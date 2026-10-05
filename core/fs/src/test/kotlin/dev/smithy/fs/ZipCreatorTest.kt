package dev.smithy.fs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipCreatorTest {

    private fun tempDir(): File =
        File.createTempFile("smithy-zc", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `打包文件与目录 条目用相对路径`() {
        val dir = tempDir()
        File(dir, "a.txt").writeText("one")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.txt").writeText("two")

        val target = File(dir, "out.zip")
        val report = ZipCreator.create(listOf(File(dir, "a.txt"), File(dir, "sub")), target)

        assertEquals(2, report.fileCount)
        assertEquals(1, report.dirCount)

        ZipEditor.open(target).use { z ->
            val names = z.entries().map { it.path }.toSet()
            // 公共父目录被剥掉：zip 根直接是 a.txt 与 sub/
            assertTrue("a.txt" in names, "实际条目：$names")
            assertTrue("sub/" in names, "实际条目：$names")
            assertTrue("sub/b.txt" in names, "实际条目：$names")
        }
    }

    @Test
    fun `目标已存在时拒绝 而不是覆盖`() {
        val dir = tempDir()
        File(dir, "a.txt").writeText("x")
        val target = File(dir, "out.zip").apply { writeText("existing") }
        assertFailsWith<IllegalArgumentException> {
            ZipCreator.create(listOf(File(dir, "a.txt")), target)
        }
        assertEquals("existing", target.readText(), "已存在的文件不该被碰")
    }

    @Test
    fun `不同目录同名文件 撞名加序号不覆盖`() {
        val dir = tempDir()
        File(dir, "d1").mkdirs()
        File(dir, "d2").mkdirs()
        File(dir, "d1/same.txt").writeText("first")
        File(dir, "d2/same.txt").writeText("second")

        val target = File(dir, "out.zip")
        ZipCreator.create(listOf(File(dir, "d1"), File(dir, "d2")), target)

        ZipEditor.open(target).use { z ->
            val names = z.entries().map { it.path }.toSet()
            assertTrue("d1/same.txt" in names && "d2/same.txt" in names, "实际条目：$names")
        }
    }

    @Test
    fun `空列表拒绝`() {
        val dir = tempDir()
        assertFailsWith<IllegalArgumentException> {
            ZipCreator.create(emptyList(), File(dir, "x.zip"))
        }
    }
}
