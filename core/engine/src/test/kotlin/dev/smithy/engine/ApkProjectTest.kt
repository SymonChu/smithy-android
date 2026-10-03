package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 集成测试：拿一个真实 APK 跑通「打开 → 解析」。
 *
 * 样本 APK 通过系统属性或环境变量传入，没有就跳过（不让 CI 因为缺样本而红）：
 *   ./gradlew :app:assembleDebug
 *   ./gradlew :core:engine:testDebugUnitTest \
 *       -Dsmithy.testApk=app/build/outputs/apk/debug/app-debug.apk
 */
class ApkProjectTest {

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    private fun requireSample(): File {
        assumeTrue("未提供样本 APK（-Dsmithy.testApk=<path>），跳过", sampleApk != null)
        return sampleApk!!
    }

    @Test
    fun `打开 APK 并解析出包名 权限 组件 dex 统计 与签名`() = runBlocking {
        val apk = requireSample()
        val project = ApkProjects.open(apk)
        try {
            val m = project.meta
            println("── 解析结果 ──────────────────────────")
            println("包名      : ${m.packageName}")
            println("版本      : ${m.versionName} (${m.versionCode})")
            println("SDK       : min=${m.minSdk} target=${m.targetSdk}")
            println("应用名    : ${m.appLabel}")
            println("权限      : ${m.permissions.size} 个")
            println("组件      : ${m.components.size} 个（activity=${m.components.count { it.kind == ComponentInfo.Kind.ACTIVITY }}）")
            println("大小      : ${"%.1f".format(m.sizeBytes / 1024.0 / 1024.0)} MB")
            m.dexStats.forEach {
                println("  dex ${it.name}: 类=${it.classes} 方法=${it.methods} 字符串=${it.strings} ${"%.1f".format(it.sizeBytes / 1024.0 / 1024.0)}MB")
            }
            m.signatures.forEach {
                println("  签名 v${it.scheme} subject=${it.subject} debug=${it.isDebug} sha256=${it.sha256.take(16)}…")
            }
            println("─────────────────────────────────────")

            assertTrue(m.packageName.isNotBlank(), "包名不应为空")
            assertTrue(m.dexStats.isNotEmpty(), "至少应解析出一个 dex")
            assertTrue(
                m.dexStats.any { it.classes > 0 },
                "dex 类数应 > 0 —— 为 0 说明 DEX 头解析没生效",
            )
            assertTrue(m.sizeBytes > 0)
        } finally {
            project.close(keepArtifacts = false)
        }
    }

    @Test
    fun `列条目并能读取条目内容`() = runBlocking {
        val apk = requireSample()
        val project = ApkProjects.open(apk)
        try {
            val entries = project.list()
            assertTrue(entries.isNotEmpty(), "条目列表不应为空")
            assertTrue(
                entries.any { it.path == "AndroidManifest.xml" },
                "APK 里必须有 AndroidManifest.xml",
            )
            assertTrue(entries.any { it.path.endsWith(".dex") }, "应能列出 dex")

            // 读一个真实条目的头几个字节
            project.readEntry("AndroidManifest.xml").use { ins ->
                val head = ByteArray(4)
                val n = ins.read(head)
                assertTrue(n > 0, "应能读出内容")
            }
        } finally {
            project.close(keepArtifacts = false)
        }
    }
}
