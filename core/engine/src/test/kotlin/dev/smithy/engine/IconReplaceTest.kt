package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 换图标：只有矢量图 / 纯色的包也能换。
 *
 * 这是最麻烦的一类包 —— 现代工程在 `minSdk 26+` 时只生成 adaptive icon，
 * 前景是矢量 XML、背景是纯色，**一个位图都没有**。「往包里塞一张 PNG」是没用的：
 * 资源表里没有对应条目，系统找不到那张图，图标会变成默认的。
 *
 * 所以验证重点不是「改了东西」，而是**改完的包还是自洽的**：
 * 新资源能被资源表按名字解析出来、adaptive 声明指向了它、包整体还能正常打开。
 */
class IconReplaceTest {

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    private fun requireSample(): File {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)
        return sampleApk!!
    }

    private fun open(f: File): ApkProject = runBlocking {
        ApkProjects.open(f, keystoreDir = Files.createTempDirectory("icon-test-ks").toFile())
    }

    /** 1×1 的合法 PNG。这里不解码它，只是要一段真 PNG 字节写进包里。 */
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
    fun `只有矢量图标的包会走新建资源这条路`() {
        open(requireSample()).use { p ->
            val plan = runBlocking { p.planIconReplace() }

            println("── 模式：${plan.mode}｜要画 ${plan.renders.size} 张图｜声明 ${plan.declaredIcon}")
            plan.renders.forEach {
                println("   ${it.key} → ${it.entryPath}  画布=${it.canvasSize} 内容=${it.contentSize}")
            }
            plan.notes.forEach { println("   说明：$it") }

            assertEquals(
                IconPlan.Mode.NEW_RESOURCES, plan.mode,
                "样本包只有矢量图标，应该走「新建资源」。计划：${plan.notes}",
            )
            assertEquals(5, plan.renders.size, "5 个密度各一张传统图标")
            assertTrue(
                plan.renders.all { it.canvasSize == it.contentSize },
                "传统图标铺满即可，不需要安全区",
            )
        }
    }

    @Test
    fun `新建资源后 产物包里能按名字查到它 且清单指向它`() {
        open(requireSample()).use { p ->
            val iconBefore = runBlocking { p.readXml("AndroidManifest.xml") }
                .lines().first { it.contains("android:icon") }
            println("── 改前清单图标：${iconBefore.trim()}")

            val plan = runBlocking { p.planIconReplace() }
            val records = runBlocking {
                p.applyIconReplace(plan.renders.associate { it.key to tinyPng })
            }
            println("── 产生 ${records.size} 条改动：")
            records.forEach { println("   ${it.target}｜${it.note}") }

            assertTrue(records.any { it.target == IconPlan.ARSC_ENTRY }, "资源表应该被改（新建了资源）")
            assertTrue(
                records.any { it.target == "AndroidManifest.xml" },
                "清单应该被改（图标指向新资源）",
            )
            assertEquals(5, records.count { it.target.endsWith(".png") }, "5 张图都要写进去")

            val out = runBlocking { p.rebuild() }

            // 关键：重新打开产物 —— 不是看我们自己的账，而是问资源表它有没有那个资源
            open(out).use { p2 ->
                val found = runBlocking { p2.resources("mipmap", "smithy_icon") }
                println("── 产物里按名字查到 ${found.size} 条资源：")
                found.forEach { println("   ${it.resName} = ${it.value}") }
                assertTrue(found.isNotEmpty(), "产物包里应该能按名字查到新建的图标资源")

                val iconAfter = runBlocking { p2.readXml("AndroidManifest.xml") }
                    .lines().first { it.contains("android:icon") }
                println("── 改后清单图标：${iconAfter.trim()}")
                assertTrue(
                    iconAfter != iconBefore,
                    "清单的图标引用必须变了（旧：$iconBefore）",
                )

                assertTrue(p2.meta.packageName.isNotBlank(), "产物包应该仍能被正常解析")
                println("── 产物包名：${p2.meta.packageName}｜条目数 ${runBlocking { p2.list().size }}")
            }
        }
    }

    @Test
    fun `没先规划就应用会报错 而不是静默什么都不做`() {
        open(requireSample()).use { p ->
            val e = runCatching {
                runBlocking { p.applyIconReplace(mapOf("fg_xxxhdpi" to tinyPng)) }
            }.exceptionOrNull()
            println("── 未规划就应用 → ${e?.javaClass?.simpleName}: ${e?.message}")
            assertTrue(e is IllegalStateException, "必须先规划再应用")
        }
    }
}
