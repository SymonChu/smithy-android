package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 重打包后的 **zip 对齐**。
 *
 * 这条在真机上出过问题：改名改版本之后装机报
 * `INSTALL_FAILED_INVALID_APK: Failed to extract native libraries, res=-2`。
 *
 * 链条是：改名会改 `resources.arsc` 的大小 → 后面所有条目的偏移都跟着变 →
 * lib 目录下的 so 不再**页对齐**。而 `extractNativeLibs=false`（现代包的默认）下
 * 安装器直接 mmap 这些 so，不对齐就拒绝安装 —— **而这个报错完全看不出是 zip 层的问题**，
 * 所以必须用测试守住，不能靠事后排查。
 */
class ZipAlignTest {

    /** 未压缩 so 的页对齐：安装器要 mmap 它。 */
    private val PAGE = 16384L

    /** 未压缩资源的对齐（4 字节，Android 11+ 的硬要求）。 */
    private val DATA = 4L

    private val samplePath = System.getenv("SMITHY_TEST_APK")

    private fun requireSample(): File {
        val f = samplePath?.let { File(it) }?.takeIf { it.isFile }
        assumeTrue("没有样本 APK（设 SMITHY_TEST_APK），跳过", f != null)
        return f!!
    }

    /** 数据偏移：从中心目录拿本地头位置，再加上头长度（30 + 名长 + extra 长）。 */
    private fun dataOffset(raw: ByteArray, localOffset: Int): Long {
        val nameLen = u16(raw, localOffset + 26)
        val extraLen = u16(raw, localOffset + 28)
        return (localOffset + 30 + nameLen + extraLen).toLong()
    }

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `改名后 so 仍页对齐 未压缩资源仍 4 字节对齐`() {
        val source = requireSample()
        val raw = source.readBytes()

        val soNames = zipNames(raw).filter { it.first.startsWith("lib/") && it.first.endsWith(".so") }
        assumeTrue("样本包里没有 .so，跳过", soNames.isNotEmpty())

        soNames.forEach { (name, localOffset) ->
            val off = dataOffset(raw, localOffset)
            println("── 样本  $name 数据@$off  %16384=${off % PAGE}")
            assertEquals(0L, off % PAGE, "样本自身的 so 就该是页对齐的（否则测试前提不成立）")
        }

        // 改一个会牵动 resources.arsc 大小的字段 —— 正是这一步让后面所有偏移都变
        val out = File.createTempFile("align-out", ".apk")
        runBlocking {
            ApkProjects.open(source).use { project ->
                project.setManifestField(ManifestField.VERSION_NAME, "9.9.9-align")
                val rebuilt = project.rebuild()
                assertTrue(rebuilt.length() > 0, "重打包产物为空（workDir 用错会得到 0 字节文件）")
                out.delete()
                rebuilt.copyTo(out, overwrite = true)
            }
        }

        val outRaw = out.readBytes()
        val problems = mutableListOf<String>()

        zipNames(outRaw).forEach { (name, localOffset) ->
            val off = dataOffset(outRaw, localOffset)
            val method = methodOf(outRaw, localOffset)
            when {
                name.startsWith("lib/") && name.endsWith(".so") -> {
                    println("── 产物  $name 数据@$off method=$method %16384=${off % PAGE}")
                    if (method != ZipEntry.STORED) {
                        problems += "$name 变成压缩了（安装器要 mmap 它，必须 STORED）"
                    }
                    if (off % PAGE != 0L) {
                        problems += "$name 未页对齐（%16384=$off），装机时报 Failed to extract native libraries"
                    }
                }

                name == "resources.arsc" -> {
                    println("── 产物  resources.arsc 数据@$off method=$method %4=${off % DATA}")
                    assertEquals(ZipEntry.STORED, method, "资源表必须未压缩")
                    if (off % DATA != 0L) problems += "resources.arsc 未 4 字节对齐"
                }
            }
        }

        assertTrue(problems.isEmpty(), "对齐被破坏了：\n  " + problems.joinToString("\n  "))
    }

    /** 遍历本地头，返回 (条目名, 本地头偏移)。顺序写出的 zip 可以这么走 —— 本地头是连续的。 */
    private fun zipNames(raw: ByteArray): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var i = 0
        while (i + 30 <= raw.size) {
            val sig = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8) or
                ((raw[i + 2].toInt() and 0xFF) shl 16) or ((raw[i + 3].toInt() and 0xFF) shl 24)
            if (sig != 0x04034b50) break
            val method = u16(raw, i + 8)
            val compSize = u32(raw, i + 18)
            val nameLen = u16(raw, i + 26)
            val extraLen = u16(raw, i + 28)
            val name = String(raw, i + 30, nameLen, Charsets.UTF_8)
            out += name to i
            // 尺寸为 0 的 DEFLATED 条目用的是 data descriptor，本地头里读不出长度 ——
            // 这时靠「中心目录」才可靠，但我们的产物是同一次顺序写出的，且 STORED 条目
            // （含所有 so 与 arsc）尺寸都是准的，所以遇到就停在这里足够用
            if (compSize == 0L && method != ZipEntry.STORED) break
            i = (i + 30 + nameLen + extraLen + compSize).toInt()
        }
        return out
    }

    private fun methodOf(raw: ByteArray, localOffset: Int): Int = u16(raw, localOffset + 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
