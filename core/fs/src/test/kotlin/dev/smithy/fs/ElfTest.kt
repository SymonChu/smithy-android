package dev.smithy.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElfTest {

    // ── 手工造一个最小 ELF64（aarch64 / 小端）───────────────────
    //
    // 布局：
    //   0x00  ELF 头（64 字节）
    //   0x40  .rodata    32 字节，里面放可打印串
    //   0x60  .dynstr    32 字节，放一个符号名
    //   0x80  .shstrtab  27 字节
    //   0x100 节头表（4 个 * 64 字节）
    //
    // 自己造而不是用真 .so：这样每个字节都是已知的，断言能精确到偏移。

    private val rodataOffset = 0x40
    private val dynstrOffset = 0x60
    private val shstrtabOffset = 0x80
    private val shoff = 0x100

    private val rodataText = "Hello Smithy World"
    private val dynstrText = "origName"

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Long): ByteArray = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun le64(v: Long): ByteArray = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun buildElf(): ByteArray {
        val buf = ByteArray(shoff + 4 * 64)

        fun put(off: Int, b: ByteArray) = b.copyInto(buf, off)

        // e_ident
        put(0, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        buf[4] = 2      // ELFCLASS64
        buf[5] = 1      // ELFDATA2LSB
        buf[6] = 1      // EV_CURRENT
        put(16, le16(3))            // e_type = DYN
        put(18, le16(0xB7))         // e_machine = AArch64
        put(20, le32(1))            // e_version
        put(24, le64(0x1000))       // e_entry
        put(32, le64(0))            // e_phoff
        put(40, le64(shoff.toLong())) // e_shoff
        put(52, le16(64))           // e_ehsize
        put(54, le16(56))           // e_phentsize
        put(56, le16(0))            // e_phnum
        put(58, le16(64))           // e_shentsize
        put(60, le16(4))            // e_shnum
        put(62, le16(3))            // e_shstrndx = .shstrtab

        // 节内容
        put(rodataOffset, rodataText.toByteArray())
        put(dynstrOffset, dynstrText.toByteArray())
        val shstr = "\u0000.rodata\u0000.dynstr\u0000.shstrtab\u0000"
        put(shstrtabOffset, shstr.toByteArray())

        // 节名在 .shstrtab 里的偏移
        val nameRodata = 1
        val nameDynstr = 9
        val nameShstrtab = 17

        fun sectionHeader(idx: Int, nameOff: Int, type: Int, addr: Long, offset: Long, size: Long) {
            val base = shoff + idx * 64
            put(base, le32(nameOff.toLong()))
            put(base + 4, le32(type.toLong()))
            put(base + 8, le64(2))          // SHF_ALLOC
            put(base + 16, le64(addr))
            put(base + 24, le64(offset))
            put(base + 32, le64(size))
            put(base + 40, le32(0))         // sh_link
            put(base + 44, le32(0))         // sh_info
            put(base + 48, le64(8))         // sh_addralign
            put(base + 56, le64(0))         // sh_entsize
        }

        // [0] 空节头保持全零
        sectionHeader(1, nameRodata, 1, 0x2000, rodataOffset.toLong(), 32)
        sectionHeader(2, nameDynstr, 3, 0x3000, dynstrOffset.toLong(), 32)
        sectionHeader(3, nameShstrtab, 3, 0, shstrtabOffset.toLong(), shstr.length.toLong())

        return buf
    }

    // ── 解析 ────────────────────────────────────────────────────

    @Test
    fun `认出架构与类型`() {
        val elf = assertNotNull(Elf.parse(buildElf()))
        assertTrue(elf.is64Bit)
        assertTrue(elf.littleEndian)
        assertEquals("aarch64", elf.abiName)
        assertTrue(elf.typeName.contains("DYN"), "共享库该被认成 DYN：${elf.typeName}")
        assertEquals(0x1000L, elf.entryPoint)
    }

    @Test
    fun `不是 ELF 时返回 null`() {
        assertNull(Elf.parse(ByteArray(64)))
        assertNull(Elf.parse("PK\u0003\u0004not an elf at all".toByteArray()))
        assertNull(Elf.parse(ByteArray(4)))   // 太短
    }

    @Test
    fun `节名与偏移解析正确`() {
        val elf = assertNotNull(Elf.parse(buildElf()))
        val names = elf.sections.map { it.name }
        assertEquals(listOf("", ".rodata", ".dynstr", ".shstrtab"), names)

        val rodata = elf.sections[1]
        assertEquals(rodataOffset.toLong(), rodata.offset)
        assertEquals(32L, rodata.size)
    }

    @Test
    fun `按偏移能查出落在哪个节里`() {
        val elf = assertNotNull(Elf.parse(buildElf()))
        assertEquals(".rodata", elf.sectionAt(rodataOffset.toLong())?.name)
        assertEquals(".dynstr", elf.sectionAt(dynstrOffset.toLong())?.name)
        // 落在缝隙里
        assertNull(elf.sectionAt(0x30))
    }

    // ── 字符串扫描 ──────────────────────────────────────────────

    @Test
    fun `扫得出可打印字符串`() {
        val all = Elf.scanStrings(buildElf(), minLength = 4).map { it.second }
        assertTrue(all.contains(rodataText), "该扫出 .rodata 里的串：$all")
        assertTrue(all.contains(dynstrText))
        // 节名表里的名字也该扫到
        assertTrue(all.contains(".rodata"))
    }

    @Test
    fun `短串默认被滤掉`() {
        // 一两个字符的东西在二进制里到处都是，列出来只会把有用的淹掉
        val long = Elf.scanStrings(buildElf(), minLength = 10).map { it.second }
        assertTrue(long.contains(rodataText))
        assertTrue(!long.contains("ELF"))
    }

    @Test
    fun `找得到偏移 也数得出出现次数`() {
        val bytes = buildElf()
        assertEquals(listOf(rodataOffset.toLong()), Elf.findOffsets(bytes, rodataText))
        // 节名表里 ".rodata" 只出现一次；"o" 则到处都是
        assertTrue(Elf.findOffsets(bytes, "o").size > 1)
        assertTrue(Elf.findOffsets(bytes, "根本没有这串").isEmpty())
    }

    // ── 替换前校验 ──────────────────────────────────────────────

    @Test
    fun `等长替换给出偏移`() {
        val check = Elf.checkPatch(buildElf(), rodataText, "Hello Smithy Werld")
        val ok = check as? Elf.PatchCheck.Ok
        assertNotNull(ok, "等长且在 rodata 里，该被允许：$check")
        assertEquals(rodataOffset.toLong(), ok.offset)
    }

    @Test
    fun `变长替换被拒 并说清为什么`() {
        // 这是 M6 验收里明确要求的一条：变长必须拒绝，并提示走源码重编
        val check = Elf.checkPatch(buildElf(), rodataText, "Hello Smithy World!!")
        val bad = check as? Elf.PatchCheck.Rejected
        assertNotNull(bad, "变长必须拒绝：$check")
        assertTrue(bad.reason.contains("长度不一样"), "要说清是长度问题：${bad.reason}")
        assertTrue(bad.reason.contains("重编"), "要给出正确路径：${bad.reason}")
        assertTrue(bad.reason.contains("偏移"), "要说清原理是偏移引用：${bad.reason}")
    }

    @Test
    fun `改在被按内容索引的节里一律拒绝`() {
        // .dynstr 里的符号名被 GNU/SysV 哈希表按内容查找。改掉名字后哈希对不上，
        // 那个符号就再也找不到了 —— 而且是加载期才炸
        val check = Elf.checkPatch(buildElf(), dynstrText, "origNamf")
        val bad = check as? Elf.PatchCheck.Rejected
        assertNotNull(bad, ".dynstr 里即使等长也该拒绝：$check")
        assertTrue(bad.reason.contains(".dynstr"), "要说清是哪个节：${bad.reason}")
        assertTrue(bad.reason.contains("索引"), "要说清原因是被索引：${bad.reason}")
    }

    @Test
    fun `找不到原串时说找不到`() {
        val check = Elf.checkPatch(buildElf(), "不存在的串", "另一个不存在的")
        val bad = check as? Elf.PatchCheck.Rejected
        assertNotNull(bad)
        assertTrue(bad.reason.contains("没找到"), bad.reason)
    }

    @Test
    fun `多处命中时拒绝 并列出偏移`() {
        // 同一句话可能出现在多个地方。猜一个改掉，就成了「有时候改了有时候没改」
        val bytes = buildElf().copyOf()
        // 在 e_ident 的填充区（偏移 8，不属于任何节）再塞一份**原串**
        rodataText.toByteArray().copyInto(bytes, 0x08)

        val check = Elf.checkPatch(bytes, rodataText, "Hello Smithy Werld")
        val bad = check as? Elf.PatchCheck.Rejected
        assertNotNull(bad, "多处命中该拒绝：$check")
        assertTrue(bad.reason.contains("出现了"), bad.reason)
        assertTrue(bad.reason.contains("2 次"), "要说清几次：${bad.reason}")
    }

    @Test
    fun `显式给偏移时不再要求唯一`() {
        val bytes = buildElf().copyOf()
        rodataText.toByteArray().copyInto(bytes, 0x08)
        val check = Elf.checkPatch(
            bytes,
            rodataText,
            "Hello Smithy Werld",
            explicitOffset = 0x08,
        )
        assertTrue(check is Elf.PatchCheck.Ok, "指明了偏移就该照办：$check")
    }

    @Test
    fun `显式偏移指错地方时拒绝`() {
        val check = Elf.checkPatch(buildElf(), rodataText, "Hello Smithy Werld", explicitOffset = 0x8)
        val bad = check as? Elf.PatchCheck.Rejected
        assertNotNull(bad, "偏移处内容不是要替换的串，该拒绝：$check")
        assertTrue(bad.reason.contains("偏移"), bad.reason)
    }

    @Test
    fun `新旧一模一样时拒绝`() {
        val check = Elf.checkPatch(buildElf(), rodataText, rodataText)
        val bad = check as? Elf.PatchCheck.Rejected
        assertNotNull(bad)
        assertTrue(bad.reason.contains("没什么可改"), bad.reason)
    }

    // ── 执行替换 ────────────────────────────────────────────────

    @Test
    fun `替换之后长度不变 其它字节一个没动`() {
        val before = buildElf()
        val check = Elf.checkPatch(before, rodataText, "Hello Smithy Werld") as Elf.PatchCheck.Ok
        val after = Elf.patch(before, check.offset, "Hello Smithy Werld")

        assertEquals(before.size, after.size, "长度必须不变")
        // 真正该守的不变量：**差异只出现在被替换的那段范围内**，其余一个字节没动
        val diff = before.indices.filter { before[it] != after[it] }
        assertTrue(diff.isNotEmpty(), "总得有点变化")
        assertTrue(
            diff.all { it in rodataOffset until (rodataOffset + rodataText.length) },
            "改动跑出被替换的范围了：$diff（范围 $rodataOffset..${rodataOffset + rodataText.length - 1}）",
        )

        // 替换后仍是可解析的 ELF，节表还在
        val elf = assertNotNull(Elf.parse(after))
        assertEquals(listOf("", ".rodata", ".dynstr", ".shstrtab"), elf.sections.map { it.name })
    }

    @Test
    fun `替换后新串出现在老位置`() {
        val before = buildElf()
        val after = Elf.patch(before, rodataOffset.toLong(), "Jello Smithy World")
        assertEquals(listOf(rodataOffset.toLong()), Elf.findOffsets(after, "Jello Smithy World"))
        assertTrue(Elf.findOffsets(after, rodataText).isEmpty())
    }
}
