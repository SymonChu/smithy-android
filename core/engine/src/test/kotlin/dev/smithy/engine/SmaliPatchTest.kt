package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * smali 文本改写 + 整 dex 回编。
 *
 * 这是 M1 里最重的一条路径（要跑完一个 dex 的反汇编与汇编往返），
 * 所以测试同时盯住三件事：**改对了**（新值进包）、**改错了会报错**（不当成成功）、
 * **失败得快**（模式没命中时不该付整 dex 往返的代价）。
 */
class SmaliPatchTest {

    private val sample: File? =
        System.getenv("SMITHY_TEST_APK")?.let(::File)?.takeIf { it.isFile }

    /** 一个确定含字符串常量的类：搜到它命中了这个常量，它的 smali 里就必然有 const-string */
    private val anchorText = "工作台"

    private fun open(f: File): ApkProject = runBlocking {
        ApkProjects.open(f, keystoreDir = Files.createTempDirectory("smali-test-ks").toFile())
    }

    @Test
    fun `改一行 smali 后重打包 —— 新值进包`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            // 用「搜索命中」来定位目标类，而不是写死类名：
            // 命中字符串常量的类，其 smali 里必然有 const-string 指令
            val cls = runBlocking { p.dexSearch(DexQuery(anchorText, DexQuery.Scope.STRING)) }
                .first().className
            val line = runBlocking { p.readSmali(cls) }
                .lines().map { it.trim() }
                .first { it.startsWith("const-string") }

            // 只换字符串字面量，指令形状与寄存器不变 —— 汇编必然通过，
            // 测的是「往返通路」而不是「我手写的 smali 语法对不对」
            val newLine = line.substringBefore(",") + ", \"SMITHY_SMALI_TEST\""
            println("[smali] 目标类 $cls\n  - $line\n  + $newLine")

            val t0 = System.currentTimeMillis()
            val rec = runBlocking { p.patchSmali(cls, null, line, newLine) }
            println("[smali] 整 dex 往返：${System.currentTimeMillis() - t0}ms｜${rec.note}")
            assertEquals(PatchRecord.PatchKind.SMALI, rec.kind)
            assertTrue(rec.beforeHash != rec.afterHash, "改动前后的哈希应当不同")

            val out = runBlocking { p.rebuild() }
            println("[smali] 重打包 → ${out.name} ${out.length() / 1024}KB")

            // 在新包上验：新字符串在，且整包仍可解析
            open(out).use { p2 ->
                val hits = runBlocking {
                    p2.dexSearch(DexQuery("SMITHY_SMALI_TEST", DexQuery.Scope.STRING))
                }
                assertTrue(hits.isNotEmpty(), "新字符串应出现在产出包里")
                assertEquals(setOf(cls), hits.map { it.className }.toSet(), "改动应落在目标类里")
                println("[smali] 新值命中 ${hits.size} 处：${hits.first().dexName}")
            }
        }
    }

    @Test
    fun `模式没命中时明确报错，不当成成功`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val cls = runBlocking { p.dexSearch(DexQuery(anchorText, DexQuery.Scope.STRING)) }
                .first().className

            val t0 = System.currentTimeMillis()
            val e = assertFailsWith<NoSuchElementException> {
                runBlocking { p.patchSmali(cls, null, "这条指令根本不存在_zzz", "x") }
            }
            val cost = System.currentTimeMillis() - t0
            println("[smali] 未命中 → ${cost}ms｜${e.message}")

            assertTrue(e.message!!.contains("没找到要替换的内容"))
            // 预检必须拦住：走完整个 dex 往返的话这里会是秒级
            assertTrue(cost < 5_000, "未命中应在预检阶段就返回，实测 ${cost}ms")
            assertEquals(0, runBlocking { p.patches() }.size, "失败的改动不能留下记录")
        }
    }

    @Test
    fun `方法名给错时，报的是「找不到方法」而不是「没命中」`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        open(sample!!).use { p ->
            val cls = runBlocking { p.dexSearch(DexQuery(anchorText, DexQuery.Scope.STRING)) }
                .first().className

            val e = assertFailsWith<NoSuchElementException> {
                runBlocking { p.patchSmali(cls, "这个方法不存在", "const-string", "x") }
            }
            println("[smali] 方法不存在 → ${e.message}")
            assertTrue(e.message!!.contains("找不到方法"), "要区分「方法不在」与「模式没命中」")
        }
    }
}
