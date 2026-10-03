package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M1 集成测试：dex 搜索与 smali 读取。
 *
 * 样本 APK 通过环境变量传入，没有就跳过（不让 CI 因为缺样本而红）：
 *   SMITHY_TEST_APK=app/build/outputs/apk/debug/app-debug.apk ./gradlew :core:engine:testDebugUnitTest
 *
 * 这里刻意用「本仓库自己的 debug 包」当样本：它是真实的多 dex 包（11 个 dex、
 * 三个贴着 65536 上限），比自己造的小样本更能暴露问题。
 */
class DexSearchTest {

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    private fun requireSample(): File {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)
        return sampleApk!!
    }

    @Test
    fun `按类名搜索能定位到类与它所在的 dex`() = runBlocking {
        val project = ApkProjects.open(requireSample())
        try {
            val hits = project.dexSearch(DexQuery("dev.smithy.MainActivity", DexQuery.Scope.CLASS))
            println("── CLASS 命中 ${hits.size} 条 ──")
            hits.take(5).forEach { println("  ${it.className}  @${it.dexName}") }

            assertTrue(hits.isNotEmpty(), "应能搜到 dev.smithy.MainActivity 本身")
            assertTrue(
                hits.any { it.className == "dev.smithy.MainActivity" },
                "类名应已从描述符 Ldev/smithy/MainActivity; 转成可读形式",
            )
            assertTrue(hits.first().dexName.endsWith(".dex"), "命中应带 dex 出处")
        } finally {
            project.close(keepArtifacts = false)
        }
    }

    @Test
    fun `按字符串搜索必须带出处`() = runBlocking {
        val project = ApkProjects.open(requireSample())
        try {
            val hits = project.dexSearch(DexQuery("工作台", scope = DexQuery.Scope.STRING))
            println("── STRING「工作台」命中 ${hits.size} 条 ──")
            hits.take(5).forEach { println("  ${it.className}#${it.methodSig}  → ${it.snippet}") }

            assertTrue(hits.isNotEmpty(), "应能搜到界面文案「工作台」")
            assertTrue(hits.all { it.snippet.isNotEmpty() }, "snippet 不应为空")
            // 这条是这个工具存在的意义：命中必须知道「谁在用它」，
            // 否则 AI 拿到一个字符串却不知道该去改哪个类。
            assertTrue(
                hits.any { it.methodSig != null },
                "字符串命中必须带方法出处（区分 const-string 与字符串池）",
            )
        } finally {
            project.close(keepArtifacts = false)
        }
    }

    @Test
    fun `按方法名搜索 子串与正则精确两种语义`() = runBlocking {
        val project = ApkProjects.open(requireSample())
        try {
            // 子串语义：搜 onCreate 会连 onCreateView / ensureCompositionCreated 一起命中。
            // 这是刻意设计（与字符串搜索一致），不是 bug。
            val broad = project.dexSearch(DexQuery("onCreate", scope = DexQuery.Scope.METHOD, limit = 30))
            println("── METHOD「onCreate」子串命中 ${broad.size} 条 ──")
            broad.take(5).forEach { println("  ${it.className}#${it.methodSig}") }

            assertTrue(broad.isNotEmpty(), "任何有 Activity 的包都该有 onCreate")
            assertTrue(broad.all { it.methodSig!!.contains("onCreate") })

            // 要精确匹配就用正则，这是子串语义的配套出口
            val exact = project.dexSearch(
                DexQuery("^onCreate$", scope = DexQuery.Scope.METHOD, regex = true, limit = 50),
            )
            println("── METHOD「^onCreate$」正则命中 ${exact.size} 条 ──")
            exact.take(5).forEach { println("  ${it.className}#${it.methodSig}") }

            assertTrue(exact.isNotEmpty(), "精确匹配也应命中")
            assertTrue(
                exact.all { it.methodSig!!.startsWith("onCreate(") },
                "正则精确匹配不该带出 onCreateView 这类的噪声",
            )
            assertTrue(
                exact.size < broad.size,
                "精确匹配的命中数(${exact.size})应少于子串(${broad.size}) —— 否则说明 regex 没起作用",
            )
        } finally {
            project.close(keepArtifacts = false)
        }
    }

    @Test
    fun `读 smali 类与单个方法`() = runBlocking {
        val project = ApkProjects.open(requireSample())
        try {
            val smali = project.readSmali("dev.smithy.MainActivity")
            println("── smali 长度 ${smali.length} 字符，前 3 行 ──")
            smali.lines().take(3).forEach { println("  $it") }

            assertTrue(smali.contains("Ldev/smithy/MainActivity;"), "smali 应含类描述符")
            assertTrue(smali.contains(".method"), "smali 应有方法块")

            // 用刚搜到的方法签名反过来读单个方法，验证两个能力能串起来
            val hit = project.dexSearch(DexQuery("onCreate", scope = DexQuery.Scope.METHOD, limit = 200))
                .firstOrNull { it.className == "dev.smithy.MainActivity" }
            if (hit?.methodSig != null) {
                val method = project.readSmaliMethod(hit.className, hit.methodSig!!)
                println("── 单方法 smali ──")
                method.lines().take(4).forEach { println("  $it") }
                assertTrue(method.contains(".method"), "应返回完整方法块")
                assertTrue(method.contains("onCreate"), "应含目标方法名")
                assertTrue(method.trimEnd().endsWith(".end method"), "应含 .end method 收尾")
            }
        } finally {
            project.close(keepArtifacts = false)
        }
    }

    @Test
    fun `读不存在的类要报错而不是返回空`() = runBlocking {
        val project = ApkProjects.open(requireSample())
        try {
            val err = runCatching { project.readSmali("com.smithy.does.not.Exist") }.exceptionOrNull()
            println("── 不存在的类 → ${err?.javaClass?.simpleName}: ${err?.message}")
            assertTrue(
                err is NoSuchElementException,
                "应抛 NoSuchElementException，实际: $err —— 静默返回空串会让上层以为读成功了",
            )
        } finally {
            project.close(keepArtifacts = false)
        }
    }
}
