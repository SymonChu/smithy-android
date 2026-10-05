package dev.smithy.fs

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * tar / tar.gz / tgz 的只读浏览与解压。
 *
 * 为什么自己写而不引库：tar 的读路径只有两种块 —— 头（512B 定长）与数据
 * （按头里的 size 向上取整到 512）。gz 套一层 [GZIPInputStream] 就完。
 * 为这两件事引 commons-compress（~1MB + 它对 7z/rar 的 native 探测）不值，
 * 而 Smithy 已经有一整套「zip 当文件系统」的 UI，接进来只差数据源。
 *
 * **7z / rar 明确不支持，识别后说清楚**：这两种格式解析器很重（LZMA 复杂
 * 状态机 / RAR 专有算法），开源实现要么巨型要么许可证不干净。做一个
 * 「点了告诉你为什么打不开」的诚实失败，比塞一个半吊子解析器好。
 */
class TarReader private constructor(private val raw: InputStream) : AutoCloseable {

    /** 一个条目（展示用）。dir 由「名字以 / 结尾或 size==0 且 type 是目录」判断。 */
    data class TarEntry(
        override val path: String,
        override val size: Long,
        override val dir: Boolean,
    ) : ArchiveEntry

    private var remainingInCurrent = 0L
    private var dataSizeInCurrent = 0L
    private var entryCount = 0

    /**
     * 数据区视图。**只限制读多少，不碰 [remainingInCurrent]**。
     *
     * 记账规则（三轮修复换来的，别再动）：
     * - `nextEntry` 把 dataSize 记为 `size`，remaining 记为 `size + pad512(size)`
     * - `skipRemaining` 只跳 `remaining`，跳完清 0
     * - dataStream 的 left = dataSize：只交出数据，padding 不给（读到 size 就该停）
     * - dataStream 实际读了多少（`read`），读完就从 remaining 里扣多少 ——
     *   剩下的正好是 padding，下一次 nextEntry 的 skipRemaining 只跳它
     * - 曾错过的三版：读时同步扣 remaining（→ padding 被当头，静默少条目）；
     *   完全不扣（→ 数据读走了还重复跳，越过下一条的头）；
     *   left 直接取 remaining（→ padding 也被当数据交出去）。
     */
    internal fun dataStream(): InputStream = object : InputStream() {
        private var left = dataSizeInCurrent
        private var read = 0L

        override fun read(): Int {
            if (left <= 0) return -1
            val b = raw.read()
            if (b >= 0) { left--; read++ }
            if (left <= 0) finish()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = raw.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) { left -= n; read += n }
            if (left <= 0) finish()
            return n
        }

        private fun finish() {
            remainingInCurrent -= read
        }
    }

    /**
     * 流式读下一条头。返回 null 表示到尾（读到全零块或 EOF）。
     *
     * **不做寻址**：这个读取器是一次性的 —— 界面先 `list()` 一遍拿清单，
     * 再要内容时重新开一个读取器扫到目标条目。tar 没有中央目录，随机访问
     * 本来就要重扫，一次性的语义最干净。
     */
    fun nextEntry(): TarEntry? {
        // 跳过上一条没读完的数据（对齐到 512）
        skipRemaining()
        val header = readHeader() ?: return null
        val name = header.substring(0, 100).cutAtNul()
        if (name.isEmpty()) return null
        val size = octal(header.substring(124, 136).cutAtNul())
        val typeChar = header.getOrNull(156) ?: '0'
        val isDir = name.endsWith("/") || typeChar == '5'
        // remaining 记「数据 + padding」：nextEntry 没被读时整体跳；
        // 被 dataStream 读过时它只扣掉已读部分（见 dataStream 的记账注释）
        remainingInCurrent = size + pad512(size)
        dataSizeInCurrent = size
        entryCount++
        // 前缀 ./ 去掉：tar 里常见但显示难看
        val cleanName = name.removePrefix("./").removeSuffix("/")
        return TarEntry(cleanName, size, isDir)
    }

    private fun skipRemaining() {
        var toSkip = remainingInCurrent
        remainingInCurrent = 0
        while (toSkip > 0) {
            val n = raw.skip(toSkip)
            if (n <= 0) {
                // skip 返回 0：流可能不支持跳（gzip 流就是这样）—— 退化成读
                val b = raw.read()
                if (b < 0) break
                toSkip--
            } else {
                toSkip -= n
            }
        }
    }

    private fun readHeader(): ByteArray? {
        val buf = ByteArray(512)
        var off = 0
        while (off < 512) {
            val n = raw.read(buf, off, 512 - off)
            if (n < 0) return null           // EOF
            if (n == 0) continue
            off += n
        }
        // 全零头 = 归档结束（标准要求两块，遇到第一块就停）
        if (buf.all { it == 0.toByte() }) return null
        // 校验和不对就说「不是 tar」而不是乱解
        val stored = octal(buf.substring(148, 156).cutAtNul())
        if (stored > 0) {
            var sum = 0L
            for (i in buf.indices) {
                sum += when (i) {
                    in 148..155 -> ' '.code.toLong()
                    else -> buf[i].toLong() and 0xFF
                }
            }
            if (sum != stored) return null
        }
        return buf
    }

    private fun pad512(size: Long) = if (size % 512 == 0L) 0L else 512 - (size % 512)

    private fun String.cutAtNul(): String {
        val i = indexOf('\u0000')
        return if (i >= 0) substring(0, i) else this
    }

    /**
     * 八进制字段（GNU 的 base-256 扩展这里不解析：我们只读自己环境里产的包）。
     *
     * **曾经写反过**：先是 `s.toLong()`（按十进制读），再 `toString(8).toLong()`
     * ——两次换基把数值搅碎，size ≤ 7 时碰巧相等，checksum 永远不等，于是
     * 整档「读不出条目」而错误信息只有一个笼统的空列表。教训：**单位转换
     * 必须有「已知值对照」的测试**（本文件的 header() 手写头 + python tarfile
     * 参考档就是干这个的），纯逻辑测试对这种 bug 是盲的。
     */
    private fun octal(field: String): Long = field.filter { !it.isWhitespace() && it != '\u0000' }
        .takeIf { it.isNotBlank() }?.toLongOrNull(8) ?: 0L

    private fun ByteArray.substring(a: Int, b: Int) = String(this, a, b - a, Charsets.ISO_8859_1)

    override fun close() {
        runCatching { raw.close() }
    }

    companion object {
        /** 判断该归档归我们管。 */
        fun handles(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz")
        }

        /** 判断这是 7z / rar —— 我们不支持但要认出来，说清楚而不是乱试。 */
        fun isUnsupportedArchive(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".7z") || n.endsWith(".rar") || n.endsWith(".rar5")
        }

        fun open(file: File): TarReader {
            val fis = file.inputStream().buffered()
            val stream = if (file.name.lowercase().endsWith(".gz") || file.name.lowercase().endsWith(".tgz")) {
                GZIPInputStream(fis)
            } else {
                fis
            }
            return TarReader(stream)
        }

        /** 列出全部条目（扫一遍全档）。 */
        fun list(file: File): List<TarEntry> {
            open(file).use { r ->
                val out = ArrayList<TarEntry>()
                while (true) {
                    val e = r.nextEntry() ?: break
                    out += e
                }
                return out
            }
        }

        /**
         * 读出一条内容。tar 没有中央目录，只能从头扫 —— 调用方（UI）自己
         * 保证别在列表滚动时反复调它。
         *
         * **走 [TarReader.dataStream] 而不是直接碰 raw**：读完 size 字节后
         * 还剩 512 对齐的 padding，`nextEntry` 的 skipRemaining 只认
         * remainingInCurrent —— 不记账的话 padding 会被当成下一条的头。
         */
        fun readEntry(file: File, entryPath: String): ByteArray {
            open(file).use { r ->
                while (true) {
                    val e = r.nextEntry() ?: break
                    if (e.path == entryPath) {
                        val out = ByteArrayOutputStream(if (e.size > 8192L) e.size.toInt() else 8192)
                        r.dataStream().use { it.copyTo(out) }
                        return out.toByteArray()
                    }
                }
            }
            throw IllegalArgumentException("归档里没有这条：$entryPath")
        }

        /** 把整档解开到目录。已存在的文件跳过并报出。 */
        fun extractAll(file: File, targetDir: File): List<File> {
            val out = ArrayList<File>()
            open(file).use { r ->
                while (true) {
                    val e = r.nextEntry() ?: break
                    val dst = File(targetDir, e.path)
                    if (e.dir) {
                        dst.mkdirs()
                        continue
                    }
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) continue   // 不覆盖：和文件管理器的粘贴一致
                    dst.outputStream().use { o -> r.dataStream().copyTo(o) }
                    out += dst
                }
            }
            return out
        }
    }
}

/** 归档条目的公共形态 —— zip / tar 的 UI 共用。 */
interface ArchiveEntry {
    val path: String
    val size: Long
    val dir: Boolean
}
