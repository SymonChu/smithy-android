package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端自检：改清单 → 重打包 → 签名 → 读回产物。
 *
 * 「真机上装得起来」只有真机能证明，但**产物层面**能验的东西不少，而这些一旦错了
 * 真机必定失败 —— 不该拿真机当第一道防线。
 */
class EndToEndSelfCheckTest {

    private fun sample(): File {
        val p = System.getenv("SMITHY_TEST_APK")
        assumeTrue("没有设置 SMITHY_TEST_APK，跳过", p != null && File(p).isFile)
        return File(p!!)
    }

    @Test
    fun `把 minSdk 降到 21 后 产物同时带 v1 与 v2 v3`() = runBlocking {
        // 产物要在 use 块**内**拷出来：工程一关，它的临时目录就被清掉了
        // （否则拿到的是一个指向已删文件、length() 为 0 的句柄）
        val stable = File("/tmp/smithy-e2e-out.apk").apply { delete() }
        val signedKb = ApkProjects.open(sample()).use { project ->
            project.setManifestField(ManifestField.APP_LABEL, "Smithy 自检")
            project.setManifestField(ManifestField.VERSION_NAME, "9.9.9")
            project.setManifestField(ManifestField.VERSION_CODE, "999")
            // 降到 21：这是「必须有 v1」的分界线，也是老系统的覆盖范围
            project.setManifestField(ManifestField.MIN_SDK, "21")
            project.rebuild()
            val signed = project.sign(SignConfig())
            signed.copyTo(stable, overwrite = true)
            signed.length() / 1024
        }

        println("── 产物 ${signedKb}KB → $stable")

        ZipFile(stable).use { zip ->
            listOf("META-INF/MANIFEST.MF", "META-INF/SMITHY.SF", "META-INF/SMITHY.RSA").forEach {
                assertTrue(zip.getEntry(it) != null, "minSdk 21 的产物必须有 $it")
            }
        }

        ApkProjects.open(stable).use { again ->
            assertEquals(21, again.meta.minSdk, "产物里的 minSdk 应该是 21")
            assertEquals("9.9.9", again.meta.versionName)
            assertEquals("Smithy 自检", again.meta.appLabel)
        }

        val verdict = ApkProjects.open(stable).use { it.verify(stable) }
        println("── 验签 valid=${verdict.valid} 方案=${verdict.schemes}")
        verdict.messages.take(4).forEach { println("     $it") }
        assertTrue(verdict.valid, "自检产物必须验签通过：${verdict.messages.take(3)}")
    }
}