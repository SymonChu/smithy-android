package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「改文案」的端到端：改一条字符串资源 → 重打包 → 签名 → 读回产物。
 *
 * 这是改包工具最核心的用法，也是 M1 验收的原始场景（「搜到开屏文案 → 改掉 →
 * 重打包 → 装回手机 → 看到新文案」）。单元层面早就测过，但**从改到产出**这条线
 * 之前没串起来验过。
 */
class LabelChangeEndToEndTest {

    private fun sample(): File {
        val p = System.getenv("SMITHY_TEST_APK")
        assumeTrue("没有设置 SMITHY_TEST_APK，跳过", p != null && File(p).isFile)
        return File(p!!)
    }

    @Test
    fun `改应用名之后 产物里读回的是新文案`() = runBlocking {
        val work = File("/tmp/smithy-e2e-labels").apply { mkdirs() }
        val stable = File("/tmp/smithy-labels-out.apk").apply { delete() }

        ApkProjects.open(sample()).use { project ->
            val before = project.meta.appLabel
            // 名字要写成 @string/xxx 这种形式（错误信息里也是这么提示的）
            val rec = project.setResource("@string/app_name", "Smithy 改文案")
            println("── 改前「$before」→ 记录 ${rec.target}｜${rec.note}")

            project.setManifestField(ManifestField.MIN_SDK, "21")
            project.rebuild()
            val signed = project.sign(SignConfig())
            signed.copyTo(stable, overwrite = true)
        }

        ApkProjects.open(stable).use { again ->
            val after = again.meta.appLabel
            println("── 产物里的应用名：「$after」")
            assertEquals("Smithy 改文案", after, "改过的文案应该出现在产物里")
        }

        val v = ApkProjects.open(stable).use { it.verify(stable) }
        println("── 验签 valid=${v.valid} 方案=${v.schemes}")
        assertTrue(v.schemes.contains(1) && v.schemes.contains(2), "产物应带 v1 + v2 签名")
    }
}
