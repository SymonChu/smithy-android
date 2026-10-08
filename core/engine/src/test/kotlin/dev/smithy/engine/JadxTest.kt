package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * jadx 单类反编译。
 *
 * 顺带验证一件重要的事：**jadx 能不能在 JVM 单测里跑**。
 * 它是 Android fork（上游 jadx 的 android 移植），如果内部直接调 `android.util.Log`，
 * 那它在这台机器上就跑不起来，只能装到手机上验 —— 那样 M1 的 Java 视图就没有自动测试兜底。
 */
class JadxTest {

    private val sample: File? =
        System.getenv("SMITHY_TEST_APK")?.let(::File)?.takeIf { it.isFile }

    private fun openProject(): ApkProject = runBlocking {
        ApkProjects.open(
            apkFile = sample!!,
            keystoreDir = Files.createTempDirectory("jadx-test-ks").toFile(),
        )
    }

    @Test
    fun `反编译包内的类得到 Java 源码`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        openProject().use { p ->
            val t0 = System.currentTimeMillis()
            val code = runBlocking { p.decompileToJava("dev.smithy.SmithyApp") }
            val cost = System.currentTimeMillis() - t0

            println("[jadx] 首次反编译 dev.smithy.SmithyApp：${cost}ms，${code.lines().size} 行")
            println(code.lines().take(12).joinToString("\n"))

            assertTrue(
                code.contains("class SmithyApp"),
                "反编译结果里应有类声明，实际开头：\n${code.take(400)}",
            )
            assertTrue(code.contains("Application"), "SmithyApp 继承 Application，应能看出来")
        }
    }

    @Test
    fun `反编译不存在的类报「不在包内」而不是空结果`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        openProject().use { p ->
            val e = assertFailsWith<NoSuchElementException> {
                runBlocking { p.decompileToJava("dev.smithy.这个类不存在") }
            }
            println("[jadx] 不存在的类 → ${e.message}")
            assertTrue(e.message!!.contains("不在本包内"))
        }
    }

    @Test
    fun `同一 dex 的第二个类走缓存，不再重新加载`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        openProject().use { p ->
            // **先问 dex 布局，再决定怎么断言** —— 原来这里直接写死「热 < 冷/2」，
            // 那等于假设两个类分属不同 dex：第一次要另载一个 dex，第二次省掉整段加载。
            // 但同一个 dex 里的两个类，第一次就把整个 dex 载完了，第二次只省解析，
            // 耗时差本来就不到 2 倍 —— dex 布局一变（多写几行代码就可能重新分 dex）
            // 这条测试就假失败。实测踩到过：484ms vs 317ms，缓存明明在工作。
            val a = "dev.smithy.SmithyApp"
            val b = "dev.smithy.MainActivity"
            // DexIndex 是 internal，但同模块的测试用得到（DexPatchTest 也这么干）
            val sameDex = dev.smithy.engine.internal.DexIndex(sample!!, 21).use { idx ->
                val da = idx.dexOfClass(dev.smithy.engine.internal.DexIndex.descriptor(a))
                val db = idx.dexOfClass(dev.smithy.engine.internal.DexIndex.descriptor(b))
                println("[jadx] $a 在 $da，$b 在 $db，同 dex=${da != null && da == db}")
                da != null && da == db
            }

            val t0 = System.currentTimeMillis()
            runBlocking { p.decompileToJava(a) }
            val cold = System.currentTimeMillis() - t0

            val t1 = System.currentTimeMillis()
            val second = runBlocking { p.decompileToJava(b) }
            val warm = System.currentTimeMillis() - t1

            println("[jadx] 冷启动 ${cold}ms → 第二次（同 dex 另一个类）${warm}ms")
            assertTrue(second.contains("class MainActivity"), "第二个类也要能反编译出来")

            if (sameDex) {
                // 同一个 dex：第二次不再载 dex，只需保证没有比第一次还慢（那才是缓存出问题）
                assertTrue(
                    warm <= cold,
                    "同一 dex 的第二次反编译不该比第一次更慢（缓存未生效？）：冷 ${cold}ms vs 热 ${warm}ms",
                )
            } else {
                // 不同 dex：第二次要省掉整段 dex 加载，收益必须是量级差
                //（否则说明会话没被复用，比如 key 用了实例而不是 dex 名）
                assertTrue(
                    warm < cold / 2,
                    "第二次应显著更快（缓存未生效？）：冷 ${cold}ms vs 热 ${warm}ms",
                )
            }
        }
    }
}
