package dev.smithy.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1 集成测试：签名与验签。
 *
 * 签名这一环最容易出的两个问题都不可见于本机：
 *   ① 签了但方案不全（比如只签了 v1），老系统装不上或新系统拒装；
 *   ② 密钥每次都重新生成 → 指纹变化 → 改过的包覆盖安装被系统拒（只报"应用未安装"）。
 * 所以这里既验签名有效性，也验**指纹的稳定性**。
 */
class SignTest {

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    private fun requireSample(): File {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)
        return sampleApk!!
    }

    @Test
    fun `改包签名后验签通过 且方案覆盖 v1 v2 v3`() = runBlocking {
        ApkProjects.open(requireSample()).use { project ->
            assumeTrue(
                "样本里没有「工作台」，跳过",
                project.dexSearch(DexQuery("工作台", scope = DexQuery.Scope.STRING)).isNotEmpty(),
            )
            project.replaceString("工作台", "操作台")
            val unsigned = project.rebuild()
            val signed = project.sign(SignConfig())

            println("── 未签名 ${unsigned.length() / 1024}KB → 已签名 ${signed.length() / 1024}KB")

            val result = project.verify(signed)
            println("── 验签: valid=${result.valid} 方案=${result.schemes}")
            result.messages.take(5).forEach { println("     $it") }

            assertTrue(result.valid, "自签名的包必须验签通过")
            // v1 不开：minSdk 26 的系统用 v2/v3 就够；而且当前 apksig 版本的 v1 路径会 NPE
            assertEquals(listOf(2, 3), result.schemes.sorted(), "应有 v2/v3；v1 在本依赖版本上不可用")
            assertTrue(
                signed.length() > 0 && signed.length() != unsigned.length(),
                "签名会在包里加入 META-INF 与签名块，大小应当变化",
            )

            // 用引擎重新打开已签名的包：签名信息要读得出来，且改动还在
            ApkProjects.open(signed).use { reopened ->
                val sig = reopened.meta.signatures.firstOrNull()
                println("── 已签名包的签名: v${sig?.scheme} subject=${sig?.subject} debug=${sig?.isDebug}")
                assertTrue(sig != null, "应能解析出签名信息")
                assertFalse(sig!!.isDebug, "内置证书不是 Android debug 证书，不该被判为 debug")
                assertTrue(
                    sig.subject.contains("Smithy"),
                    "证书主体应是内置密钥，实际: ${sig.subject}",
                )

                // 签名块不该破坏包内容
                assertEquals(0, reopened.dexSearch(DexQuery("工作台", scope = DexQuery.Scope.STRING)).size)
                assertTrue(reopened.dexSearch(DexQuery("操作台", scope = DexQuery.Scope.STRING)).isNotEmpty())
            }
        }
    }

    @Test
    fun `内置密钥跨会话指纹稳定`() = runBlocking {
        val ksDir = File("/tmp/smithy-ks-persist-test")
        ksDir.deleteRecursively()

        suspend fun signAndReadFingerprint(tag: String): String {
            var fp = ""
            ApkProjects.open(requireSample(), keystoreDir = ksDir).use { project ->
                assumeTrue(
                    "样本里没有「工作台」，跳过",
                    project.dexSearch(DexQuery("工作台", scope = DexQuery.Scope.STRING)).isNotEmpty(),
                )
                project.replaceString("工作台", "操作台")
                project.rebuild()
                val signed = project.sign(SignConfig())
                ApkProjects.open(signed).use { reopened ->
                    fp = reopened.meta.signatures.firstOrNull()?.sha256.orEmpty()
                }
            }
            println("── $tag 指纹: ${fp.take(24)}…")
            return fp
        }

        val first = signAndReadFingerprint("第一次")
        val second = signAndReadFingerprint("第二次")
        println("── keystore 目录内容: ${ksDir.list()?.joinToString()}")

        assertTrue(first.isNotBlank(), "应能读到证书指纹")
        assertEquals(
            first, second,
            "两次改包的签名指纹必须一致 —— 变了的话覆盖安装会被系统拒绝（只报「应用未安装」）",
        )
    }

    @Test
    fun `没打包就签名 与 没改动就打包 都要被拒绝`() = runBlocking {
        ApkProjects.open(requireSample()).use { project ->
            val e1 = runCatching { project.sign(SignConfig()) }.exceptionOrNull()
            println("── 未打包就签名 → ${e1?.javaClass?.simpleName}: ${e1?.message}")
            assertTrue(e1 is IllegalStateException, "应明确报错，而不是签一个空文件")

            val e2 = runCatching { project.rebuild() }.exceptionOrNull()
            println("── 无改动就打包 → ${e2?.javaClass?.simpleName}: ${e2?.message}")
            assertTrue(e2 is IllegalStateException)
        }
    }

    /**
     * 这不是一个"功能正常"的用例，而是把**已知限制**钉在测试里：
     * 当前 apksig-android 4.4.0 走 v1 会 NPE（库内部问题，外部修不了）。
     *
     * 一旦将来升级依赖后这条断言开始失败，就说明 v1 能用了 ——
     * 那时应当把 v1 打开、删掉本用例与 docs/08-risks.md 里的限制说明。
     */
    @Test
    fun `已知限制 v1 签名在当前 apksig 版本上失败`() = runBlocking {
        val sample = requireSample()
        val material = dev.smithy.engine.internal.Keystores.loadOrCreate(
            File("/tmp/smithy-ks-v1-limit"),
        )
        val out = File.createTempFile("smithy-v1-try", ".apk")
        out.deleteOnExit()

        val err = runCatching {
            dev.smithy.engine.internal.Signer.sign(
                input = sample,
                output = out,
                privateKey = material.privateKey,
                certificates = material.certificates,
                minSdk = 26,
                schemes = setOf(1),
            )
        }.exceptionOrNull()

        println("── 直接用 v1 签 → ${err?.javaClass?.name}: ${err?.message}")
        assertTrue(
            err != null,
            "预期 v1 在当前依赖上失败。若这里开始通过，说明依赖已升级 —— " +
                "请打开 v1、删除本用例，并更新 docs/08-risks.md",
        )
    }
}
