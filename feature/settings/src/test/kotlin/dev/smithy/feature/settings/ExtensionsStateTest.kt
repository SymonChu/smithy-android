package dev.smithy.feature.settings

import dev.smithy.fs.AddOnCatalog
import dev.smithy.fs.AddOnInstall
import dev.smithy.fs.AddOnKind
import dev.smithy.fs.AddOnProgress
import dev.smithy.fs.AddOnSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 扩展中心的状态层。
 *
 * 只测**纯逻辑**（分组、副标题、体积换算、进度百分比）—— 这些是「界面上说错话」的源头，
 * 而界面本身要真机肉眼验。Compose 的部分这里不碰：跑不起 instrumentation 就别假装测过。
 */
class ExtensionsStateTest {

    private fun spec(
        id: String,
        kind: AddOnKind,
        name: String = id,
        bytes: Long = 1024L,
    ) = AddOnSpec(
        id = id,
        name = name,
        summary = "",
        kind = kind,
        url = "https://example.invalid/$id.tar.gz",
        bytes = bytes,
        sha256 = "0".repeat(64),
        archive = dev.smithy.fs.AddOnArchive.TAR_GZ,
        license = "",
        homepage = "",
    )

    private fun installOf(spec: AddOnSpec, bytes: Long = 2048L, at: Long = 1_700_000_000_000L) =
        AddOnInstall(spec, java.io.File("/tmp/${spec.id}"), bytes, at)

    // ── 体积换算 ──────────────────────────────────────────────

    @Test
    fun `体积小于 1MB 用 KB，超过用 MB`() {
        assertEquals("512 KB", ExtensionsViewModel.humanSize(524_288L))
        assertEquals("154 MB", ExtensionsViewModel.humanSize(161_298_217L))
        // 656MB 那个 NDK：整数除法会写成 637MB 之类，取整要按真实字节算
        assertEquals("638 MB", ExtensionsViewModel.humanSize(668_556_491L))
    }

    @Test
    fun `体积拿不到时说实话而不是显示 0`() {
        // 清册里没有体积的本地包：显示「未知」，不显示「0 MB」——后者看着像「空文件」
        assertEquals("未知", ExtensionsViewModel.humanSize(0L))
    }

    // ── 分组 ──────────────────────────────────────────────────

    @Test
    fun `工具链与 sysroot 各自成组，sysroot 标明是辅助件`() {
        val tc = spec("tc", AddOnKind.TOOLCHAIN)
        val sr = spec("sr", AddOnKind.SYSROOT)
        val state = ExtensionUiState(catalog = listOf(tc, sr), installed = emptyList())
        val titles = state.groups.map { it.title }
        assertTrue("编译工具链" in titles, "工具链要有自己的组，实际=$titles")
        assertTrue(
            titles.any { it.contains("辅助") },
            "sysroot 要说明它是工具链的辅助件，实际=$titles",
        )
        // 每一份清单条目都得在某组里出现 —— 漏掉一条就是界面上「明明登记了却看不见」
        val grouped = state.groups.flatMap { it.specs }.map { it.id }.toSet()
        assertEquals(setOf("tc", "sr"), grouped)
    }

    @Test
    fun `本地导入的条目单独一组并说明不会自动更新`() {
        val tc = spec("tc", AddOnKind.TOOLCHAIN)
        val localSpec = spec("my-toolchain", AddOnKind.TOOLCHAIN)
        val state = ExtensionUiState(
            catalog = listOf(tc),
            installed = listOf(installOf(tc), installOf(localSpec)),
        )
        val local = state.groups.firstOrNull { it.local }
        assertNotNull(local, "本地导入的要有独立分组")
        assertEquals(listOf("my-toolchain"), local.specs.map { it.id })
        assertNotNull(local.hint, "本地导入的必须说清不会自动更新")
        assertTrue(local.hint.contains("不会自动更新"))
    }

    @Test
    fun `清册与已装取并集 不会互相顶掉`() {
        // 清册有、已装（未装）+ 已装（清单里没有的）：两条都得看得见
        val tc = spec("tc", AddOnKind.TOOLCHAIN)
        val rootfs = spec("rootfs", AddOnKind.ROOTFS)
        val extra = spec("extra", AddOnKind.OTHER)
        val state = ExtensionUiState(
            catalog = listOf(tc, rootfs),
            installed = listOf(installOf(tc), installOf(extra)),
        )
        val grouped = state.groups.flatMap { it.specs }.map { it.id }.toSet()
        assertEquals(setOf("tc", "rootfs", "extra"), grouped)
    }

    @Test
    fun `空清单不产生分组（而不是产出一个空组）`() {
        val state = ExtensionUiState(catalog = emptyList(), installed = emptyList())
        assertTrue(state.groups.isEmpty())
    }

    // ── 副标题与状态 ──────────────────────────────────────────

    @Test
    fun `副标题说出架构与已装数量`() {
        val state = ExtensionUiState(
            abi = "arm64-v8a",
            catalog = listOf(spec("a", AddOnKind.TOOLCHAIN), spec("b", AddOnKind.ROOTFS)),
            installed = listOf(installOf(spec("a", AddOnKind.TOOLCHAIN))),
        )
        assertEquals("arm64-v8a · 已装 1 / 2 个", state.subtitle)
    }

    @Test
    fun `架构没探到时说实话`() {
        // 空 ABI 显示「未知架构」而不是「· 已装 0/3 个」——少了主语读不出是什么
        assertTrue(ExtensionUiState(abi = "").subtitle.startsWith("未知架构"))
    }

    @Test
    fun `isInstalled 与 installOf 对同一条给出同一个答案`() {
        val tc = spec("tc", AddOnKind.TOOLCHAIN)
        val rootfs = spec("rootfs", AddOnKind.ROOTFS)
        val state = ExtensionUiState(catalog = listOf(tc, rootfs), installed = listOf(installOf(tc)))
        assertTrue(state.isInstalled(tc))
        assertEquals(tc.id, state.installOf(tc)?.spec?.id)
        assertTrue(!state.isInstalled(rootfs))
        assertNull(state.installOf(rootfs))
    }

    // ── 进度 ──────────────────────────────────────────────────

    @Test
    fun `只有一条在忙时 isBusy 成立 全部结束时复位`() {
        var state = ExtensionUiState(catalog = listOf(spec("a", AddOnKind.TOOLCHAIN)))
        assertTrue(!state.isBusy)
        state = state.copy(busy = mapOf("a" to AddOnProgress(AddOnProgress.Phase.DOWNLOAD, 10, 100)))
        assertTrue(state.isBusy)
        state = state.copy(busy = emptyMap())
        assertTrue(!state.isBusy)
    }

    @Test
    fun `进度百分比按已下载与总量算 总量为 0 时不崩`() {
        // 总量为 0（服务器没给 Content-Length）：算成 0%，不能是除零异常 ——
        // 进度条整个崩掉比显示 0% 糟得多
        assertEquals(0, AddOnProgress(AddOnProgress.Phase.DOWNLOAD, 100, 0).percent)
        assertEquals(50, AddOnProgress(AddOnProgress.Phase.DOWNLOAD, 50, 100).percent)
        // 超出总量的续传进度不该越界（下载完最后一字节时 done == total）
        assertEquals(100, AddOnProgress(AddOnProgress.Phase.VERIFY, 120, 100).percent)
    }

    // ── 清单本身 ──────────────────────────────────────────────

    @Test
    fun `清册里的条目 id 不重复`() {
        // 重复 id 会让界面上的两行指向同一个安装目录，第二行删第一行的东西
        val ids = AddOnCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "清册有重复 id：$ids")
    }

    @Test
    fun `清册里的条目都有校验值与体积`() {
        // AddOn.kt 的头一条原则：有 sha256 才下。这里钉住它 —— 将来有人「先加一条占位」
        // 会直接被这条测试拦下
        AddOnCatalog.all.forEach {
            assertEquals(64, it.sha256.length, "${it.id} 的 sha256 不是 64 位")
            assertTrue(it.bytes > 0, "${it.id} 没写体积")
            assertTrue(it.url.isNotEmpty(), "${it.id} 没有下载地址")
        }
    }

    @Test
    fun `要 root 的条目标出来了 界面上不能装作不需要`() {
        // rootfs 那条要 root。忘了标的话界面上不会提示，用户装完才发现用不了
        assertTrue(
            AddOnCatalog.all.first { it.id == "rootfs-alpine" }.needsRoot,
            "rootfs-alpine 应当标为需要 root",
        )
        // 反过来：免 root 的主路径（clang 工具链）不能被标成要 root，
        // 否则界面上会劝退「别的用户」唯一走得通的那条路
        assertTrue(
            !AddOnCatalog.all.first { it.id == "toolchain-clang-aarch64" }.needsRoot,
            "clang 工具链是免 root 的，不该标 needsRoot",
        )
    }
}
