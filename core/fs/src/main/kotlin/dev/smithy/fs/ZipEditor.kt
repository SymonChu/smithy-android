package dev.smithy.fs

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 一个 zip 条目的信息（列表展示用）。 */
data class ZipEntryInfo(
    val path: String,
    val size: Long,
    val compressedSize: Long,
    val crc: Long,
    val method: Int,
    val dir: Boolean,
) {
    /** 未压缩存储。apk 里的 `resources.arsc` 必须是这种，且要 4 字节对齐。 */
    val stored: Boolean get() = method == ZipEntry.STORED

    /** 目录层级（`a/b/c.txt` → `a/b`），列表里按层展开用。 */
    val parent: String get() = path.trimEnd('/').substringBeforeLast('/', "")
}

/** 写出结果。 */
data class ZipWriteReport(
    /** 原样搬运的条目数 */
    val copied: Int,
    /** 新写入或替换的条目数 */
    val written: Int,
    /** 删除的条目数 */
    val deleted: Int,
    /** 产物大小 */
    val bytes: Long,
)

/**
 * zip / apk 的直改。
 *
 * 与「打开为工程」（`dev.smithy.engine.ApkProject`）的分工：
 * - **工程**是改包：解析 dex 与资源、记改动、可回退、重打包后签名
 * - **这里**是改文件：把 zip 当文件系统，替换 / 删除 / 新增条目，**不解析内容**
 *
 * 用途是「换个 so 库」「塞个配置文件」「删掉多余的资源」这类不需要理解包结构的操作。
 *
 * **改完 apk 签名会失效**：签名覆盖的是条目内容，动一个字节就废。所以用它改完 apk，
 * 仍然要走签名流程 —— 界面层要把这句说明白，不能让用户以为改完就能装。
 */
class ZipEditor private constructor(private val source: File) : AutoCloseable {

    private val zip = ZipFile(source)

    /** 待写入的改动：路径 → 新内容；值为 null 表示删除。 */
    private val puts = linkedMapOf<String, ByteArray?>()

    fun entries(): List<ZipEntryInfo> = zip.entries().asSequence()
        .map { e ->
            ZipEntryInfo(
                path = e.name,
                size = e.size.coerceAtLeast(0),
                compressedSize = e.compressedSize.coerceAtLeast(0),
                crc = e.crc.coerceAtLeast(0),
                method = e.method,
                dir = e.isDirectory,
            )
        }
        .sortedWith(compareBy({ it.path.count { c -> c == '/' } }, { it.path }))
        .toList()

    /** 读条目内容。改动过就返回改动后的，否则读原文件。 */
    fun read(path: String): ByteArray {
        puts[path]?.let { return it ?: error("$path 已被标记删除") }
        val entry = zip.getEntry(path) ?: throw NoSuchElementException("zip 里没有 $path")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun put(path: String, bytes: ByteArray) {
        require(path.isNotBlank()) { "路径不能为空" }
        puts[path] = bytes
    }

    fun delete(path: String) {
        if (zip.getEntry(path) == null && !puts.containsKey(path)) {
            throw NoSuchElementException("zip 里没有 $path")
        }
        puts[path] = null
    }

    /** 有没有未写出的改动。 */
    fun isDirty(): Boolean = puts.isNotEmpty()

    /** 改动条数（界面显示用）。 */
    fun changeCount(): Int = puts.size

    /**
     * 写出到 [target]。
     *
     * **未改动的条目原样搬运**：内容按原样复制，不做任何解析。压缩条目会由
     * `ZipOutputStream` 重新压缩一次（用 Java 的 zip 实现无法「不重压缩地搬运」），
     * 所以**字节级与原包不同，但解出来的内容逐字节一致** —— 这也是为什么测试里
     * 断言的是「内容一致」而不是「文件字节一致」。
     *
     * 不原地覆盖：写一半失败会把原文件毁掉。
     */
    fun writeTo(target: File): ZipWriteReport {
        require(!target.canonicalPath.equals(source.canonicalPath)) {
            "不能覆盖原文件（写一半失败会把它毁掉），先写到别的位置"
        }

        val counter = CountingOutputStream(BufferedOutputStream(target.outputStream()))
        val out = ZipOutputStream(counter)
        var copied = 0
        var written = 0
        var deleted = 0
        try {
            for (entry in zip.entries()) {
                if (puts.containsKey(entry.name)) continue // 改动过的后面统一写
                copyRaw(entry, out, counter)
                copied++
            }
            for ((path, bytes) in puts) {
                if (bytes == null) {
                    deleted++
                    continue
                }
                writeNew(path, bytes, out, counter)
                written++
            }
        } finally {
            out.close()
        }
        return ZipWriteReport(copied, written, deleted, target.length())
    }

    /**
     * 原样搬运一个条目。
     *
     * STORED 的条目（apk 里的 `resources.arsc` 就是）要**保持 STORED 且 4 字节对齐**：
     * 安装器按内存映射读它，压缩了或没对齐都会被系统拒绝安装 ——
     * 而症状是「装不上」，最难往 zip 层联想。所以这里显式补一个合法的对齐填充。
     */
    private fun copyRaw(entry: ZipEntry, out: ZipOutputStream, counter: CountingOutputStream) {
        val copy = ZipEntry(entry.name).apply {
            method = entry.method
            time = entry.time
            comment = entry.comment
            if (entry.method == ZipEntry.STORED) {
                size = entry.size
                compressedSize = entry.size
                crc = entry.crc
                // 本地头的 30 字节固定部分**已经包含「extra 长度」那 2 字节**，
                // 所以无 extra 时的数据偏移就是 +30+名长 —— 这里不能再加 2（踩过：差 2 就不对齐）
                extra = paddedExtra(counter.written + 30 + entry.name.toByteArray().size)
            }
        }
        out.putNextEntry(copy)
        zip.getInputStream(entry).use { it.copyTo(out) }
        out.closeEntry()
    }

    private fun writeNew(path: String, bytes: ByteArray, out: ZipOutputStream, counter: CountingOutputStream) {
        // 资源表必须 STORED + 对齐（安装器按内存映射读它，压缩了会被拒绝安装）；
        // 其余按默认压缩 —— apk 里绝大多数条目本来就是 DEFLATED
        val needsStored = path == "resources.arsc"
        val entry = ZipEntry(path)
        if (needsStored) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            val crc = java.util.zip.CRC32().apply { update(bytes) }
            entry.crc = crc.value
            // 同 copyRaw：本地头的 30 字节里已含 extra 长度字段，不要再 +2
            entry.extra = paddedExtra(counter.written + 30 + path.toByteArray().size)
        }
        entry.time = System.currentTimeMillis()
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }

    /**
     * 造一段「合法的对齐填充」。
     *
     * 不能随便塞几个字节当 padding：zip 的 extra 区是「4 字节头 + 数据」的连续记录，
     * 头里带字段 id 与长度。长度与实际不符时解压器会直接报错，所以这里按规范写一条
     * 私有字段（id 0x7075）来占位。
     *
     * [offsetIfNoPadding] 是「不加填充时数据会落在哪个偏移」，据此算出还需要几个字节。
     */
    private fun paddedExtra(offsetIfNoPadding: Long): ByteArray? {
        val need = ((4 - (offsetIfNoPadding % 4)) % 4).toInt()
        if (need == 0) return null
        // 一条 extra 记录至少 4 字节（id 2 + size 2），所以补 need + 4 字节：
        // 既是 4 的倍数（对齐成立），又够放一条合法记录
        val pad = need + 4
        val dataLen = pad - 4
        return ByteArray(pad).also { buf ->
            // 私有字段 id 0xCAFE，size 填**真实数据长度** ——
            // 填 0 的话剩下的字节会被当成下一条记录的头，解压器直接报错
            buf[0] = 0xCA.toByte()
            buf[1] = 0xFE.toByte()
            buf[2] = (dataLen and 0xFF).toByte()
            buf[3] = ((dataLen shr 8) and 0xFF).toByte()
        }
    }

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        fun open(file: File): ZipEditor {
            require(file.isFile) { "不是文件：${file.absolutePath}" }
            return ZipEditor(file)
        }
    }
}

/** 数写出去多少字节。对齐要靠它算偏移，而 `ZipOutputStream` 不暴露这个。 */
private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var written: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        written++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        written += len
    }
}
