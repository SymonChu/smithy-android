package dev.smithy.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 体检结论。
 *
 * 这些规则决定「用户会不会白改一版」，所以每条都要有断言 ——
 * 尤其是两条最贵的错判：把加固包当成可改（改完全白做）、
 * 把 Xposed 模块当成普通 App（改包名后注入失效，而用户不知道）。
 */
class ApkHealthTest {

    private fun meta(
        packageName: String = "com.example.app",
        minSdk: Int = 28,
        targetSdk: Int = 35,
        isSplit: Boolean = false,
        dexCount: Int = 1,
        packer: PackerGuess? = null,
        signatures: List<SignatureInfo> = listOf(sig()),
    ) = ApkMeta(
        sourcePath = "/tmp/a.apk",
        packageName = packageName,
        versionName = "1.0",
        versionCode = 1L,
        minSdk = minSdk,
        targetSdk = targetSdk,
        appLabel = "示例",
        permissions = emptyList(),
        components = emptyList(),
        signatures = signatures,
        dexStats = List(dexCount) { DexStat("classes${if (it == 0) "" else it + 1}.dex", 10, 100, 200, 1024) },
        sizeBytes = 1024,
        isSplit = isSplit,
        packerGuess = packer,
    )

    private fun sig(scheme: Int = 2, debug: Boolean = false, sha256: String = "aa".repeat(32)) = SignatureInfo(
        scheme = scheme,
        subject = "CN=x",
        issuer = "CN=x",
        md5 = "m",
        sha1 = "s",
        sha256 = sha256,
        isDebug = debug,
    )

    private val baseEntries = listOf(
        "AndroidManifest.xml",
        "resources.arsc",
        "classes.dex",
        "lib/arm64-v8a/libfoo.so",
    )

    @Test
    fun `普通包是「可以放心改」`() {
        val h = ApkHealthCheck.of(meta(), baseEntries)
        assertEquals(CheckLevel.OK, h.level)
        assertTrue(h.headline.contains("可以放心改"), h.headline)
    }

    @Test
    fun `加固包顶到结论里 并说清哪些改动仍然可用`() {
        val packer = PackerGuess("360 加固", 1f, listOf("libjiagu.so"))
        val h = ApkHealthCheck.of(meta(packer = packer), baseEntries + "lib/arm64-v8a/libjiagu.so")

        assertEquals(CheckLevel.BAD, h.level)
        assertTrue(h.headline.contains("加固"), h.headline)
        // 「改资源仍然可能生效」这句是给用户的出路，不能丢
        assertTrue(h.detail!!.contains("资源"), h.detail!!)
    }

    @Test
    fun `Xposed 模块的结论指向改包名会让注入失效`() {
        val h = ApkHealthCheck.of(meta(), baseEntries + "assets/xposed_init")

        assertEquals(CheckLevel.WARN, h.level)
        assertTrue(h.headline.contains("改包名"), h.headline)
        assertTrue(h.detail!!.contains("入口"), h.detail!!)
        assertTrue(h.next!!.contains("改包名"), h.next!!)
    }

    @Test
    fun `只有一个 armeabi-v7a 时警告 64 位系统装不上`() {
        val entries = listOf("AndroidManifest.xml", "resources.arsc", "classes.dex", "lib/armeabi-v7a/libfoo.so")
        val h = ApkHealthCheck.of(meta(), entries)

        val abi = h.items.single { it.group == "ABI" }
        assertEquals(CheckLevel.WARN, abi.level)
        assertTrue(abi.text.contains("arm64"), abi.text)
    }

    @Test
    fun `清单读不出来直接判不可改`() {
        val h = ApkHealthCheck.of(meta(packageName = "—"), baseEntries)
        assertEquals(CheckLevel.BAD, h.level)
        assertTrue(h.headline.contains("改不了"), h.headline)
    }

    @Test
    fun `签名与机上不同时写明装机要先卸载`() {
        val h = ApkHealthCheck.of(meta(), baseEntries, installedCertSha256 = "bb".repeat(32))
        val s = h.items.single { it.group == "签名" }
        assertEquals(CheckLevel.WARN, s.level)
        assertTrue(s.text.contains("先卸载"), s.text)
    }

    @Test
    fun `同一把证书时不提卸载`() {
        val h = ApkHealthCheck.of(meta(), baseEntries, installedCertSha256 = "aa".repeat(32))
        val s = h.items.single { it.group == "签名" }
        assertEquals(CheckLevel.OK, s.level)
    }

    @Test
    fun `minSdk 低于 24 时提示要带 v1 签名`() {
        val h = ApkHealthCheck.of(meta(minSdk = 21), baseEntries)
        assertTrue(h.items.any { it.group == "系统版本" && it.text.contains("v1") }, h.items.toString())
    }

    @Test
    fun `没有资源表时如实说`() {
        val h = ApkHealthCheck.of(meta(), listOf("AndroidManifest.xml", "classes.dex"))
        val s = h.items.single { it.group == "结构" }
        assertTrue(s.text.contains("没有资源表"), s.text)
    }

    // ── 加固识别 ──────────────────────────────────────────────

    @Test
    fun `按 so 名认得出常见壳`() {
        val g = PackerDetect.guess(listOf("lib/arm64-v8a/libjiagu.so", "classes.dex"))
        assertNotNull(g)
        assertEquals("360 加固", g.name)
        // 一条命中只是「怀疑」，不该给满分置信度
        assertTrue(g.confidence < 1f, "单条命中不该是确定")
    }

    @Test
    fun `命中的特征不止一条才是确定`() {
        // 同一个 so 出现在两个 ABI 目录里仍然只是一个特征（所以按**特征名**去重，
        // 不按出现次数）—— 这条例钉的就是这件事
        val g = PackerDetect.guess(listOf("lib/arm64-v8a/libjiagu.so", "lib/arm64-v8a/libjiagu_art.so"))
        assertEquals(1f, g!!.confidence)
    }

    @Test
    fun `同一个 so 跨 ABI 出现不算两条特征`() {
        val g = PackerDetect.guess(listOf("lib/arm64-v8a/libjiagu.so", "lib/armeabi-v7a/libjiagu.so"))
        assertTrue(g!!.confidence < 1f, "同一特征重复出现不该升级成「确定」")
    }

    @Test
    fun `认不出来时不猜名字`() {
        assertNull(PackerDetect.guess(listOf("AndroidManifest.xml", "classes.dex", "lib/arm64-v8a/libfoo.so")))
    }

    @Test
    fun `assets 里藏 dex 报未知壳`() {
        val g = PackerDetect.guess(listOf("assets/classes0.dex", "classes.dex"))
        assertNotNull(g)
        assertTrue(g.name.contains("未知"), g.name)
    }
}
