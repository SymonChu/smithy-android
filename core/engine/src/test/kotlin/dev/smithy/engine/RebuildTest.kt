package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 集成测试：改 → 重打包 → 产物还能用。
 *
 * 「重打包」最容易出的问题是产出**结构上非法**的包（central directory 偏移算错、
 * resources.arsc 被压了、对齐丢了）—— 这类包在本机看着没问题，装到手机上才发现装不上。
 * 所以断言分三层：
 *   ① 结构：ZipFile 能打开，且每个条目都能完整读出（CRC 由 ZipFile 校验，错一位就抛）
 *   ② 硬性要求：resources.arsc 必须 STORED 且 4 字节对齐（Android 11+ 违反直接拒装）
 *   ③ 语义：用引擎自己重新打开新包，包名/权限/组件/资源表都还得读得出来
 *
 * 注意所有断言都在 `use { }` **内部**完成：产物落在临时工作区里，
 * 关闭工程（close(keepArtifacts=false)）会把它一起清掉 —— 这正是真实用法，
 * 「改 → 打包 → 签名 → 装机」必须是一条会话内走完的链。
 */
class RebuildTest {

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    private fun requireSample(): File {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)
        return sampleApk!!
    }

    /** 取某条目压缩数据的起始偏移（自己解析 zip 结构，不借助引擎代码，避免自证） */
    private fun dataOffsetOf(apk: File, entryName: String): Long {
        RandomAccessFile(apk, "r").use { raf ->
            fun u16(a: ByteArray, o: Int) = (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
            fun u32(a: ByteArray, o: Int) = u16(a, o).toLong() or (u16(a, o + 2).toLong() shl 16)

            val len = raf.length()
            val back = minOf(len, 22L + 65535L).toInt()
            val tail = ByteArray(back)
            raf.seek(len - back)
            raf.readFully(tail)

            var eocd = -1
            for (i in back - 22 downTo 0) if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
            require(eocd >= 0) { "找不到 EOCD" }
            val cdSize = u32(tail, eocd + 12)
            val cdOffset = u32(tail, eocd + 16)

            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)

            var p = 0
            while (p + 46 <= cd.size) {
                val nameLen = u16(cd, p + 28)
                val extraLen = u16(cd, p + 30)
                val commentLen = u16(cd, p + 32)
                if (String(cd, p + 46, nameLen, Charsets.UTF_8) == entryName) {
                    val localOffset = u32(cd, p + 42)
                    val h = ByteArray(30)
                    raf.seek(localOffset)
                    raf.readFully(h)
                    return localOffset + 30 + u16(h, 26) + u16(h, 28)
                }
                p += 46 + nameLen + extraLen + commentLen
            }
            throw NoSuchElementException("包里没有 $entryName")
        }
    }

    @Test
    fun `改字符串后重打包 产物结构 对齐 与语义都要成立`() = runBlocking {
        val sample = requireSample()

        ApkProjects.open(sample).use { project ->
            val hits = project.dexSearch(DexQuery("工作台", scope = DexQuery.Scope.STRING))
            assumeTrue("样本里没有「工作台」，跳过", hits.isNotEmpty())

            val patches = project.replaceString("工作台", "操作台")
            assertEquals(1, patches.size, "应只改动含该字符串的那一个 dex")

            val t0 = System.currentTimeMillis()
            val out = project.rebuild()
            val rebuildMs = System.currentTimeMillis() - t0
            val base = project.meta
            println("── 原包 ${sample.length() / 1024}KB → 新包 ${out.length() / 1024}KB，重打包耗时 ${rebuildMs}ms")

            // 把产物复制到一个固定路径（临时目录随 close 消失），便于外部工具核对
            val dump = File("/tmp/smithy-rebuild-out.apk")
            out.copyTo(dump, overwrite = true)

            // ① 结构：能打开，且每个条目都能完整读出
            var entryCount = 0
            var totalUncompressed = 0L
            ZipFile(out).use { zip ->
                for (e in zip.entries()) {
                    zip.getInputStream(e).use { it.readBytes() }
                    entryCount++
                    totalUncompressed += e.size
                }
                assertTrue(zip.getEntry("AndroidManifest.xml") != null, "manifest 必须还在")
                assertTrue(zip.getEntry("resources.arsc") != null, "资源表必须还在")
            }
            println("── 条目 $entryCount 个，解压总量 ${totalUncompressed / 1024 / 1024}MB")

            // ② 未改动的条目必须与原始包**字节一致**（raw copy 的正确性；
            //    这条比"能读出来"更强，能抓出搬运边界算错的情况）
            ZipFile(sample).use { src ->
                ZipFile(out).use { dst ->
                    val pairs = listOf("resources.arsc", "AndroidManifest.xml")
                    for (name in pairs) {
                        val a = src.getInputStream(src.getEntry(name)).use { it.readBytes() }
                        val b = dst.getInputStream(dst.getEntry(name)).use { it.readBytes() }
                        val declared = dst.getEntry(name).crc
                        val actual = java.util.zip.CRC32().apply { update(b) }.value
                        val firstDiff = a.indices.firstOrNull { a[it] != b[it] } ?: -1
                        println(
                            "── $name: 原 ${a.size}B / 新 ${b.size}B｜一致=${a.contentEquals(b)}" +
                                "｜首个不同@$firstDiff｜声明CRC=$declared 实测CRC=$actual" +
                                "｜新包dataStart=${dataOffsetOf(out, name)} 原包dataStart=${dataOffsetOf(sample, name)}",
                        )
                        assertTrue(a.contentEquals(b), "$name 未改动，必须原样搬运")
                    }
                }
            }

            // ② 硬性要求：resources.arsc 未压缩 + 4 字节对齐
            ZipFile(out).use { zip ->
                val arsc = zip.getEntry("resources.arsc")
                println("── resources.arsc method=${arsc.method}（0=STORED）大小=${arsc.size}")
                assertEquals(0, arsc.method, "resources.arsc 必须未压缩 —— 压了 Android 11+ 直接拒装")
            }
            val arscOffset = dataOffsetOf(out, "resources.arsc")
            println("── resources.arsc 数据偏移=$arscOffset（%4=${arscOffset % 4}）")
            assertEquals(0L, arscOffset % 4, "resources.arsc 必须 4 字节对齐")

            // ③ 语义：引擎重新打开新包，元数据与资源表都还得读得出来
            ApkProjects.open(out).use { reopened ->
                val m = reopened.meta
                println("── 新包：${m.packageName} ${m.versionName} 权限=${m.permissions.size} 组件=${m.components.size} 应用名=${m.appLabel}")
                assertEquals(base.packageName, m.packageName, "包名不能变")
                assertEquals(base.versionName, m.versionName, "版本不能变")
                assertEquals(
                    base.permissions.size, m.permissions.size,
                    "权限数不能变 —— 变了说明 manifest 或资源表被写坏了",
                )
                assertEquals(base.components.size, m.components.size, "组件数不能变")
                assertEquals(base.appLabel, m.appLabel, "应用名走资源表解析，应保持不变")
                assertTrue(m.dexStats.isNotEmpty(), "dex 统计应可读")
            }

            // ④ 改动确实进了新包（否则前三层都通过也没意义）
            ApkProjects.open(out).use { reopened ->
                val stillOld = reopened.dexSearch(DexQuery("工作台", scope = DexQuery.Scope.STRING))
                val nowNew = reopened.dexSearch(DexQuery("操作台", scope = DexQuery.Scope.STRING))
                println("── 新包里：旧值命中 ${stillOld.size} 条，新值命中 ${nowNew.size} 条")
                assertEquals(0, stillOld.size, "旧字符串不该还在新包里")
                assertTrue(nowNew.isNotEmpty(), "新字符串必须在新包里")
            }
        }
    }

    @Test
    fun `没有改动时重打包应当被拒绝`() = runBlocking {
        ApkProjects.open(requireSample()).use { project ->
            val err = runCatching { project.rebuild() }.exceptionOrNull()
            println("── 无改动重打包 → ${err?.javaClass?.simpleName}: ${err?.message}")
            assertTrue(err is IllegalStateException, "应明确拒绝，而不是产出一个和原包一样的包")
        }
    }
}
