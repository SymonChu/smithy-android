package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 换图标：**清单里根本没有 `android:icon`** 的包。
 *
 * 这类包的图标可能由主题指定（`android:icon` 写在 style 里），也可能压根没设。
 * 之前这里被当成死路直接报「换不了」—— 其实可以：新建一个图标资源，
 * 再把 `android:icon` 加到 `<application>` 上（清单是改得动的，见 `docs/08` 第十节）。
 */
class IconNoDeclarationTest {

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    private fun requireSample(): File {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)
        return sampleApk!!
    }

    private fun open(f: File): ApkProject = runBlocking {
        ApkProjects.open(f, keystoreDir = Files.createTempDirectory("icon-nodecl-ks").toFile())
    }

    private val tinyPng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
        0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    @Test
    fun `清单没声明图标时 也能新建一个挂上去`() {
        open(requireSample()).use { p ->
            // 先把图标声明清掉，模拟「图标由主题指定 / 压根没设图标」的包
            // 接口层返回的是 PatchRecord（不是 Boolean）—— 这里只关心它没抛异常
            val cleared = runBlocking { p.setManifestField(ManifestField.ICON, "") }
            println("── 清掉图标声明 → ${cleared.target}（${cleared.note}）")

            val targets = runBlocking { p.iconTargets() }
            println("── 清掉后的解析：声明=${targets.declaredIcon}")
            targets.notes.forEach { println("   说明：$it") }
            assertNull(targets.declaredIcon, "这时清单里不该还有图标声明")

            val plan = runBlocking { p.planIconReplace() }
            println("── 计划：${plan.mode}｜要画 ${plan.renders.size} 张")
            plan.notes.forEach { println("   说明：$it") }
            assertEquals(
                IconPlan.Mode.NEW_RESOURCES, plan.mode,
                "没有声明图标时也该能新建一个，而不是报「换不了」",
            )
            assertTrue(
                plan.notes.first().contains("没有声明"),
                "说明里应该讲清「清单没声明图标」，实际：${plan.notes.first()}",
            )

            val records = runBlocking {
                p.applyIconReplace(plan.renders.associate { it.key to tinyPng })
            }
            println("── 产生 ${records.size} 条改动")
            records.forEach { println("   ${it.target}｜${it.note}") }
            assertTrue(
                records.any { it.target == "AndroidManifest.xml" },
                "清单应该被改（加上了 android:icon）",
            )

            val out = runBlocking { p.rebuild() }
            open(out).use { p2 ->
                val iconLine = runBlocking { p2.readXml("AndroidManifest.xml") }
                    .lines().firstOrNull { it.contains("android:icon") }
                println("── 产物清单里的图标：${iconLine?.trim()}")
                assertNotNull(iconLine, "产物里 <application> 应该被加上了 android:icon")

                val found = runBlocking { p2.resources("mipmap", "smithy_icon") }
                println("── 产物里查到资源：${found.map { "${it.resName}=${it.value}" }}")
                assertTrue(found.isNotEmpty(), "新建的图标资源应该在产物里")

                assertTrue(p2.meta.packageName.isNotBlank(), "产物包应该仍能被正常解析")
            }
        }
    }

    @Test
    fun `按名字设图标 名字不存在时要报错而不是静默写 0`() {
        open(requireSample()).use { p ->
            val e = runCatching {
                runBlocking { p.setManifestField(ManifestField.ICON, "@mipmap/这个资源不存在") }
            }.exceptionOrNull()
            println("── 设一个不存在的图标资源 → ${e?.javaClass?.simpleName}: ${e?.message}")
            assertNotNull(e, "资源不存在时必须报错 —— 静默写 0 会让图标变成默认的，很难查")
        }
    }
}
