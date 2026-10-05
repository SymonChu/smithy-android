package dev.smithy.fs

import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TarReader] 的逻辑测试。现场造 tar —— 格式简单到「造」比「找样本」可靠：
 * 每一版 Android / GNU tar 产的档在 owner、magic、pax 头上都有细微差别，
 * 造的时候把这些字段全摆出来，将来真档读不了时能对着测试定位是哪个字段。
 */
class TarReaderTest {

    /** 手写一个 ustar 头（512B），不用任何库 —— 这正是被测代码要解析的东西。 */
    private fun header(name: String, size: Long, typeChar: Char = '0'): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(b, 0, h, off, b.size)
        }
        put(0, name.take(100))
        put(100, "0000644")            // mode（八进制字符串）
        put(108, "0000000")            // uid
        put(116, "0000000")            // gid
        put(124, size.toString(8).padStart(11, '0') + " ")  // size
        put(136, "00000000000 ")       // mtime
        h[156] = typeChar.code.toByte() // '0'=文件 '5'=目录
        put(257, "ustar  \u0000")      // magic（ustar + 空格，GNU 写法）
        // checksum：字段自身算 ' '，先置空再算
        for (i in 148..155) h[i] = ' '.code.toByte()
        var sum = 0L
        for (b in h) sum += b.toLong() and 0xFF
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    private fun tar(entries: List<Pair<String, String>>): File {
        val f = File.createTempFile("smithy-tar", ".tar")
        f.deleteOnExit()
        FileOutputStream(f).use { out ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                out.write(header(name, bytes.size.toLong()))
                out.write(bytes)
                val pad = (512 - bytes.size % 512) % 512
                repeat(pad) { out.write(0) }
            }
            out.write(ByteArray(1024))  // 结束块 ×2
        }
        return f
    }

    @Test
    fun `tar 能列出条目 含目录`() {
        val f = tar(listOf("a.txt" to "hello", "dir/" to "", "dir/b.txt" to "x"))
        val entries = TarReader.list(f)
        assertEquals(3, entries.size)
        assertEquals("a.txt", entries[0].path)
        assertEquals(5L, entries[0].size)
        assertTrue(entries[1].dir, "名字带 / 的该认成目录")
        assertEquals("dir/b.txt", entries[2].path)
    }

    @Test
    fun `tar 能读出条目内容`() {
        val f = tar(listOf("a.txt" to "你好，tar", "b.txt" to "second"))
        assertEquals("你好，tar", String(TarReader.readEntry(f, "a.txt"), Charsets.UTF_8))
        assertEquals("second", String(TarReader.readEntry(f, "b.txt"), Charsets.UTF_8))
    }

    @Test
    fun `读不存在的条目要报错而不是给空`() {
        val f = tar(listOf("a.txt" to "hello"))
        assertFailsWith<IllegalArgumentException> {
            TarReader.readEntry(f, "nope.txt")
        }
    }

    @Test
    fun `tar 包上 gzip 也走同一条路`() {
        // gzip 包一层：压缩后不再是 512 对齐，检验「按流读」而不是「按文件偏移」
        val raw = tar(listOf("a.txt" to "compressed content"))
        val gz = File.createTempFile("smithy-tar", ".tar.gz")
        gz.deleteOnExit()
        raw.inputStream().use { input ->
            GZIPOutputStream(gz.outputStream().buffered()).use { input.copyTo(it) }
        }
        assertEquals("compressed content", String(TarReader.readEntry(gz, "a.txt"), Charsets.UTF_8))
    }

    @Test
    fun `extractAll 解出文件且不覆盖已存在的`() {
        val f = tar(listOf("a.txt" to "one", "d/b.txt" to "two"))
        val dir = File.createTempFile("smithy-ex", "").let { it.delete(); it.mkdirs(); it }
        val out = TarReader.extractAll(f, dir)
        assertEquals(2, out.size)
        assertEquals("one", File(dir, "a.txt").readText())
        assertEquals("two", File(dir, "d/b.txt").readText())
        // 再解一次：全部跳过
        val again = TarReader.extractAll(f, dir)
        assertTrue(again.isEmpty(), "已存在的该跳过：${again.map { it.name }}")
    }

    @Test
    fun `handles 与 unsupported 的判定`() {
        assertTrue(TarReader.handles("x.tar"))
        assertTrue(TarReader.handles("x.TAR.GZ"))
        assertTrue(TarReader.handles("x.tgz"))
        assertTrue(TarReader.isUnsupportedArchive("x.7z"))
        assertTrue(TarReader.isUnsupportedArchive("x.rar"))
    }
}
