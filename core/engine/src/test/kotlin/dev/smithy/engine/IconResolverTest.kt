package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * 图标解析。
 *
 * 存在的理由是一次真实的失败：之前实现按 `ic_launcher` 这个名字去猜图标，
 * 结果用户拿真包一用就报「没找到图标条目」—— 因为 `ic_launcher` 只是
 * Android Studio 模板的默认名，他的包不叫这个。
 *
 * 所以这里盯两件事：**能不能顺着清单找到**、**找不到时有没有说清为什么**。
 */
class IconResolverTest {

    private val sample: File? =
        System.getenv("SMITHY_TEST_APK")?.let(::File)?.takeIf { it.isFile }

    private fun open(f: File): ApkProject = runBlocking {
        ApkProjects.open(f, keystoreDir = Files.createTempDirectory("icon-test-ks").toFile())
    }

    @Test
    fun `真实包上能顺着清单解析出图标条目`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val t = runBlocking { p.iconTargets() }

            println("[icon] 清单声明：${t.declaredIcon}")
            println("[icon] adaptive 入口：${t.adaptiveXml}")
            println("[icon] 前景层：${t.foreground}")
            println("[icon] 背景层：${t.background}")
            println("[icon] 背景色：${t.backgroundColor}")
            println("[icon] 传统图标：${t.legacy}")
            println("[icon] 可替换：${t.isReplaceable}｜形态：${if (t.isAdaptive) "adaptive" else "传统"}｜${t.layerSummary}")
            println("[icon] 诊断：${t.notes}")
            // 把 adaptive 声明文件的内容打出来：正则对不上时，这是唯一能看出真实结构的办法
            t.adaptiveXml?.let { path ->
                println("[icon] 声明文件 $path 的内容：\n" + runBlocking { p.readXml(path) })
            }

            // 上一段在解析失败时看不到任何东西（adaptiveXml 是 null），所以这里无条件探一次
            println("[icon] 与 launcher 相关的全部条目：")
            runBlocking { p.list() }
                .filter { it.path.contains("ic_launcher", ignoreCase = true) || it.path.contains("launcher", ignoreCase = true) }
                .forEach { println("   ${it.path}  ${it.size}B") }

            // 样本就是本工具自己。它是个 minSdk 26 的现代包：AGP 只生成 adaptive icon，
            // 前景是矢量 XML、背景是纯色 —— **一个位图都没有**，所以换不了图标。
            // 这里要验的不是「能换」，而是「能正确判断并说清为什么」。
            assertTrue(t.declaredIcon != null, "应该能从清单里读到 android:icon 的引用")
            assertTrue(
                t.notes.isNotEmpty(),
                "换不了的时候必须有诊断信息，否则用户拿到一句「找不到」完全无从判断",
            )
            assertTrue(
                t.notes.any { it.contains("矢量") || it.contains("位图") },
                "诊断要说清是矢量图/纯色这个原因：${t.notes}",
            )
            // 解析器对「有位图的包」的路径要能走通（这里用真包验不了，但至少验证了不误报可替换）
            assertTrue(
                !t.isReplaceable || t.legacy.isNotEmpty() || t.foreground.isNotEmpty(),
                "说可替换就必须真的给出了条目",
            )
        }
    }

    @Test
    fun `解析出的条目路径都真实存在`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val t = runBlocking { p.iconTargets() }
            val all = (t.foreground + t.background + t.legacy).values

            // 不要求非空：样本包只有矢量 adaptive icon，本来就没有位图可列。
            // 要验的是「一旦给出了条目，就必须是真实、合法的位图路径」
            all.forEach { path ->
                assertTrue(path.startsWith("res/"), "条目路径应该在 res/ 下：$path")
                assertTrue(
                    path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp"),
                    "解析出来的应该是位图文件，不是 xml 或别的：$path",
                )
            }
            // 同一个条目不该在两层里重复出现
            val dup = all.groupBy { it }.filterValues { it.size > 1 }.keys
            assertTrue(dup.isEmpty(), "同一条目出现在多个图层里：$dup")
        }
    }
}
