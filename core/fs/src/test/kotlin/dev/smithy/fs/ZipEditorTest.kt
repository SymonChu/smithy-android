package dev.smithy.fs

import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * zip 直改。
 *
 * 验的重点**不是「改成功」，而是「没碰的东西没被弄坏」**：这类工具最危险的失败模式
 * 是顺带毁掉别的条目，而那种问题要等到装包失败才暴露。所以每个用例里都有
 * 「其余条目字节一致」这条断言。
 */
class ZipEditorTest {

    private val arscBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)

    /** 造一个含压缩条目、未压缩条目（模拟 resources.arsc）的 zip。 */
    private fun sampleZip(): File {
        val f = File.createTempFile("fs-sample", ".zip")
        ZipOutputStream(f.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("res/values/strings.xml"))
            out.write("<resources><string name=\"a\">A</string></resources>".toByteArray())
            out.closeEntry()

            // resources.arsc 在真实 apk 里必须是 STORED
            out.putNextEntry(
                ZipEntry("resources.arsc").apply {
                    method = ZipEntry.STORED
                    size = arscBytes.size.toLong()
                    compressedSize = arscBytes.size.toLong()
                    crc = CRC32().apply { update(arscBytes) }.value
                },
            )
            out.write(arscBytes)
            out.closeEntry()

            out.putNextEntry(ZipEntry("lib/arm64-v8a/libx.so"))
            out.write(ByteArray(4096) { (it % 251).toByte() })
            out.closeEntry()
        }
        return f
    }

    private fun bytesOf(zip: File, path: String): ByteArray =
        ZipFile(zip).use { z ->
            z.getInputStream(z.getEntry(path)).use { it.readBytes() }
        }

    private fun outFile(): File = File.createTempFile("fs-out", ".zip").also { it.delete() }

    @Test
    fun `列条目 带上路径大小与是否压缩`() {
        val src = sampleZip()
        ZipEditor.open(src).use { e ->
            val entries = e.entries()
            entries.forEach { println("   ${it.path}  ${it.size}B  压缩后 ${it.compressedSize}B  stored=${it.stored}") }

            assertEquals(3, entries.size)
            assertEquals(
                true,
                entries.first { it.path == "resources.arsc" }.stored,
                "资源表必须是 STORED",
            )
            assertEquals(false, entries.first { it.path.endsWith(".so") }.stored)
        }
    }

    @Test
    fun `替换一个条目 其余条目字节一致`() {
        val src = sampleZip()
        val soBefore = bytesOf(src, "lib/arm64-v8a/libx.so")
        val arscBefore = bytesOf(src, "resources.arsc")
        val out = outFile()

        val report = ZipEditor.open(src).use { e ->
            e.put("res/values/strings.xml", "<resources/>".toByteArray())
            e.writeTo(out)
        }
        println("── 搬运=${report.copied} 写入=${report.written} 删除=${report.deleted} → ${report.bytes}B")

        assertEquals("<resources/>", String(bytesOf(out, "res/values/strings.xml")))
        assertTrue(
            soBefore.contentEquals(bytesOf(out, "lib/arm64-v8a/libx.so")),
            "没改的 so 必须字节一致 —— 否则就是顺带把别人弄坏了",
        )
        assertTrue(
            arscBefore.contentEquals(bytesOf(out, "resources.arsc")),
            "没改的资源表必须字节一致",
        )
        assertEquals(2, report.copied, "两个未改动的条目应该原样搬运")
        assertEquals(1, report.written)
    }

    @Test
    fun `删除条目后产物里没有它 其余照旧`() {
        val src = sampleZip()
        val arscBefore = bytesOf(src, "resources.arsc")
        val out = outFile()

        val report = ZipEditor.open(src).use { e ->
            e.delete("lib/arm64-v8a/libx.so")
            e.writeTo(out)
        }

        ZipFile(out).use { z -> assertNull(z.getEntry("lib/arm64-v8a/libx.so"), "删掉的条目不该还在") }
        assertEquals(1, report.deleted)
        assertEquals(2, ZipFile(out).use { it.size() })
        assertTrue(arscBefore.contentEquals(bytesOf(out, "resources.arsc")))
    }

    @Test
    fun `新增条目会出现 且原条目不变`() {
        val src = sampleZip()
        val xmlBefore = bytesOf(src, "res/values/strings.xml")
        val out = outFile()
        val extra = "hello".toByteArray()

        ZipEditor.open(src).use { e ->
            e.put("assets/extra.txt", extra)
            e.writeTo(out)
        }

        assertTrue(extra.contentEquals(bytesOf(out, "assets/extra.txt")), "新增的条目内容要能读回来")
        assertTrue(xmlBefore.contentEquals(bytesOf(out, "res/values/strings.xml")))
        assertEquals(4, ZipFile(out).use { it.size() })
    }

    /**
     * 资源表写出来必须仍是 STORED，且数据**4 字节对齐**。
     *
     * 安装器按内存映射读 `resources.arsc`，这两条任一不满足都会**装不上** ——
     * 而症状是「装包失败」，最难往 zip 层联想，所以专门盯住。
     */
    @Test
    fun `资源表写出来仍是 STORED 且 4 字节对齐`() {
        val src = sampleZip()
        val out = outFile()

        // 故意在 arsc 前面塞一个长度不整的条目，逼出对齐填充
        ZipEditor.open(src).use { e ->
            e.put("assets/odd.txt", ByteArray(3) { 7 })
            e.writeTo(out)
        }

        ZipFile(out).use { z ->
            val entry = z.getEntry("resources.arsc")
            assertNotNull(entry)
            assertEquals(ZipEntry.STORED, entry.method, "资源表必须 STORED")
            assertEquals(arscBytes.size.toLong(), entry.size)
        }

        val offset = localDataOffset(out, "resources.arsc")
        println("── 产物里 resources.arsc 的数据偏移 = $offset（应能被 4 整除）")
        assertNotNull(offset, "应该能在本地头里找到这个条目")
        assertEquals(0L, offset % 4, "资源表数据必须 4 字节对齐，否则装不上")

        // 产物还得是能正常打开的 zip
        assertTrue(arscBytes.contentEquals(bytesOf(out, "resources.arsc")))
    }

    @Test
    fun `不允许覆盖原文件`() {
        val src = sampleZip()
        val e = runCatching {
            ZipEditor.open(src).use { it.put("a.txt", byteArrayOf(1)); it.writeTo(src) }
        }.exceptionOrNull()
        println("── 覆盖原文件 → ${e?.javaClass?.simpleName}: ${e?.message}")
        assertNotNull(e, "必须拦住：写一半失败会把原文件毁掉")
    }

    // ── 解析 zip，算条目数据的偏移 ──
    //
    // 从**中心目录**找，而不是顺序遍历本地头：`ZipOutputStream` 对 DEFLATED 条目会在
    // 数据后面写一个描述符（本地头里的 size 是 0），顺序遍历会因为算不出长度而错位。

    private fun localDataOffset(zip: File, want: String): Long? {
        val b = zip.readBytes()

        var eocd = b.size - 22
        while (eocd >= 0 && !(b[eocd] == 0x50.toByte() && b[eocd + 1] == 0x4B.toByte() &&
                b[eocd + 2] == 0x05.toByte() && b[eocd + 3] == 0x06.toByte())
        ) {
            eocd--
        }
        if (eocd < 0) return null

        val count = leShort(b, eocd + 10)
        var p = leInt(b, eocd + 16)
        repeat(count) {
            if (p + 46 > b.size) return null
            val nameLen = leShort(b, p + 28)
            val extraLen = leShort(b, p + 30)
            val commentLen = leShort(b, p + 32)
            val localOff = leInt(b, p + 42)
            val name = String(b, p + 46, nameLen, Charsets.UTF_8)
            if (name == want) {
                val localNameLen = leShort(b, localOff + 26)
                val localExtraLen = leShort(b, localOff + 28)
                val data = localOff + 30 + localNameLen + localExtraLen
                println("── $name：本地头@$localOff 名长=$localNameLen extra长=$localExtraLen 数据@$data（%4=${data % 4}）")
                if (localExtraLen > 0) {
                    val extraBytes = b.copyOfRange(
                        localOff + 30 + localNameLen,
                        localOff + 30 + localNameLen + localExtraLen,
                    )
                    println("── extra 原始字节：" + extraBytes.joinToString(" ") { "%02x".format(it) })
                }
                return data.toLong()
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    private fun leShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
