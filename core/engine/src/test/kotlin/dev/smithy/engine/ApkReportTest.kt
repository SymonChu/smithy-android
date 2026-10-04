package dev.smithy.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 报告渲染。
 *
 * 纯逻辑，不依赖样本 APK —— 这类测试跑起来是毫秒级，所以可以写得细一点。
 * 值得细的原因：报告是**给人看的产物**，它少了哪一段不会有任何报错，
 * 只会让读的人以为「这包没有权限/没有签名」。
 */
class ApkReportTest {

    private fun meta(
        appLabel: String = "演示应用",
        permissions: List<String> = listOf("android.permission.INTERNET", "com.demo.CUSTOM"),
        components: List<ComponentInfo> = listOf(
            ComponentInfo(ComponentInfo.Kind.ACTIVITY, "com.demo.MainActivity", exported = true, hasIntentFilter = true),
            ComponentInfo(ComponentInfo.Kind.SERVICE, "com.demo.SyncService", exported = false, hasIntentFilter = false),
        ),
        signatures: List<SignatureInfo> = listOf(
            SignatureInfo(2, "CN=Demo", "CN=Demo", "aabb", "ccdd", "eeff", isDebug = false),
        ),
        dexStats: List<DexStat> = listOf(DexStat("classes.dex", 120, 900, 1_500, 4_096_000)),
        packerGuess: PackerGuess? = null,
    ) = ApkMeta(
        sourcePath = "/tmp/demo.apk",
        packageName = "com.demo",
        versionName = "1.0",
        versionCode = 7,
        minSdk = 24,
        targetSdk = 35,
        appLabel = appLabel,
        permissions = permissions,
        components = components,
        signatures = signatures,
        dexStats = dexStats,
        sizeBytes = 3L * 1024 * 1024,
        isSplit = false,
        packerGuess = packerGuess,
    )

    @Test
    fun `五个小节一个都不少`() {
        val md = ApkReport.toMarkdown(meta(), entryCount = 42, sourceName = "demo.apk")

        listOf("## 1. 基本信息", "## 2. 签名", "## 3. 权限", "## 4. 组件", "## 5. DEX 构成").forEach {
            assertTrue(md.contains(it), "报告里缺少「$it」小节")
        }
        assertTrue(md.contains("demo.apk"), "报告要写清来源文件")
        assertTrue(md.contains("com.demo"), "报告要写包名")
    }

    @Test
    fun `没有签名时不装作有`() {
        val md = ApkReport.toMarkdown(meta(signatures = emptyList()), entryCount = 1, sourceName = "x.apk")
        assertTrue(md.contains("未检测到有效签名"), "无签名要明确写出来，而不是留空")
    }

    @Test
    fun `权限按系统与第三方分开列`() {
        val md = ApkReport.toMarkdown(meta(), entryCount = 1, sourceName = "x.apk")
        assertTrue(md.contains("### 系统权限（1）"), "系统权限应有独立小节")
        assertTrue(md.contains("### 其他权限（1）"), "第三方权限应有独立小节")
        // 系统权限去掉冗长的前缀，读起来才是一份清单而不是一堆常量
        assertTrue(md.contains("`INTERNET`"), "系统权限应去掉 android.permission. 前缀")
        assertTrue(md.contains("`com.demo.CUSTOM`"), "第三方权限保留全名")
    }

    @Test
    fun `接近方法上限的 dex 会被标出来`() {
        val near = listOf(DexStat("classes.dex", 60_000, 65_400, 90_000, 8_000_000))
        val md = ApkReport.toMarkdown(meta(dexStats = near), entryCount = 1, sourceName = "x.apk")
        assertTrue(md.contains("⚠"), "接近 65536 上限要标出来")
        assertTrue(md.contains("65536"), "要说清是什么上限")
    }

    @Test
    fun `表格里的竖线被转义，不会把单元格切坏`() {
        // 证书主体里带竖线虽然少见，但一旦出现就会把表格结构破坏掉 —— 导出文件是给人看的，不能糊
        val weird = listOf(SignatureInfo(2, "CN=a|b", "CN=a|b", "m", "s", "h", isDebug = false))
        val md = ApkReport.toMarkdown(meta(signatures = weird), entryCount = 1, sourceName = "x.apk")

        assertTrue(md.contains("CN=a\\|b"), "竖线应被转义")
        // 表格的每一行都应该是「以 | 开头、以 | 结尾」的
        md.lines().filter { it.startsWith("| CN=") }.forEach {
            assertTrue(it.trim().endsWith("|") && it.count { ch -> ch == '|' } == 5, "表格行被切坏了：$it")
        }
    }

    @Test
    fun `大小按量级换单位`() {
        assertEquals("512 B", ApkReport.humanSize(512))
        assertEquals("1.0 KB", ApkReport.humanSize(1024))
        assertEquals("1.5 MB", ApkReport.humanSize(1024L * 1024 * 3 / 2))
        assertEquals("2.00 GB", ApkReport.humanSize(1024L * 1024 * 1024 * 2))
    }
}
