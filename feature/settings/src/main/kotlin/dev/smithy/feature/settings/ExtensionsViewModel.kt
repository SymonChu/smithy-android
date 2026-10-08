package dev.smithy.feature.settings

import android.content.Context
import dev.smithy.fs.AddOnCatalog
import dev.smithy.fs.AddOnHost
import dev.smithy.fs.AddOnInstall
import dev.smithy.fs.AddOnKind
import dev.smithy.fs.AddOnManager
import dev.smithy.fs.AddOnProgress
import dev.smithy.fs.AddOnSpec
import dev.smithy.fs.NativeToolchains
import dev.smithy.fs.ShellChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 扩展中心的状态机。
 *
 * **为什么单独一个 VM 而不用 ModuleViewModel 的现成逻辑**：那一套是给模块页用的，
 * 状态跟 zygisk / 刷入 / root 混在一起，扩展中心只关心「每个条目处于哪个阶段」——
 * 混用会让「点卸载」顺手把模块那边也动一下。两边只是共用 [AddOnManager] 这一层。
 */
data class ExtensionUiState(
    /** 架构串（`arm64-v8a`），顶栏副标题要用。 */
    val abi: String = "",
    /** 清册里的条目（顺序 = [AddOnCatalog.all] 的顺序，界面上按这个排）。 */
    val catalog: List<AddOnSpec> = emptyList(),
    /** 装好了的条目：清册里的 + 本地灌进来的（后者 id 不在清册里）。 */
    val installed: List<AddOnInstall> = emptyList(),
    /** 正在进行的任务：`id -> 进度`。**同时只能有一个**——并发下两个包抢同一份 `.part` 文件。 */
    val busy: Map<String, AddOnProgress> = emptyMap(),
    /** 一条任务的最终结果（成功/失败文案 + 下一步）。做完留在这里，不自动清。 */
    val notice: Notice? = null,
    /** 权限探测结果，见 [Capabilities]。 */
    val caps: Capabilities = Capabilities(),
    /** 工具链是否真的可用（clang 能跑起来）。清册说「已装」不等于「能用」。 */
    val toolchainReady: Boolean = false,
    /** 上一次扫描时的错误（读不到安装目录之类）。 */
    val scanError: String? = null,
) {
    val installedCount: Int get() = installed.size

    val isBusy: Boolean get() = busy.isNotEmpty()

    /** 顶栏副标题：照截图那一句「架构 · 已激活 N / M 个」。 */
    val subtitle: String
        get() {
            val total = catalog.size
            val arch = abi.ifEmpty { "未知架构" }
            return "$arch · 已装 $installedCount / $total 个"
        }

    fun installOf(spec: AddOnSpec): AddOnInstall? = installed.firstOrNull { it.spec.id == spec.id }

    fun isInstalled(spec: AddOnSpec): Boolean = installOf(spec) != null

    /** 这一条是不是正忙着。 */
    fun progressOf(spec: AddOnSpec): AddOnProgress? = busy[spec.id]

    /**
     * 分组：照截图的「语言运行时 / 编译构建」那种按用途分组。
     *
     * 分组名用**这套工具干的是什么活**而不是「这是什么东西」——「编译工具链」
     * 说的是能不能编，「系统环境」说的是给了之后能干什么。安装顺序也按这个来：
     * 先有工具链，再谈别的。
     */
    val groups: List<Group>
        get() {
            val byKind = catalog.groupBy { it.kind }
            val localOnly = installed.filter { i -> catalog.none { it.id == i.spec.id } }
            val out = mutableListOf<Group>()

            byKind[AddOnKind.TOOLCHAIN]?.let { out += Group("编译工具链", it) }
            // SYSROOT 是编 so 的辅助件（头与桩库），跟工具链放一组比单独一组好懂
            val sysroot = byKind[AddOnKind.SYSROOT].orEmpty()
            val toolchain = byKind[AddOnKind.TOOLCHAIN].orEmpty()
            if (toolchain.isNotEmpty() && sysroot.isNotEmpty()) {
                out += Group("编译工具链（辅助）", sysroot)
            } else if (toolchain.isEmpty()) {
                sysroot.takeIf { it.isNotEmpty() }?.let { out += Group("编译工具链（辅助）", it) }
            }
            byKind[AddOnKind.ROOTFS]?.let { out += Group("系统环境", it) }
            byKind[AddOnKind.OTHER]?.let { out += Group("其他", it) }
            // 本地导入的（清单里没有）单独一组：它们不会自动更新，得说清这一点
            if (localOnly.isNotEmpty()) {
                out += Group(
                    title = "本地导入",
                    hint = "不在下载清单里，所以不会自动更新；要换版本得自己再导一次",
                    specs = localOnly.map { it.spec },
                    local = true,
                )
            }
            return out
        }
}

/** 一个分组。`local` 的条目在界面上没有下载按钮（清册里查不到地址）。 */
data class Group(
    val title: String,
    val specs: List<AddOnSpec>,
    val hint: String? = null,
    val local: Boolean = false,
)

data class Notice(
    val ok: Boolean,
    val title: String,
    val detail: String,
    /** 下一步。失败时必须有 —— 只说「失败了」等于让人自己猜。 */
    val hint: String? = null,
)

/**
 * 设备能力探测 —— 对应截图里的「权限中心」。
 *
 * 每项都是**当场判一次**，不做缓存：这些状态变化频繁（用户去系统设置开了权限就变了），
 * 缓存下来的话界面会一直说「未开启」，而用户明明刚打开过 —— 那是最劝退的一种错。
 */
data class Capabilities(
    val rootGranted: Boolean = false,
    val storageGranted: Boolean = false,
    val shizukuGranted: Boolean = false,
    val abi: String = "",
)

class ExtensionsViewModel(private val app: Context) {

    private val _state = MutableStateFlow(ExtensionUiState())
    val state: StateFlow<ExtensionUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    /** 正在跑的下载任务，用来挡重复点击。 */
    private var job: Job? = null

    private fun manager(): AddOnManager? = AddOnHost.current()

    /**
     * 重新扫一遍。
     *
     * 每次进页面都调：`installedAll()` 会从磁盘上的记账文件反推，所以「用户从电脑灌进去
     * 的工具链」和「上次装过的」都能被认出来 —— 只看清册的话，这两样都会显示成没装。
     */
    fun refresh() {
        val mgr = manager()
        if (mgr == null) {
            _state.update {
                it.copy(
                    catalog = AddOnCatalog.all,
                    scanError = "这台设备上没有登记扩展的安装位置（App 启动时干这活）。重启 App 应该就好了",
                )
            }
            return
        }
        val abi = abiOf(app)
        _state.update {
            it.copy(
                abi = abi,
                catalog = AddOnCatalog.all,
                installed = mgr.installedAll(),
                toolchainReady = NativeToolchains.current()?.available() == true,
                caps = Capabilities(
                    rootGranted = ShellChannels.current()?.available() == true,
                    storageGranted = storageGranted(app),
                    shizukuGranted = shizukuGranted(app),
                    abi = abi,
                ),
                scanError = null,
            )
        }
    }

    /**
     * 装一个条目（下载 → 校验 → 解包）。
     *
     * **一次只跑一个**：两个包会争同一个 `.part` 文件与同一个安装目录，第二个任务的
     * 结果无法预测。界面上的按钮在忙时全部置灰，这条规则只在这里兜底。
     */
    fun install(id: String) {
        val mgr = manager() ?: return fail("没地方装", "App 刚启动还没准备好，进页面时点一下「刷新」")
        if (job?.isActive == true) return
        val spec = AddOnCatalog.find(id)
            ?: return fail("没有这个扩展", "下架了或版本变了；「刷新」会重新读清单")
        job = scope.launch {
            val result = mgr.install(spec) { p ->
                _state.update { it.copy(busy = mapOf(spec.id to p)) }
            }
            _state.update { it.copy(busy = emptyMap()) }
            refreshQuiet()
            if (result.ok) {
                _state.update {
                    it.copy(
                        notice = Notice(
                            true,
                            "${spec.name} 装好了",
                            result.install?.let { i -> "装在 ${i.dir}（${humanSize(i.bytes)}）" }
                                ?: spec.summary,
                        ),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        notice = Notice(
                            false,
                            "${spec.name} 没装成",
                            result.message ?: "下载失败",
                            result.hint ?: "检查网络后重试；重复点会接着下，不会从头来",
                        ),
                    )
                }
            }
        }
    }

    /**
     * 装清单里的全部（截图里的「一键安装全部」）。
     *
     * **故意只装还没装的**：已装的那些清册 sha256 一变本来就判定为「没装」（要重装），
     * 这里再下就是白费 800MB 流量。sysroot 那条要 root，先跳过并在结果里说清。
     */
    fun installAll() {
        val mgr = manager() ?: return fail("没地方装", "App 刚启动还没准备好")
        if (job?.isActive == true) return
        val pending = AddOnCatalog.all.filter { mgr.installed(it) == null && !it.needsRoot }
        if (pending.isEmpty()) {
            _state.update {
                it.copy(
                    notice = Notice(
                        true,
                        "不用装",
                        "需要的那条已经在设备上了" +
                            if (AddOnCatalog.all.any { s -> mgr.installed(s) == null && s.needsRoot }) {
                                "；还差的那条要 root，得单独在「模块」页走 chroot 那条路"
                            } else {
                                ""
                            },
                    ),
                )
            }
            return
        }
        job = scope.launch {
            var ok = 0
            var failed = 0
            var lastErr: String? = null
            for (spec in pending) {
                val result = mgr.install(spec) { p ->
                    _state.update { it.copy(busy = mapOf(spec.id to p)) }
                }
                _state.update { it.copy(busy = emptyMap()) }
                if (result.ok) ok++ else {
                    failed++
                    lastErr = "${spec.name}：${result.message}"
                }
            }
            refreshQuiet()
            _state.update {
                it.copy(
                    notice = Notice(
                        ok = failed == 0,
                        title = "装完 $ok 个" + if (failed > 0) "，失败 $failed 个" else "",
                        detail = lastErr ?: "都在「本地导入」之外的清单里，可以直接用",
                        hint = if (failed > 0) "失败的点开那条看原因；重下会接着上次的位置" else null,
                    ),
                )
            }
        }
    }

    /**
     * 卸载。
     *
     * 先确认不是「正在编东西」的状态：装着的工具链可能正被一次编译用着，
     * 删掉之后那次编译报的是 `cannot find clang` 这种没法倒查的错。
     */
    fun remove(spec: AddOnSpec) {
        val mgr = manager() ?: return
        if (job?.isActive == true) return
        // 清单里的条目：走 remove()（顺带删记账文件）。本地导入的：按记账里的目录删。
        val ok = mgr.remove(spec) || spec.id.let { File(mgr.root, it + ".installed").delete() }
        refreshQuiet()
        _state.update {
            it.copy(
                notice = if (ok) {
                    Notice(
                        true,
                        "${spec.name} 已删除",
                        "占掉的空间还回来了。要再用得重新下一份" +
                            if (spec.bytes > 0) "（${humanSize(spec.bytes)} 下载）" else "",
                    )
                } else {
                    Notice(false, "${spec.name} 没删掉", "可能正被某个进程占着（App 重启后再试）", "重启 App 后再删一次")
                },
            )
        }
    }

    /** 导入手机本地的一份包（电脑传过来的工具链走这条）。 */
    fun importLocal(archive: java.io.File, name: String) {
        val mgr = manager() ?: return fail("没地方装", "App 刚启动还没准备好")
        if (job?.isActive == true) return
        job = scope.launch {
            // 本地包没有登记的 sha256 → 传空串跳过校验（installFromLocal 里 sha 为空就不验）。
            // 这不是放松底线：本地包是用户自己挑的，登记里本来就没有它的校验值，
            // 硬编一个反而是编的。
            val spec = AddOnSpec(
                id = name,
                name = name,
                summary = "本地导入的包（没登记 sha256，所以没校验）",
                kind = AddOnKind.TOOLCHAIN,
                url = "",
                bytes = archive.length(),
                sha256 = "",
                archive = if (archive.name.endsWith(".zip", ignoreCase = true)) {
                    dev.smithy.fs.AddOnArchive.ZIP
                } else {
                    dev.smithy.fs.AddOnArchive.TAR_GZ
                },
                license = "未登记（本地导入）",
                homepage = "",
                stripComponents = 1,
            )
            val result = mgr.installFromLocal(spec, archive) { p ->
                _state.update { it.copy(busy = mapOf(spec.id to p)) }
            }
            _state.update { it.copy(busy = emptyMap()) }
            refreshQuiet()
            _state.update {
                it.copy(
                    notice = if (result.ok) {
                        Notice(
                            true,
                            "$name 导入了",
                            result.install?.let { i -> "装在 ${i.dir}（${humanSize(i.bytes)}）" } ?: "",
                            "本地导入的不会自动更新：换版本要自己再导一次",
                        )
                    } else {
                        Notice(false, "$name 没导成", result.message ?: "解包失败", result.hint)
                    },
                )
            }
        }
    }

    /** 清掉上一条结果提示（点一下提示条就收起来）。 */
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun fail(title: String, detail: String) = _state.update {
        it.copy(notice = Notice(false, title, detail, "「刷新」能重新读一遍状态"))
    }

    /** 扫描完不弹提示 —— 装卸过程中会反复调它，每次都盖提示会把真结果冲掉。 */
    private fun refreshQuiet() {
        val mgr = manager() ?: return
        val abi = abiOf(app)
        _state.update {
            it.copy(
                catalog = AddOnCatalog.all,
                installed = mgr.installedAll(),
                toolchainReady = NativeToolchains.current()?.available() == true,
                caps = it.caps.copy(
                    rootGranted = ShellChannels.current()?.available() == true,
                    storageGranted = storageGranted(app),
                    shizukuGranted = shizukuGranted(app),
                ),
                scanError = null,
            )
        }
    }

    // ── 设备能力 ──────────────────────────────────────────────

    private fun abiOf(ctx: Context): String =
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""

    /** 「所有文件访问」：Android 11+ 必须去系统设置里开，没有弹窗可弹。 */
    private fun storageGranted(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            ctx.checkSelfPermission(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Shizuku 授权。
     *
     * **只报已授权，不主动去问**：真要发起 Shizuku 授权得走它的 AIDL，App 里没接；
     * 而一个「检测」按钮如果点了就弹授权框，用户会觉得 App 在替他要权限。
     * 这里只陈述事实 —— 没授权就说没授权，怎么授权去模块页（那边有引导）。
     */
    private fun shizukuGranted(ctx: Context): Boolean = runCatching {
        val cls = Class.forName("moe.shizuku.privileged.api.Shizuku")
        val m = cls.getMethod("checkSelfPermission")
        m.invoke(null) == 0
    }.getOrDefault(false)

    companion object {
        /** 154MB → 「154 MB」。界面上的体积都用它，别各写各的。 */
        fun humanSize(bytes: Long): String {
            if (bytes <= 0) return "未知"
            val mb = bytes.toDouble() / 1024 / 1024
            return if (mb >= 1) String.format("%.0f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
        }
    }
}
