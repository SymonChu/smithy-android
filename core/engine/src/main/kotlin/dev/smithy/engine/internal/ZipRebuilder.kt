package dev.smithy.engine.internal

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK 重打包。
 *
 * 两条路径：
 *  - **增量（默认）**：未改动的条目直接从原 zip 搬运原始压缩字节，不重新压缩。
 *    改一个字符串只需搬运整个包，而不是把 26MB 重压一遍 —— 这是"手机上改包"能不能等得住的关键。
 *  - **全量（兜底）**：碰到 zip64 / data descriptor 这类没手工处理的形态时，退回
 *    `ZipOutputStream` 重压。慢，但保证不产出坏包。
 *
 * 为什么不用 ZipOutputStream 一把梭：**`resources.arsc` 必须未压缩且 4 字节对齐**
 * （Android 11+ 的硬要求，违反直接装不上）。要对齐就得自己控制 local header 的
 * extra field，而 ZipOutputStream 不给这个机会。
 *
 * 自己写 zip 的主要风险点是 central directory 的偏移字段：EOCD 里的 cd 偏移必须等于
 * 写完所有条目数据后的真实位置，算错一个字节整包就废。所以这里把 cd 偏移作为参数显式传递，
 * 不放任何可变状态。
 */
internal object ZipRebuilder {

    private const val LOCAL_SIG = 0x04034b50L
    private const val CENTRAL_SIG = 0x02014b50L
    private const val EOCD_SIG = 0x06054b50L
    private const val ALIGNMENT = 4
    private const val MAX_U16 = 0xFFFF
    private const val MAX_U32 = 0xFFFFFFFFL

    private const val FLAG_DATA_DESCRIPTOR = 0x08
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    /** 对齐填充用的哑元 extra 块 ID（保留值，解析器不认识就忽略） */
    private const val PADDING_EXTRA_ID = 0xFFFF

    /** 遇到没手工处理的 zip 形态，抛它触发全量兜底 */
    private class UnsupportedZip(message: String) : Exception(message)

    data class Stats(
        val rawCopied: Int,
        val recompressed: Int,
        val deleted: Int,
        val fellBackToFull: Boolean,
    )

    /**
     * @param overlay 返回某条目的改动后内容；null 表示没改过（走 raw copy 搬运）
     * @param deleted 要从包里去掉的条目
     */
    fun rebuild(
        source: File,
        outFile: File,
        overlay: (String) -> File?,
        deleted: Set<String>,
        onProgress: ((String) -> Unit)? = null,
        forceFull: Boolean = false,
    ): Stats = if (forceFull) {
        outFile.delete()
        rebuildFull(source, outFile, overlay, deleted).copy(fellBackToFull = true)
    } else {
        try {
            rebuildIncremental(source, outFile, overlay, deleted, onProgress)
        } catch (e: UnsupportedZip) {
            outFile.delete()      // 半成品不能留下
            rebuildFull(source, outFile, overlay, deleted).copy(fellBackToFull = true)
        }
    }

    // ── 增量：搬运原始压缩字节 ────────────────────────────────

    private fun rebuildIncremental(
        source: File,
        outFile: File,
        overlay: (String) -> File?,
        deleted: Set<String>,
        onProgress: ((String) -> Unit)?,
    ): Stats {
        var rawCopied = 0
        var recompressed = 0
        var deletedCount = 0

        RandomAccessFile(source, "r").use { raf ->
            val entries = readCentralDirectory(raf)
            for (e in entries) {
                if (e.flags and FLAG_DATA_DESCRIPTOR != 0) {
                    throw UnsupportedZip("条目 ${e.name} 用了 data descriptor")
                }
            }

            FileOutputStream(outFile).use { fos ->
                val counter = CountingOutputStream(BufferedOutputStream(fos, 1 shl 16))
                val out = counter
                val written = ArrayList<Written>(entries.size)

                for (e in entries) {
                    if (e.name in deleted) {
                        deletedCount++
                        continue
                    }
                    val nameBytes = e.name.toByteArray(Charsets.UTF_8)
                    val ov = overlay(e.name)
                    // 条目的 localOffset 直接取"已写出的字节数"，不再手工累加各部分尺寸。
                    // 手工累加漏算过一次 extra 的长度（header 里声明了 extraLen、字节却没写），
                    // 整包偏移因此全错，症状是读条目时报 LOC header 签名不符。
                    val offset = counter.count

                    if (ov != null) {
                        val raw = ov.readBytes()
                        val crc = crc32(raw)
                        // 保持原条目的压缩方式：resources.arsc 这类是 STORED，改了也必须是 STORED
                        val payload = if (e.method == METHOD_STORED) raw else deflate(raw)
                        val extra = alignExtra(e.extra, offset, nameBytes.size, e.method)
                        val header = localHeader(
                            e.versionNeeded, 0, e.method, e.time, e.date,
                            crc, payload.size.toLong(), raw.size.toLong(), nameBytes, extra,
                        )
                        out.write(header)
                        out.write(nameBytes)
                        out.write(extra)      // extra 必须真的写出去：header 里声明了它的长度
                        out.write(payload)
                        written += Written.of(e, nameBytes, extra, offset, e.method, crc, payload.size.toLong(), raw.size.toLong())
                        recompressed++
                    } else {
                        val dataStart = localDataOffset(raf, e.localOffset)
                        val extra = alignExtra(e.extra, offset, nameBytes.size, e.method)
                        val header = localHeader(
                            e.versionNeeded, 0, e.method, e.time, e.date,
                            e.crc, e.compressedSize, e.size, nameBytes, extra,
                        )
                        out.write(header)
                        out.write(nameBytes)
                        out.write(extra)      // 同上：漏写 extra 会让所有后续数据整体前移，症状是"内容对、偏移错"
                        raf.seek(dataStart)
                        copyRange(raf, out, e.compressedSize)
                        written += Written.of(e, nameBytes, extra, offset, e.method, e.crc, e.compressedSize, e.size)
                        rawCopied++
                    }
                    onProgress?.invoke(e.name)
                }

                writeCentralDirectory(out, written, cdOffset = counter.count)
                out.flush()
            }
        }
        return Stats(rawCopied, recompressed, deletedCount, fellBackToFull = false)
    }

    // ── 全量兜底 ──────────────────────────────────────────────

    private fun rebuildFull(
        source: File,
        outFile: File,
        overlay: (String) -> File?,
        deleted: Set<String>,
    ): Stats {
        var copied = 0
        var recompressed = 0
        var deletedCount = 0
        ZipFile(source).use { zip ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile), 1 shl 16)).use { zos ->
                for (e in zip.entries()) {
                    if (e.name in deleted) {
                        deletedCount++
                        continue
                    }
                    val ov = overlay(e.name)
                    val bytes = if (ov != null) {
                        recompressed++
                        ov.readBytes()
                    } else {
                        copied++
                        zip.getInputStream(e).use { it.readBytes() }
                    }
                    val ze = ZipEntry(e.name).apply {
                        method = if (e.method == METHOD_STORED) ZipEntry.STORED else ZipEntry.DEFLATED
                        if (e.time > 0) time = e.time
                        if (this.method == ZipEntry.STORED) {
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            crc = crc32(bytes)
                        }
                    }
                    zos.putNextEntry(ze)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        return Stats(copied, recompressed, deletedCount, fellBackToFull = false)
    }

    // ── zip 结构 ──────────────────────────────────────────────

    private data class Entry(
        val name: String,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val time: Int,
        val date: Int,
        val localOffset: Long,
        val extra: ByteArray,
        val comment: ByteArray,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val internalAttrs: Int,
        val externalAttrs: Long,
    )

    private data class Written(
        val nameBytes: ByteArray,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
        val extra: ByteArray,
        val comment: ByteArray,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val internalAttrs: Int,
        val externalAttrs: Long,
    ) {
        companion object {
            fun of(
                e: Entry,
                nameBytes: ByteArray,
                extra: ByteArray,
                localOffset: Long,
                method: Int,
                crc: Long,
                compressedSize: Long,
                size: Long,
            ) = Written(
                nameBytes = nameBytes, method = method, time = e.time, date = e.date,
                crc = crc, compressedSize = compressedSize, size = size, localOffset = localOffset,
                extra = extra, comment = e.comment, versionMadeBy = e.versionMadeBy,
                versionNeeded = e.versionNeeded, internalAttrs = e.internalAttrs,
                externalAttrs = e.externalAttrs,
            )
        }
    }

    private fun readCentralDirectory(raf: RandomAccessFile): List<Entry> {
        val eocdOffset = findEocd(raf)
        raf.seek(eocdOffset)
        val eocd = ByteArray(22)
        raf.readFully(eocd)

        val total = u16(eocd, 10)
        val cdSize = u32(eocd, 12)
        val cdOffset = u32(eocd, 16)
        if (total == MAX_U16 || cdOffset == MAX_U32 || cdSize == MAX_U32) {
            throw UnsupportedZip("zip64 中央目录")
        }

        raf.seek(cdOffset)
        val cd = ByteArray(cdSize.toInt())
        raf.readFully(cd)

        val out = ArrayList<Entry>(total)
        var p = 0
        while (p + 46 <= cd.size) {
            if (u32(cd, p) != CENTRAL_SIG) throw UnsupportedZip("中央目录签名不符 @$p")
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            val nameAt = p + 46
            val extraAt = nameAt + nameLen
            val commentAt = extraAt + extraLen

            out += Entry(
                name = String(cd, nameAt, nameLen, Charsets.UTF_8),
                method = u16(cd, p + 10),
                flags = u16(cd, p + 8),
                crc = u32(cd, p + 16),
                compressedSize = u32(cd, p + 20),
                size = u32(cd, p + 24),
                time = u16(cd, p + 12),
                date = u16(cd, p + 14),
                localOffset = u32(cd, p + 42),
                extra = cd.copyOfRange(extraAt, extraAt + extraLen),
                comment = cd.copyOfRange(commentAt, commentAt + commentLen),
                versionMadeBy = u16(cd, p + 4),
                versionNeeded = u16(cd, p + 6),
                internalAttrs = u16(cd, p + 36),
                externalAttrs = u32(cd, p + 38),
            )
            p = commentAt + commentLen
        }
        return out
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val len = raf.length()
        val back = minOf(len, 22L + 65535L)
        if (back < 22) throw IllegalArgumentException("文件太小，不是 zip")
        val buf = ByteArray(back.toInt())
        raf.seek(len - back)
        raf.readFully(buf)
        for (i in buf.size - 22 downTo 0) {
            if (u32(buf, i) == EOCD_SIG) return len - back + i
        }
        throw IllegalArgumentException("不是有效的 zip：找不到 EOCD")
    }

    private fun localDataOffset(raf: RandomAccessFile, localOffset: Long): Long {
        raf.seek(localOffset)
        val h = ByteArray(30)
        raf.readFully(h)
        if (u32(h, 0) != LOCAL_SIG) throw UnsupportedZip("local header 签名不符 @$localOffset")
        return localOffset + 30 + u16(h, 26) + u16(h, 28)
    }

    /**
     * STORED 条目要对齐到 4 字节（`resources.arsc` 是 Android 11+ 的硬要求）。
     *
     * 填充必须是**合法的 extra 块**，结构为 [id:2][长度:2][数据]，所以最小 4 字节。
     * 而对齐只关心「总偏移 mod 4」，因此填 (4 + pad) 字节与填 pad 字节等效 ——
     * 既合法又能对齐，两全。
     *
     * 为什么不用裸字节填充：实测它会让严格的 zip 解析器读不出整包
     * （ARSCLib 打开新包后资源表直接读不到，应用名退回显示资源 id）。
     * DEFLATED 条目不需要对齐（对齐只对能直接 mmap 的未压缩数据有意义）。
     */
    private fun alignExtra(origExtra: ByteArray, offset: Long, nameLen: Int, method: Int): ByteArray {
        if (method != METHOD_STORED) return origExtra
        val base = offset + 30 + nameLen + origExtra.size
        val pad = ((ALIGNMENT - base % ALIGNMENT) % ALIGNMENT).toInt()
        if (pad == 0) return origExtra

        val block = ByteArray(4 + pad)
        w16(block, 0, PADDING_EXTRA_ID)
        w16(block, 2, pad)          // 数据长度 = pad，于是块总长 = 4 + pad
        return origExtra + block
    }

    private fun localHeader(
        versionNeeded: Int,
        flags: Int,
        method: Int,
        time: Int,
        date: Int,
        crc: Long,
        compressedSize: Long,
        size: Long,
        name: ByteArray,
        extra: ByteArray,
    ): ByteArray {
        val b = ByteArray(30)
        w32(b, 0, LOCAL_SIG)
        w16(b, 4, versionNeeded)
        w16(b, 6, flags)          // 我们总是知道 crc/size，所以不带 data descriptor
        w16(b, 8, method)
        w16(b, 10, time)
        w16(b, 12, date)
        w32(b, 14, crc)
        w32(b, 18, compressedSize)
        w32(b, 22, size)
        w16(b, 26, name.size)
        w16(b, 28, extra.size)
        return b
    }

    /** @param cdOffset central directory 的起始偏移，必须等于写完所有条目数据后的流位置 */
    private fun writeCentralDirectory(out: OutputStream, entries: List<Written>, cdOffset: Long) {
        if (entries.size > MAX_U16) throw UnsupportedZip("条目数 ${entries.size} 超过 65535，需要 zip64")
        if (cdOffset > MAX_U32) throw UnsupportedZip("central directory 偏移超过 4GB")

        var cdSize = 0L
        for (e in entries) {
            val b = ByteArray(46)
            w32(b, 0, CENTRAL_SIG)
            w16(b, 4, e.versionMadeBy)
            w16(b, 6, e.versionNeeded)
            w16(b, 8, 0)                 // flags：同上，不带 data descriptor
            w16(b, 10, e.method)
            w16(b, 12, e.time)
            w16(b, 14, e.date)
            w32(b, 16, e.crc)
            w32(b, 20, e.compressedSize)
            w32(b, 24, e.size)
            w16(b, 28, e.nameBytes.size)
            w16(b, 30, e.extra.size)
            w16(b, 32, e.comment.size)
            w16(b, 34, 0)                // 起始磁盘号
            w16(b, 36, e.internalAttrs)
            w32(b, 38, e.externalAttrs)
            w32(b, 42, e.localOffset)
            out.write(b)
            out.write(e.nameBytes)
            out.write(e.extra)
            out.write(e.comment)
            cdSize += 46 + e.nameBytes.size + e.extra.size + e.comment.size
        }

        val eocd = ByteArray(22)
        w32(eocd, 0, EOCD_SIG)
        w16(eocd, 4, 0)
        w16(eocd, 6, 0)
        w16(eocd, 8, entries.size)
        w16(eocd, 10, entries.size)
        w32(eocd, 12, cdSize)
        w32(eocd, 16, cdOffset)
        w16(eocd, 20, 0)
        out.write(eocd)
    }

    /**
     * 只做一件事：数已经写出了多少字节。条目的 localOffset 与 central directory 的
     * 起始偏移都取自它 —— 位置信息只允许有一个来源，避免"声明了多少"和"实际写了多少"
     * 各算各的（这类不一致会让整包偏移全错，而且很难看出来）。
     */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    // 小端读写
    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    private fun w16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun w32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** zip 用的是裸 deflate（nowrap），没有 zlib 头 */
    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(raw)
        d.finish()
        val buf = ByteArray(1 shl 16)
        val out = ByteArrayOutputStream(raw.size / 2 + 64)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun copyRange(raf: RandomAccessFile, out: OutputStream, length: Long) {
        val buf = ByteArray(1 shl 16)
        var remaining = length
        while (remaining > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) throw IllegalStateException("zip 数据被截断（还差 $remaining 字节）")
            out.write(buf, 0, n)
            remaining -= n
        }
    }
}
