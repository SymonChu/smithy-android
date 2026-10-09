package dev.smithy.engine.internal

import java.io.File
import java.io.RandomAccessFile

/** zip 里一个条目的位置信息（只取校验需要的那几个字段）。 */
internal data class ZipEntryOffset(
    val name: String,
    val method: Int,
    val localOffset: Long,
    /** 压缩数据真正的起点：本地头 + 30 + 文件名长度 + extra 长度。 */
    val dataOffset: Long,
)

/**
 * 只读地解析 zip 的条目偏移。
 *
 * ## 为什么要这个（而不是用 `java.util.zip.ZipFile`）
 *
 * `ZipFile` 不暴露「数据在文件里的绝对偏移」，而**对齐检查恰恰只看这个数**：
 * Android 要求未压缩的 `resources.arsc` 4 字节对齐、未压缩的 `.so` 16KB 页对齐，
 * 违反就在装机时报 `Failed to extract native libraries, res=-2`（一个字都不提 zip）。
 *
 * 偏移必须自己算：EOCD → 中央目录 → 本地头。**要读本地头的 extra 长度**，
 * 因为对齐填充正是在那里补的 —— 中央目录里的 extra 可能还是旧值。
 *
 * ## 用 RandomAccessFile 而不是把整包读进内存
 *
 * 手机上打开的包动辄上百 MB，全读进内存是拿内存换几十行代码，不划算。
 * 这里只读 EOCD 的尾巴、中央目录、以及每个条目的本地头前 30 字节。
 *
 * 读不出来（不是 zip、ZIP64、结构损坏）时返回空表 —— 校验报告里会写成
 * 「读不出条目偏移」，而不是让整个验证崩掉。
 */
internal object ZipOffsets {

    private const val EOCD_SIG = 0x06054b50L
    private const val CENTRAL_SIG = 0x02014b50L
    private const val LOCAL_SIG = 0x04034b50L

    /** EOCD 最多 22 + 65535 字节（注释最长 64KB）。 */
    private const val EOCD_MAX_BACK = 22L + 65535L

    fun read(apk: File): List<ZipEntryOffset> = runCatching {
        RandomAccessFile(apk, "r").use { raf -> parse(raf) }
    }.getOrDefault(emptyList())

    private fun parse(raf: RandomAccessFile): List<ZipEntryOffset> {
        val fileLen = raf.length()
        if (fileLen < 22) return emptyList()

        val back = minOf(fileLen, EOCD_MAX_BACK).toInt()
        val tail = ByteArray(back)
        raf.seek(fileLen - back)
        raf.readFully(tail)

        var eocd = -1
        for (i in back - 22 downTo 0) {
            if (u32(tail, i) == EOCD_SIG) {
                eocd = i
                break
            }
        }
        if (eocd < 0) return emptyList()

        val count = u16(tail, eocd + 10)
        val cdSize = u32(tail, eocd + 12)
        val cdOffset = u32(tail, eocd + 16)
        // ZIP64（EOCD 里写满 0xFFFFFFFF）我们没写过，读也只读不猜
        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) return emptyList()
        if (cdOffset + cdSize > fileLen) return emptyList()

        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)

        val out = ArrayList<ZipEntryOffset>(count)
        var p = 0
        var read = 0
        while (read < count && p + 46 <= cd.size) {
            if (u32(cd, p) != CENTRAL_SIG) break

            val method = u16(cd, p + 10)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            val localOffset = u32(cd, p + 42)
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)

            if (localOffset + 30 <= fileLen) {
                val head = ByteArray(30)
                raf.seek(localOffset)
                raf.readFully(head)
                if (u32(head, 0) == LOCAL_SIG) {
                    val localExtra = u16(head, 28)
                    out += ZipEntryOffset(
                        name = name,
                        method = method,
                        localOffset = localOffset,
                        dataOffset = localOffset + 30 + nameLen + localExtra,
                    )
                }
            }

            p += 46 + nameLen + extraLen + commentLen
            read++
        }
        return out
    }

    private fun u16(a: ByteArray, o: Int): Int =
        (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(a: ByteArray, o: Int): Long =
        u16(a, o).toLong() or (u16(a, o + 2).toLong() shl 16)
}
