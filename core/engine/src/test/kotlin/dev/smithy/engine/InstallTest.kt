package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 装机链路的**降级逻辑**。
 *
 * 真正的装机动作要在设备上才有意义（Shizuku 服务、Root 授权、系统安装器界面），
 * 在 JVM 上测不了。但降级顺序是纯逻辑 —— 而它恰恰是最容易出错、
 * 也最容易骗人的部分（悄悄降级到手动安装却报告"已静默安装"）。
 * 所以这里用假通道把降级链路钉死。
 */
class InstallTest {

    private val sample: File? =
        System.getenv("SMITHY_TEST_APK")?.let(::File)?.takeIf { it.isFile }

    /** 一个可编排的假通道：记录被调用的档位，按脚本返回成功或失败。 */
    private class FakeChannel(
        private val available: List<InstallVia>,
        private val failAt: Set<InstallVia>,
        val calls: MutableList<InstallVia> = mutableListOf(),
    ) : InstallChannel {
        override fun available() = available
        override suspend fun install(apk: File, via: InstallVia): InstallResult {
            calls += via
            return if (via in failAt) {
                InstallResult(false, via, "$via 在这个测试里注定失败")
            } else {
                InstallResult(true, via, "$via 成功")
            }
        }
    }

    private fun openProject(channel: InstallChannel?): ApkProject = runBlocking {
        ApkProjects.open(
            apkFile = sample!!,
            keystoreDir = Files.createTempDirectory("install-test-ks").toFile(),
            installChannel = channel,
        )
    }

    /** 把工程推进到「可装机」状态：改点东西 → 打包 → 签名。 */
    private fun ApkProject.makeInstallable(): File = runBlocking {
        replaceString("工作台", "工作台·已改")
        rebuild()
        sign(SignConfig())
    }

    @Test
    fun `Shizuku 与 Root 都失败时降到系统安装器，并如实报告用了哪一档`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        val channel = FakeChannel(
            available = listOf(InstallVia.SHIZUKU, InstallVia.ROOT),
            failAt = setOf(InstallVia.SHIZUKU, InstallVia.ROOT),
        )
        openProject(channel).use { p ->
            val apk = p.makeInstallable()

            val r = runBlocking { p.install(apk, InstallVia.SHIZUKU) }
            println("[install] 结果 ok=${r.ok} via=${r.via} msg=${r.message}")

            assertTrue(r.ok, "系统安装器兜底应当成功")
            // 关键：报告的是实际用了哪一档，不是请求的那一档
            assertEquals(InstallVia.INTENT, r.via, "不能把降级后的结果报成 Shizuku 装的")
            assertEquals(
                listOf(InstallVia.SHIZUKU, InstallVia.ROOT, InstallVia.INTENT),
                channel.calls,
                "降级要按 Shizuku → Root → 系统安装器 的顺序逐级尝试",
            )
            assertEquals(WorkspaceState.INSTALLED, p.state)
        }
    }

    @Test
    fun `Shizuku 可用时一步到位，不去碰后面的通道`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        val channel = FakeChannel(
            available = listOf(InstallVia.SHIZUKU, InstallVia.ROOT),
            failAt = emptySet(),
        )
        openProject(channel).use { p ->
            val apk = p.makeInstallable()

            val r = runBlocking { p.install(apk, InstallVia.SHIZUKU) }
            assertEquals(InstallVia.SHIZUKU, r.via)
            assertEquals(listOf(InstallVia.SHIZUKU), channel.calls, "成功了就不该再试后面的通道")
        }
    }

    @Test
    fun `没打包就装机 —— 明确拒绝，且不触碰任何通道`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        val channel = FakeChannel(available = listOf(InstallVia.SHIZUKU), failAt = emptySet())
        openProject(channel).use { p ->
            // 刚打开，什么都没改：装上去的会是原包，等于白忙
            val r = runBlocking { p.install(sample!!, InstallVia.SHIZUKU) }

            assertTrue(!r.ok)
            assertTrue(r.message!!.contains("先重打包并签名"), "要说清下一步：${r.message}")
            assertTrue(channel.calls.isEmpty(), "状态不对时不该真的去装")
        }
    }

    @Test
    fun `没有装机通道时如实报错，不假装成功`() {
        assumeTrue("未提供 SMITHY_TEST_APK，跳过集成测试", sample != null)

        openProject(channel = null).use { p ->
            val apk = p.makeInstallable()
            val r = runBlocking { p.install(apk, InstallVia.SHIZUKU) }

            assertTrue(!r.ok)
            assertTrue(
                r.message!!.contains("没有接入装机通道"),
                "要说明是通道缺失而不是装机失败：${r.message}",
            )
        }
    }
}
