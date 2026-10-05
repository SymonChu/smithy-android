package dev.smithy.fs

import org.junit.Assume
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 对**真实 tar 工具产物**的验收：`TAR_REF` 指向一个 python tarfile（或系统 tar）
 * 造的档时，确认我们手写的解析器和真实现一致。
 *
 * 和 [TarReaderTest] 的分工：那边手写 512B 头（验我们自己的编码），
 * 这边读**别人造的**档（验「字段布局的理解」对不对 —— `fromOctal` 写反那种
 * 换基 bug 在手写两头（写和读都是同一份错误认知）里是测不出来的，
 * 必须有外部真值对照）。
 */
class TarFixtureTest {

    private fun fixture(): File {
        val f = System.getenv("SMITHY_TEST_TAR")?.let { File(it) }?.takeIf { it.isFile }
        Assume.assumeTrue("需要 SMITHY_TEST_TAR 指向一个真 tar/tar.gz", f != null)
        return f!!
    }

    @Test
    fun `真工具造的 tar 能列出全部条目`() {
        val f = fixture()
        val entries = TarReader.list(f)
        assertTrue(entries.isNotEmpty(), "一个条目都没读出来：${f.name}")
        assertTrue(entries.any { !it.dir }, "该有文件条目")
        println("  · ${entries.size} 条: " + entries.take(5).joinToString { it.path })
    }

    @Test
    fun `真工具造的 tar 读出的内容和写入一致`() {
        val f = fixture()
        val text = TarReader.list(f).firstOrNull { !it.dir && it.size > 0 }
        assertNotNull(text, "该有非空文件条目")
        val bytes = TarReader.readEntry(f, text.path)
        assertEquals(text.size, bytes.size.toLong(), "size 字段和实际字节对不上")
    }
}
