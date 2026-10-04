package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 清单字段的改写（改名 / 版本 / 支持的系统版本）。
 *
 * 这些是最高频的改动，而它们坏掉的代价是「包在装机时才炸」。所以除了「改得动」，
 * 还要盯住「非法值不会被写进去」—— 那是同一类伤害的另一半。
 */
class ManifestEditTest {

    private val samplePath = System.getenv("SMITHY_TEST_APK")

    private fun requireSample(): File {
        val p = samplePath
        assumeTrue("没有设置 SMITHY_TEST_APK，跳过", p != null && File(p).isFile)
        return File(p!!)
    }

    @Test
    fun `改名改版本改支持范围 都能写进去并读回`() = runBlocking {
        ApkProjects.open(requireSample()).use { project ->
            val before = project.meta
            println(
                "── 改前：${before.appLabel}｜${before.versionName}(${before.versionCode})" +
                    "｜min=${before.minSdk} target=${before.targetSdk}",
            )

            project.setManifestField(ManifestField.APP_LABEL, "Smithy 改名测试")
            project.setManifestField(ManifestField.VERSION_NAME, "9.9.9")
            project.setManifestField(ManifestField.VERSION_CODE, "999")
            // 提到 24 会顺带影响签名方案（不再需要 v1），所以这条尤其要能在产物里读回
            project.setManifestField(ManifestField.MIN_SDK, "24")
            project.setManifestField(ManifestField.TARGET_SDK, "34")

            val after = project.meta
            println(
                "── 改后：${after.appLabel}｜${after.versionName}(${after.versionCode})" +
                    "｜min=${after.minSdk} target=${after.targetSdk}",
            )
            assertEquals("Smithy 改名测试", after.appLabel)
            assertEquals("9.9.9", after.versionName)
            assertEquals(999L, after.versionCode)
            assertEquals(24, after.minSdk)
            assertEquals(34, after.targetSdk)

            val out = project.rebuild()
            assertTrue(out.length() > 0, "重打包产物不应为空")

            ApkProjects.open(out).use { again ->
                val read = again.meta
                println(
                    "── 产物里读回：${read.appLabel}｜${read.versionName}(${read.versionCode})" +
                        "｜min=${read.minSdk} target=${read.targetSdk}",
                )
                assertEquals("Smithy 改名测试", read.appLabel, "产物里的应用名应改过来")
                assertEquals(24, read.minSdk, "产物里的 minSdk 应改过来")
                assertEquals(34, read.targetSdk, "产物里的 targetSdk 应改过来")
            }
        }
    }

    @Test
    fun `API 级别的非法值会被拒绝 而不是写坏清单`() = runBlocking {
        ApkProjects.open(requireSample()).use { project ->
            val before = project.meta.minSdk
            println("── 原来的 minSdk=$before")

            // 这些值的共同点是「看不出来的坏」：写进去不会当场报错，
            // 而是等到装机或运行时才以别的方式炸（比如 0、负数、天文数字）
            listOf("abc", "", "  ", "-1", "0", "999").forEach { bad ->
                val refused = runCatching {
                    project.setManifestField(ManifestField.MIN_SDK, bad)
                }.isFailure
                println("   minSdk = '$bad' → ${if (refused) "已拒绝" else "接受了（不对）"}")
                assertTrue(refused, "非法值「$bad」应被拒绝")
            }

            assertEquals(before, project.meta.minSdk, "被拒绝的值不该改动清单")
        }
    }
}
