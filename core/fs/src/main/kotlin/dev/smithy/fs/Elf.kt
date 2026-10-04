package dev.smithy.fs

/**
 * ELF 的解析与**等长**字符串替换。
 *
 * 这里只做三件事：认出架构、列出节、改等长字符串。**刻意不做反汇编 / 回编** ——
 * 逆向产物没有源码语义，回编不成立，做个半成品只会误导人。真要改逻辑得走源码重编。
 *
 * ## 为什么替换必须等长
 *
 * ELF 内部到处是**偏移引用**：节头表指向节、符号表指向字符串表、重定位表指向
 * 指令位置、动态段按偏移寻址。往中间插一个字节，后面所有东西的位置都变了，
 * 而那些引用还指着老位置 —— 结果是一个**能骗过 `readelf`、加载时才崩**的文件。
 * 变长替换的正确路径是源码重编，不是二进制补丁。
 *
 * ## 为什么有些节一律拒绝
 *
 * [REFUSED_SECTIONS] 里的节（`.dynstr` / `.gnu.hash` / `.hash` 等）被**按内容索引**：
 * 动态链接器用 GNU/SysV 哈希表查找符号名，改掉一个名字会让哈希对不上，
 * 那个符号就再也找不到了 —— 而且是加载期才炸。这类改动不算「等长就安全」。
 */
object Elf {

    /** 一律拒绝改动的节：它们被按内容索引，等长也不安全。 */
    val REFUSED_SECTIONS = setOf(
        ".dynstr",     // 动态符号名。GNU/SysV 哈希表按内容查找
        ".dynsym",     // 动态符号表本身
        ".gnu.hash",   // 哈希表
        ".hash",
        ".shstrtab",   // 节名表：节头按偏移引用，名字改了节名就全乱
        ".strtab",     // 符号名表
        ".symtab",
        ".dynamic",    // 动态段：按偏移寻址
        ".rela.dyn",
        ".rela.plt",
        ".rel.dyn",
        ".rel.plt",
    )

    /** 机器类型（`e_machine`）→ ABI 名。只列 Android 上会遇到的。 */
    private val MACHINES = mapOf(
        0x28 to "arm",
        0xB7 to "aarch64",
        0x03 to "x86",
        0x3E to "x86_64",
        0xF3 to "riscv64",
    )

    private val TYPES = mapOf(
        1 to "REL", 2 to "EXEC", 3 to "DYN（共享库 / PIE）", 4 to "CORE",
    )

    /** 一个节头。 */
    data class Section(
        val index: Int,
        val name: String,
        val type: Int,
        val offset: Long,
        val size: Long,
        val addr: Long,
    ) {
        /** 这个偏移是否落在本节里。 */
        fun contains(off: Long): Boolean = off >= offset && off < offset + size
    }

    /** 解析结果。 */
    data class ElfFile(
        val is64Bit: Boolean,
        val littleEndian: Boolean,
        val machine: Int,
        val type: Int,
        val entryPoint: Long,
        val sections: List<Section>,
    ) {
        /** ABI 名（认不出时给出十六进制机器号，而不是编一个名字）。 */
        val abiName: String get() = MACHINES[machine] ?: "machine=0x%x".format(machine)

        val typeName: String get() = TYPES[type] ?: "type=$type"

        /** 某个文件偏移落在哪个节里（不在任何节里返回 null）。 */
        fun sectionAt(offset: Long): Section? = sections.firstOrNull { it.contains(offset) }
    }

    /**
     * 解析。
     *
     * 不是 ELF、或者结构不完整时返回 null —— 调用方据此说「这不是 ELF」，
     * 而不是拿到一个半成品对象。
     */
    fun parse(bytes: ByteArray): ElfFile? {
        if (bytes.size < 0x34) return null
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return null
        }

        val cls = bytes[4].toInt() and 0xFF
        val data = bytes[5].toInt() and 0xFF
        if (cls != 1 && cls != 2) return null
        if (data != 1 && data != 2) return null
        val is64 = cls == 2
        val le = data == 1

        fun u16(off: Int): Int = readU16(bytes, off, le) ?: 0
        fun u32(off: Int): Long = readU32(bytes, off, le) ?: 0L
        fun u64(off: Int): Long = readU64(bytes, off, le)

        val machine = u16(18)
        val type = u16(16)

        val entry: Long
        val shoff: Long
        val shentsize: Int
        val shnum: Int
        val shstrndx: Int
        if (is64) {
            entry = readU64(bytes, 24, le)
            shoff = readU64(bytes, 40, le)
            shentsize = u16(58)
            shnum = u16(60)
            shstrndx = u16(62)
        } else {
            entry = u32(24)
            shoff = u32(32)
            shentsize = u16(46)
            shnum = u16(48)
            shstrndx = u16(50)
        }

        // 节头表读不出来不算致命：.so 被 strip 过或畸形时都可能没有，
        // 但「架构是什么」仍然有用
        val sections = readSections(bytes, is64, le, shoff, shentsize, shnum, shstrndx)

        return ElfFile(
            is64Bit = is64,
            littleEndian = le,
            machine = machine,
            type = type,
            entryPoint = entry,
            sections = sections,
        )
    }

    private fun readSections(
        bytes: ByteArray,
        is64: Boolean,
        le: Boolean,
        shoff: Long,
        shentsize: Int,
        shnum: Int,
        shstrndx: Int,
    ): List<Section> {
        if (shoff <= 0 || shnum <= 0 || shentsize <= 0) return emptyList()
        val minEnt = if (is64) 64 else 40
        if (shentsize < minEnt) return emptyList()
        val end = shoff + shentsize.toLong() * shnum
        if (shoff < 0 || end > bytes.size) return emptyList()

        // 先把原始字段读出来，再靠 shstrndx 那个节解析名字
        data class Raw(val nameOff: Long, val type: Int, val offset: Long, val size: Long, val addr: Long)

        val raws = (0 until shnum).mapNotNull { i ->
            val base = (shoff + shentsize.toLong() * i).toInt()
            val type = readU32(bytes, base + 4, le)?.toInt() ?: return@mapNotNull null
            if (is64) {
                Raw(
                    nameOff = readU32(bytes, base, le)?.toLong() ?: 0L,
                    type = type,
                    addr = readU64(bytes, base + 16, le),
                    offset = readU64(bytes, base + 24, le),
                    size = readU64(bytes, base + 32, le),
                )
            } else {
                Raw(
                    nameOff = readU32(bytes, base, le) ?: 0L,
                    type = type,
                    addr = readU32(bytes, base + 12, le) ?: 0L,
                    offset = readU32(bytes, base + 16, le) ?: 0L,
                    size = readU32(bytes, base + 20, le) ?: 0L,
                )
            }
        }

        // 节名表
        val shstr = raws.getOrNull(shstrndx)
        val names = shstr?.let { readCStringTable(bytes, it.offset, it.size) } ?: emptyMap()

        return raws.mapIndexed { i, r ->
            Section(
                index = i,
                // 按**字节偏移**取名字。节头里的 sh_name 存的是偏移，不是序号 ——
                // 按序号取的话，偏移恰好等于序号的那个节（比如偏移 1）会蒙对，
                // 其它节名全空，而且看着像「这个节没有名字」
                name = names[r.nameOff.toInt()] ?: "",
                type = r.type,
                offset = r.offset,
                size = r.size,
                addr = r.addr,
            )
        }
    }

    /**
     * 读一个「`\0` 分隔」的字符串表，返回**字节偏移 → 文本**。
     *
     * 必须按偏移做键：引用方（节头的 `sh_name`、符号表的 `st_name`）存的都是偏移。
     */
    private fun readCStringTable(bytes: ByteArray, offset: Long, size: Long): Map<Int, String> {
        if (offset < 0 || size <= 0) return emptyMap()
        val end = minOf(offset + size, bytes.size.toLong())
        if (offset >= end) return emptyMap()

        val out = HashMap<Int, String>()
        val start = offset.toInt()
        val stop = end.toInt()
        var p = start
        while (p < stop) {
            // 手工找 NUL：ByteArray.indexOf 只收一个参数，不能从 p 开始找
            var zero = p
            while (zero < stop && bytes[zero] != 0.toByte()) zero++
            out[p - start] = String(bytes, p, zero - p, Charsets.UTF_8)
            p = zero + 1
        }
        return out
    }

    // ── 字符串扫描 ──────────────────────────────────────────────

    /**
     * 扫出可打印字符串，像 `strings` 那样。
     *
     * [minLength] 默认 4 —— 再短的东西（单字母、两位十六进制）到处都是，
     * 列出来只会把有用的淹没。
     */
    fun scanStrings(bytes: ByteArray, minLength: Int = 4, limit: Int = 4000): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        var i = 0
        while (i < bytes.size && out.size < limit) {
            if (isStringByte(bytes[i])) {
                val start = i
                while (i < bytes.size && isStringByte(bytes[i])) i++
                val len = i - start
                if (len >= minLength) {
                    out += start.toLong() to String(bytes, start, len, Charsets.UTF_8)
                }
            } else {
                i++
            }
        }
        return out
    }

    private fun isStringByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        // 制表符也算（脚本 / 配置里常见），但换行不算 —— 它是分隔符
        return v == 0x09 || v in 0x20..0x7E
    }

    /** 找出 [text] 在文件里出现的所有偏移。 */
    fun findOffsets(bytes: ByteArray, text: String): List<Long> {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > bytes.size) return emptyList()
        val out = ArrayList<Long>()
        var i = 0
        while (i <= bytes.size - needle.size) {
            var j = 0
            while (j < needle.size && bytes[i + j] == needle[j]) j++
            if (j == needle.size) out += i.toLong()
            i++
        }
        return out
    }

    // ── 替换 ────────────────────────────────────────────────────

    /** 替换失败的原因。 */
    sealed interface PatchCheck {
        /** 可以改。 */
        data class Ok(val offset: Long) : PatchCheck
        /** 不行，[reason] 是给人看的话。 */
        data class Rejected(val reason: String) : PatchCheck
    }

    /**
     * 判断这次改串能不能做，能则返回应改的偏移。
     *
     * 三条都要过：
     * 1. **等长** —— 见类注释，变长会破坏偏移引用
     * 2. **不在 [REFUSED_SECTIONS] 里** —— 那些节被按内容索引，等长也不安全
     * 3. **匹配唯一**（除非给了 [explicitOffset]）—— 同一句话可能在多个地方出现，
     *    猜一个改掉是「有时候改了有时候没改」的来源
     */
    fun checkPatch(
        bytes: ByteArray,
        old: String,
        new: String,
        explicitOffset: Long? = null,
        elf: ElfFile? = parse(bytes),
    ): PatchCheck {
        val oldBytes = old.toByteArray(Charsets.UTF_8)
        val newBytes = new.toByteArray(Charsets.UTF_8)

        if (oldBytes.isEmpty()) return PatchCheck.Rejected("原串不能是空的")
        if (old == new) return PatchCheck.Rejected("新串和原串一样，没什么可改的")

        // **先定位，再谈长度**。反过来的话，一个根本不存在的串会报「长度不一样」——
        // 那会把人引去数长度，而真正的问题是那句话在这个文件里压根没有
        val at: Long = if (explicitOffset != null) {
            if (!bytes.containsAt(explicitOffset, oldBytes)) {
                return PatchCheck.Rejected(
                    "偏移 ${HexEdit.formatOffset(explicitOffset)} 处不是要替换的那串内容 —— " +
                        "先确认一下这个偏移指的是哪儿",
                )
            }
            explicitOffset
        } else {
            val hits = findOffsets(bytes, old)
            if (hits.isEmpty()) return PatchCheck.Rejected("没找到「$old」")
            if (hits.size > 1) {
                val shown = hits.take(5).joinToString(", ") { HexEdit.formatOffset(it) }
                val more = if (hits.size > 5) " …" else ""
                return PatchCheck.Rejected(
                    "「$old」在文件里出现了 ${hits.size} 次（偏移 $shown$more）—— " +
                        "说清改哪一个，别让我猜",
                )
            }
            hits[0]
        }

        if (oldBytes.size != newBytes.size) {
            return PatchCheck.Rejected(
                "新串 ${newBytes.size} 字节、原串 ${oldBytes.size} 字节，长度不一样。" +
                    "ELF 内部到处是按偏移的引用（节头表、符号表、重定位、动态段），" +
                    "插一个字节后面全会错位 —— 改出来是个能骗过 readelf、" +
                    "加载时才崩的文件。变长的正确路径是改源码重编",
            )
        }

        // 落在被索引的节里就拒绝
        val sec = elf?.sectionAt(at)
        if (sec != null && sec.name in REFUSED_SECTIONS) {
            return PatchCheck.Rejected(
                "这个偏移在节「${sec.name}」里，这个节被**按内容索引**（动态链接器按哈希表" +
                    "查符号名、节头按偏移查节名），改掉一个名字会让查找对不上 —— " +
                    "而且要到加载时才炸。等长也不安全，所以一律拒绝",
            )
        }

        return PatchCheck.Ok(at)
    }

    private fun ByteArray.containsAt(offset: Long, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > size) return false
        for (i in needle.indices) {
            if (this[offset.toInt() + i] != needle[i]) return false
        }
        return true
    }

    /**
     * 执行等长替换，返回新数组。
     *
     * 校验交给 [checkPatch]；这里只做覆盖（长度不变，见 [HexEdit.overwrite]）。
     */
    fun patch(bytes: ByteArray, offset: Long, new: String): ByteArray =
        HexEdit.overwrite(bytes, offset, new.toByteArray(Charsets.UTF_8))

    // ── 小工具 ─────────────────────────────────────────────────

    private fun readU16(b: ByteArray, off: Int, le: Boolean): Int? {
        if (off < 0 || off + 2 > b.size) return null
        val a = b[off].toInt() and 0xFF
        val c = b[off + 1].toInt() and 0xFF
        return if (le) a or (c shl 8) else (a shl 8) or c
    }

    private fun readU32(b: ByteArray, off: Int, le: Boolean): Long? {
        if (off < 0 || off + 4 > b.size) return null
        val v = (0 until 4).sumOf { i ->
            (b[off + i].toLong() and 0xFF) shl (8 * if (le) i else (3 - i))
        }
        return v
    }

    private fun readU64(b: ByteArray, off: Int, le: Boolean): Long {
        if (off < 0 || off + 8 > b.size) return 0L
        var v = 0L
        for (i in 0 until 8) {
            val sh = 8 * if (le) i else (7 - i)
            v = v or ((b[off + i].toLong() and 0xFF) shl sh)
        }
        return v
    }
}
