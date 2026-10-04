package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * `patchXml` 对**普通 xml**（`res/` 下的）的现状。
 *
 * 已有的 `XmlBridgeTest` 三个用例全在 `AndroidManifest.xml` 上，而清单是特例
 * （它是 module 自己的对象，改得动）。`docs/08` 第 10.2 节记着普通 xml 不落盘 ——
 * 这条测试就是来确认它现在还成不成立的。
 */
class XmlPatchOrdinaryTest {

    private val sample = System.getenv("SMITHY_TEST_APK")?.let(::File)

    @Test
    fun `改普通 xml 的属性要能落盘`() = runBlocking {
        val apk = sample
        assumeTrue("未提供 SMITHY_TEST_APK，跳过", apk != null && apk.isFile)

        // 这是 docs/08 第 10.2 节记着的那条：ARSCLib 对普通 xml 改内存对象不落盘。
        // 清单是例外（它是 module 自己的对象），所以老测试全在清单上、没暴露这条。
        val target = "res/anim/anim_diagnostic_tooltip_window_enter.xml"
        val out = File("/tmp/smithy-xml-ordinary.apk").apply { delete() }

        ApkProjects.open(apk!!).use { p ->
            val rec = p.patchXml(target, "set/translate", "android:duration", "999")
            println("[bx] 改动记录：${rec.note}")

            val rebuilt = p.rebuild()
            rebuilt.copyTo(out, overwrite = true)
            println("[bx] 产物 ${out.length() / 1024}KB")
        }

        // 关键：问**产物**，不问我们自己的账
        ApkProjects.open(out).use { p2 ->
            val after = p2.readXml(target)
            println("[bx] 产物里的内容：")
            println(after.take(320))
            assertTrue(
                after.contains("\"999\""),
                "改成 999 之后，产物里应该读得到新值 —— 读不到就说明普通 xml 的改动没落盘",
            )
        }
    }

    @Test
    fun `探针：看一个普通 xml 长什么样`() = runBlocking {
        val apk = sample
        assumeTrue("未提供 SMITHY_TEST_APK，跳过", apk != null && apk.isFile)

        ApkProjects.open(apk!!).use { p ->
            // 先看包里有哪些 xml（res/ 下的是普通二进制 xml，不是清单）
            val all = p.list()
            val xmls = all.filter { it.path.endsWith(".xml") }
            println("[bx] 包内 xml 共 ${xmls.size} 个")
            xmls.take(12).forEach { println("[bx]   ${it.path}") }

            val target = xmls.firstOrNull { it.path.startsWith("res/") }
            if (target == null) {
                println("[bx] 没有 res/ 下的 xml —— 样本包可能被裁剪过")
                return@use
            }
            println("[bx] 挑中：${target.path}")
            val text = p.readXml(target.path)
            println("[bx] 内容前 600 字符：")
            println(text.take(600))
        }
    }
}
