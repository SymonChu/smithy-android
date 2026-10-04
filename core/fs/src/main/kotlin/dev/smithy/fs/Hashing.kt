package dev.smithy.fs

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 文件摘要。
 *
 * **三个一起算**：不同场合要的不一样（对比下载来源常用 MD5，校验完整性用 SHA-256，
 * 有些老工具只认 SHA-1）。分开算是三遍 IO，而手机上读一个几十 MB 的文件不便宜，
 * 所以一次读完顺带把三个都算出来 —— 多算两个哈希比多读两遍快得多。
 */
object Hashing {

    data class Digests(
        val size: Long,
        val md5: String,
        val sha1: String,
        val sha256: String,
    )

    fun of(file: File): Digests = file.inputStream().use { of(it, file.length()) }

    /** 对内存里的字节算（用于压缩包内的条目，它们本来就是读进内存处理的）。 */
    fun ofBytes(bytes: ByteArray): Digests =
        of(ByteArrayInputStream(bytes), bytes.size.toLong())

    private fun of(input: InputStream, knownSize: Long): Digests {
        input.buffered(1 shl 16).use { ins ->
            val md5 = MessageDigest.getInstance("MD5")
            val sha1 = MessageDigest.getInstance("SHA-1")
            val sha256 = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md5.update(buf, 0, n)
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                total += n
            }
            return Digests(
                size = if (knownSize >= 0) knownSize else total,
                md5 = hex(md5.digest()),
                sha1 = hex(sha1.digest()),
                sha256 = hex(sha256.digest()),
            )
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * 人类可读的体积。
 *
 * 用 1024 进制（与系统和压缩工具一致）—— 混用 1000 和 1024 会让「为什么这里显示
 * 1.5MB 那里 1.4MB」变成日常困惑。
 */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

/** `1699999999999` → `2026-07-14 21:30`。 */
fun humanTime(millis: Long): String {
    if (millis <= 0) return "未知"
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date(millis))
}
