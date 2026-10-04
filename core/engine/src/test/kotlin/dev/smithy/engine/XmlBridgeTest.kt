package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 二进制 XML 层（`axml.decode` / `axml.patch`）。
 *
 * 重点不在「属性改对了没有」，而在**改完之后包还能不能装** ——
 * 二进制 XML 是一棵带字符串池的树，序列化错了不会报错，只会让系统读不了清单，
 * 症状是「装机失败」。
 */
class XmlBridgeTest {

    private val sample: File? =
        System.getenv("SMITHY_TEST_APK")?.let(::File)?.takeIf { it.isFile }

    private fun open(f: File): ApkProject = runBlocking {
        ApkProjects.open(f, keystoreDir = Files.createTempDirectory("axml-test-ks").toFile())
    }

    @Test
    fun `解码清单：能看到元素与属性`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val xml = runBlocking { p.readXml("AndroidManifest.xml") }
            println("[axml] 清单解码成 ${xml.lines().size} 行：")
            println(xml.lines().take(10).joinToString("\n"))

            assertTrue(xml.contains("<manifest"), "解码结果应该是 XML 文本")
            assertTrue(xml.contains("package=\"dev.smithy"), "package 属性应该有值")
            assertTrue(xml.contains("<application"), "应能看到 application 节点")
            // 数值型属性曾经因为只用 getValueAsString()（对整数返回 null）而整片显示为空
            assertTrue(
                !xml.contains("versionCode=\"\""),
                "versionCode 不该是空的 —— 空了说明数值型属性没被正确展开。实际输出：\n$xml",
            )
        }
    }

    @Test
    fun `改 application 的属性，重打包后包仍然可解析`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val before = runBlocking { p.readXml("AndroidManifest.xml") }
            // 取反着改：debug 包本来多半就是 true，改成 true 等于没改（那会被实现拒绝）
            val target = if (before.contains("debuggable=\"true\"")) "false" else "true"

            val rec = runBlocking {
                p.patchXml("AndroidManifest.xml", "manifest/application", "android:debuggable", target)
            }
            println("[axml] ${rec.note}｜${rec.beforeHash?.take(8)} → ${rec.afterHash?.take(8)}")

            val out = runBlocking { p.rebuild() }

            // 关键：产出包的清单必须仍可被解析 —— 序列化坏了就是「装机失败」而不是「值不对」
            open(out).use { p2 ->
                val after = runBlocking { p2.readXml("AndroidManifest.xml") }
                assertTrue(after.contains("debuggable=\"$target\""), "新值应出现在产出包里")
                assertTrue(after.contains("<application"), "节点结构不该被改坏")
                println("[axml] 产出包清单可解析，debuggable=$target 已生效")
            }
        }
    }

    @Test
    fun `元素路径写错时明确报错，不当成成功`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val e = assertFailsWith<NoSuchElementException> {
                runBlocking { p.patchXml("AndroidManifest.xml", "manifest/application/没有这个节点", "x", "y") }
            }
            println("[axml] 路径写错 → ${e.message}")
            assertTrue(e.message!!.contains("找不到元素路径"))
            assertTrue(runBlocking { p.patches() }.isEmpty(), "失败的改动不该留下记录")
        }
    }

    @Test
    fun `拿非 XML 的条目当参数会报错，而不是给个空结果`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val e = assertFailsWith<NoSuchElementException> {
                runBlocking { p.readXml("classes.dex") }
            }
            println("[axml] 非 xml → ${e.message}")
            assertTrue(e.message!!.contains("不是二进制 XML"), "要说清为什么读不了")
        }
    }
}
