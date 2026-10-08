package dev.smithy.feature.apk

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.engine.ModuleChannels
import dev.smithy.fs.ModuleLayout
import dev.smithy.fs.ModuleProp
import dev.smithy.fs.ModuleProject
import dev.smithy.fs.ModuleScaffold
import dev.smithy.fs.ModuleSkeletonSpec
import dev.smithy.fs.ModuleNativeBuild
import dev.smithy.fs.NativeAbi
import dev.smithy.fs.NativeToolchains
import dev.smithy.fs.NativeToolchain
import dev.smithy.fs.AddOnArchive
import dev.smithy.fs.AddOnCatalog
import dev.smithy.fs.AddOnHost
import dev.smithy.fs.AddOnKind
import dev.smithy.fs.AddOnProgress
import dev.smithy.fs.AddOnSpec
import dev.smithy.fs.RootFs
import dev.smithy.fs.ShellChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 模块页的状态。 */
data class ModuleUiState(
    /** 打开的那个模块 zip。null = 还没打开。 */
    val zipPath: String? = null,
    val prop: ModuleProp? = null,
    val layout: ModuleLayout? = null,
    val entries: List<String> = emptyList(),

    // 元数据草稿。改完点保存才写回 —— 边打字边写文件的话，改一半就落盘了
    val draftVersion: String = "",
    val draftVersionCode: String = "",
    val draftName: String = "",
    val draftDescription: String = "",

    /** 正在编辑的条目路径与草稿内容。 */
    val editing: String? = null,
    val editingText: String = "",

    /** 设备上已安装的模块 id。 */
    val installed: List<String> = emptyList(),

    /** 打包出来的产物（有它才能刷）。 */
    val packaged: String? = null,

    val rootOk: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
)

/**
 * 模块页。
 *
 * 定位是「**能看见、能改、能刷**」：元数据、ABI 覆盖、脚本、打包、刷入、启停。
 * 不追求把改包工作台那一套搬过来 —— 模块就是一个 zip 加几个纯文本文件，
 * 真正需要的是**把结构问题摆在眼前**（缺当前 ABI、放错层级、还带着 disable 标记）。
 *
 * 同样的事 AI 也能通过 `module.*` 工具做；这个页面是给人用的。
 */
class ModuleViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ModuleUiState())
    val state: StateFlow<ModuleUiState> = _state.asStateFlow()

    init {
        refreshInstalled()
        // 顺手看看这台设备现在能不能编（含 Termux 那条探测，要走 root 通道 → 放 IO 上）。
        // 不弹提示：编译卡自己会反映状态，安静地把工具链登记上就够了。
        viewModelScope.launch { withContext(Dispatchers.IO) { rescanToolchain() } }
    }

    /**
     * 重算「现在能不能编」，三条路按「麻烦程度」依次试：
     * ① 应用目录里的工具链（下载/导入的包，**免 root**）
     * ② rootfs 里的 clang（路线 A，要 root：chroot）
     * ③ 手机上已装的 Termux 的 clang（零下载，要 root：路径是它的私有目录）
     *
     * ②③ 都要走 shell 探测（几十到几百毫秒），所以**必须在 IO 线程调用**。
     */
    private fun rescanToolchain(): NativeToolchain? {
        val filesDir = getApplication<Application>().filesDir
        NativeToolchains.scan(*NativeToolchains.standardRoots(filesDir).toTypedArray())?.let { return it }
        val shell = ShellChannels.current() ?: return null
        if (!shell.available()) return null
        val mount = RootFs.deployDirFor()
        if (RootFs(File(mount), shell).hasClang()) {
            NativeToolchains.scanChroot(shell, mount, NativeToolchains.chrootSysroot(filesDir))?.let { return it }
        }
        return NativeToolchains.scanTermux(shell)
    }

    /** 重新读设备上的模块列表与通道可用性。 */
    fun refreshInstalled() {
        viewModelScope.launch {
            val (ok, list) = withContext(Dispatchers.IO) {
                val ch = ModuleChannels.current()
                val available = runCatching { ch?.available() == true }.getOrDefault(false)
                available to runCatching { ch?.listInstalled().orEmpty() }.getOrDefault(emptyList())
            }
            _state.update { it.copy(rootOk = ok, installed = list) }
        }
    }

    /** 打开一个模块 zip。 */
    fun open(zipPath: String) = load(zipPath, packaged = null)

    /**
     * 读一个模块 zip 进状态。
     *
     * [packaged] 是「当前该刷哪个包」：从源码编出带 so 的新包之后，那张包才是要刷的产物，
     * 而 [zipPath] 也跟着指过去（结构卡要显示出新增的 `zygisk/<abi>.so`）。
     */
    private fun load(zipPath: String, packaged: String?, done: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "读模块…", message = null, isError = false) }
            try {
                val loaded = withContext(Dispatchers.IO) {
                    ModuleProject.open(File(zipPath)).use { p ->
                        val prop = p.prop
                            ?: throw IllegalArgumentException(
                                "这个 zip 不是模块：根目录没有 module.prop（或者 prop 里缺 id / versionCode）。" +
                                    "模块 zip 的条目要直接在根上，不能套一层目录",
                            )
                        Triple(prop, p.layout, p.entryNames().sorted())
                    }
                }
                val (prop, layout, names) = loaded
                _state.update {
                    it.copy(
                        zipPath = zipPath,
                        prop = prop,
                        layout = layout,
                        entries = names,
                        draftVersion = prop.version,
                        draftVersionCode = prop.versionCode.toString(),
                        draftName = prop.name,
                        draftDescription = prop.description,
                        packaged = packaged,
                        busy = null,
                        message = done,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun close() = _state.update {
        it.copy(
            zipPath = null, prop = null, layout = null, entries = emptyList(),
            editing = null, editingText = "", packaged = null, message = null,
        )
    }

    // ── 新建 ────────────────────────────────────────────────────

    /**
     * 从骨架新建一个模块，建好直接打开。
     *
     * 骨架的结构约束（id 合法性、条目在根上、脚本、system.prop）交给
     * [ModuleScaffold] —— 在这里再挑一遍是重复，而且那几条的判定标准将来只会在
     * 一个地方改。这里只管**放在哪**和**打开它**。
     *
     * 放应用私有目录（`filesDir/modules/`）：新建的模块还没有「用户选的位置」，
     * 放在自己目录里最省事；要在别处留一份时走「另存」，不在别人的可见目录里
     * 留半成品。
     *
     * [zygisk] 只影响生成出来的是源码骨架还是纯脚本 —— 前者要自己编出 `.so`
     * 才生效（这一步手机上还没有工具链，见 docs/06 的 M6-B）。
     */
    fun create(id: String, name: String, description: String, zygisk: Boolean = false) {
        val clean = id.trim()
        // 先校验再落盘：非法 id 生成的模块 Magisk 是**静默跳过**的（列表里都不出现），
        // 早一步说清比事后去设备上查便宜得多
        ModuleProp.validateId(clean)?.let { return message(it, isError = true) }

        viewModelScope.launch {
            _state.update { it.copy(busy = "建模块…", message = null, isError = false) }
            try {
                val zip = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().filesDir, "modules").apply { mkdirs() }
                    val target = File(dir, "$clean.zip")
                    ModuleScaffold.write(
                        ModuleSkeletonSpec(
                            id = clean,
                            name = name.trim().ifBlank { clean },
                            description = description.trim(),
                            flavour = if (zygisk) {
                                ModuleSkeletonSpec.Flavour.ZYGISK
                            } else {
                                ModuleSkeletonSpec.Flavour.SHELL
                            },
                        ),
                        target,
                    )
                    target
                }
                // 建好就直接打开它：新模块的下一步一定是「改点东西再刷」，而不是先看一个空页
                open(zip.absolutePath)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 元数据 ──────────────────────────────────────────────────

    fun onVersion(v: String) = _state.update { it.copy(draftVersion = v) }
    fun onVersionCode(v: String) = _state.update { it.copy(draftVersionCode = v.filter(Char::isDigit)) }
    fun onName(v: String) = _state.update { it.copy(draftName = v) }
    fun onDescription(v: String) = _state.update { it.copy(draftDescription = v) }

    /**
     * 保存元数据，写出一个新 zip（`<原名>-edited.zip`）。
     *
     * **不原地改用户的包**：和改包那边一样，产物另存 —— 刷进去发现不对还能拿原包再来一次。
     */
    fun saveProp() {
        val s = _state.value
        val zip = s.zipPath?.let(::File) ?: return
        val old = s.prop ?: return

        val code = s.draftVersionCode.toIntOrNull()
            ?: return message("versionCode 要填整数（Magisk 用它比大小）", isError = true)

        val next = old.copy(
            version = s.draftVersion,
            versionCode = code,
            name = s.draftName,
            description = s.draftDescription,
        )
        if (next == old) return message("没有改动")

        viewModelScope.launch {
            _state.update { it.copy(busy = "写入…", message = null, isError = false) }
            try {
                val out = withContext(Dispatchers.IO) {
                    val target = editedFileFor(zip)
                    ModuleProject.open(zip).use { p ->
                        p.updateProp(next, dirNameOrNull = null)
                        p.packageTo(target)
                    }
                    target
                }
                _state.update {
                    it.copy(prop = next, packaged = out.absolutePath, busy = null, message = "已写入 ${out.name}")
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 脚本 / 文本条目 ─────────────────────────────────────────

    // ── native 编译 ─────────────────────────────────────────────

    // ── 可选组件（文档里的 M5「可选模块」）──────────────────────

    /**
     * 「准备编译环境」：把 rootfs 部署到可执行位置 → 挂 /proc、/dev → `apk add clang`
     * → 登记 chroot 工具链。
     *
     * 这是**不依赖任何外部产物**就能在手机上编 .so 的路：Alpine 的 clang 是原生 aarch64 的，
     * 而官方没有给 arm64 安卓的 clang（NDK 只有 x86_64/darwin/windows 宿主机版）。
     *
     * 每一步都留痕：这条链要 root、要 chroot、要网络，任一环断了，只报「失败」等于让人重来一遍。
     */
    fun prepareRootfsEnv() {
        val mgr = AddOnHost.current()
            ?: return message("这台设备上没登记可选组件的安装位置（App 启动时干这活）", isError = true)
        val rootfsAddon = mgr.installed(AddOnCatalog.rootfsAlpine)
            ?: return message("先装 Alpine rootfs（4MB，编译卡里有按钮）", isError = true)
        val shell = ShellChannels.current()
            ?: return message("没有可用的命令通道（App 启动时登记）", isError = true)
        if (!shell.available()) {
            return message(
                "要 root：chroot 与 mount 只有 root 能调。在 Root 管理器里给 Smithy 放行后重试",
                isError = true,
            )
        }

        viewModelScope.launch {
            val mount = RootFs.deployDirFor()
            val rootFs = RootFs(File(mount), shell)
            val failure = withContext(Dispatchers.IO) {
                _state.update { it.copy(busy = "部署 rootfs 到 $mount …", message = null, isError = false) }
                val deploy = rootFs.deploy(rootfsAddon.dir)
                if (!deploy.ok) return@withContext "部署 rootfs 失败：\n${deploy.out.tailLines(8)}"

                _state.update { it.copy(busy = "准备 chroot 环境（挂 /proc、/dev，写 resolv.conf）…") }
                val prep = rootFs.prepare()
                if (!prep.ok) {
                    return@withContext "准备 chroot 环境失败（mount 多半被 SELinux 拦了）：\n${prep.out.tailLines(8)}"
                }
                if (rootFs.hasClang()) return@withContext null   // 上次装过了

                _state.update { it.copy(busy = "在 rootfs 里装 clang（约 100–200MB，慢）…") }
                val apk = rootFs.installClang()
                if (!apk.ok) {
                    return@withContext "apk 装 clang 失败：\n${apk.out.tailLines(8)}" +
                        "\n（网络不通或镜像的 https 证书验不过都可能长这样）"
                }
                null
            }
            if (failure != null) {
                return@launch _state.update {
                    it.copy(busy = null, isError = true, message = "$failure\n改完再点一次会接着做")
                }
            }

            val sysroot = NativeToolchains.chrootSysroot(getApplication<Application>().filesDir)
            val chain = NativeToolchains.scanChroot(shell, mount, sysroot)
            _state.update {
                it.copy(
                    busy = null,
                    isError = chain == null,
                    message = if (chain != null) {
                        if (sysroot != null) {
                            "编译环境就绪：走 chroot 里的 clang + ${sysroot.name} 里的 target sysroot。可以点编译了"
                        } else {
                            "clang 就绪，但还缺 target sysroot（编 arm64 要 bionic 的头与桩库）：" +
                                "编译卡里可以下「Android sysroot（arm64-v8a）」"
                        }
                    } else {
                        "装完了，但没在 rootfs 里找到 clang —— 把上面那段输出发我"
                    },
                )
            }
        }
    }

    /** 只留最后几行：给用户看的是「出事那几句」，不是整段过程。 */
    private fun String.tailLines(n: Int): String = lines().takeLast(n).joinToString("\n")

    /**
     * 装一个可选组件（下载 + 校验 + 解包）。
     *
     * 进度写进 [ModuleUiState.busy]：几百 MB 的下载必须看得见在动，
     * 否则用户会以为卡死然后去杀进程 —— 那正是「下了一半」的来源。
     *
     * **当前没有界面调用方**（2026-10-08 起组件的下载/卸载统一在「设置 → 扩展」那一页，
     * 模块页只留一句「去扩展中心」的指引，避免两处入口让人怀疑是不是两套东西）。
     * 保留是因为这条能力属于本 VM 的职责范围，扩展中心的 VM 走的是另一条更完整的路径
     * （含分组、镜像 failover、导入本地包）；将来模块页要恢复就地安装时直接接上即可。
     */
    fun installComponent(id: String) {
        val mgr = AddOnHost.current()
            ?: return message("这台设备上没登记可选组件的安装位置（App 启动时干这活）", isError = true)
        val spec = AddOnCatalog.find(id)
            ?: return message("没有这个组件：$id", isError = true)
        viewModelScope.launch {
            _state.update { it.copy(busy = "准备下载 ${spec.name}…", message = null, isError = false) }
            val res = withContext(Dispatchers.IO) {
                mgr.install(spec) { p ->
                    _state.update { s -> s.copy(busy = "${spec.name}：${phaseText(p.phase)} ${p.percent}%") }
                }
            }
            afterComponent(res.ok, spec.name, res.message, res.hint)
        }
    }

    /**
     * 装手机本地的一份归档。
     *
     * 这条**路本身仍然必需**（给 arm64 安卓用的 clang 官方没有现成的：NDK 只有
     * x86_64/darwin/windows 宿主机版，LLVM 也不发 android 目标，得在外面产出一份、
     * 传到手机上再灌进去），但 2026-10-08 起入口统一收到扩展中心那一页了。
     * 与 [installComponent] 同样：当前没有界面调用方，保留作为能力备份。
     */
    fun importComponent(archive: File) {
        val mgr = AddOnHost.current()
            ?: return message("这台设备上没登记可选组件的安装位置（App 启动时干这活）", isError = true)
        viewModelScope.launch {
            _state.update { it.copy(busy = "装 ${archive.name}…", message = null, isError = false) }
            val spec = AddOnSpec(
                id = "local-" + archive.nameWithoutExtension.replace('.', '-'),
                name = archive.name,
                summary = "从本地灌进来的（${archive.absolutePath}）",
                kind = AddOnKind.TOOLCHAIN,
                url = "",
                bytes = archive.length(),
                // 本地灌进来的没法预先登记校验值：这里不假装校验过，装完由编译器自己去证明
                sha256 = "",
                archive = if (archive.name.endsWith(".zip", ignoreCase = true)) AddOnArchive.ZIP else AddOnArchive.TAR_GZ,
                license = "随包自带，见包内说明",
                homepage = "",
                stripComponents = 1,
            )
            val res = withContext(Dispatchers.IO) {
                mgr.installFromLocal(spec, archive) { p ->
                    _state.update { s -> s.copy(busy = "${archive.name}：${phaseText(p.phase)} ${p.percent}%") }
                }
            }
            afterComponent(res.ok, archive.name, res.message, res.hint)
        }
    }

    /**
     * 装完之后：**立刻重扫工具链**。
     *
     * 装之前说「缺工具链」，装之后同一句话还能看见的话，用户会以为装了个没用的东西 ——
     * 所以这里必须把「现在能不能编」重新算一遍再说给他听。
     */
    private suspend fun afterComponent(ok: Boolean, name: String, message: String?, hint: String?) {
        if (!ok) {
            return message(listOfNotNull(message, hint).joinToString("\n"), isError = true)
        }
        val found = withContext(Dispatchers.IO) { rescanToolchain() }
        _state.update {
            it.copy(
                busy = null,
                isError = found == null,
                message = if (found != null) {
                    "$name 装好了，编译工具链已就绪"
                } else {
                    "$name 装好了。但还没找到可用的 clang —— 工具链包解出来的目录里要有 bin/clang++ 和 sysroot/（见编译卡里的说明）"
                },
            )
        }
    }

    private fun phaseText(phase: AddOnProgress.Phase): String = when (phase) {
        AddOnProgress.Phase.DOWNLOAD -> "下载中"
        AddOnProgress.Phase.VERIFY -> "校验中"
        AddOnProgress.Phase.EXTRACT -> "解包中"
    }

    /**
     * 把 `jni/` 下的源码编成 `zygisk/<abi>.so`，产出写成 `<原名>-<abi>.zip` 并打开它。
     *
     * 这是 zygisk 那一档从「有源码」到「能生效」的必经一步：Magisk 只认
     * `zygisk/<abi>.so`，源码刷进去什么都不会发生。编译本身在
     * [ModuleNativeBuild]（纯 JVM，和 AI 用的是同一条路径）。
     *
     * 没有工具链时**立刻说清**，不启动一次注定失败的编译：clang + sysroot 是
     * 300–400MB 的下载项，用户得先把它放进来（见 docs/06 的 M6-B）。
     */
    fun compile(abi: NativeAbi = NativeAbi.ARM64_V8A) {
        val zip = _state.value.zipPath?.let(::File) ?: return
        val toolchain = NativeToolchains.current()
        if (toolchain == null || !toolchain.available()) {
            // 措辞取自 core:fs —— 模块页和 AI 看到的必须是同一句话
            return message(NativeToolchains.missingHint(), isError = true)
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = "编译 ${abi.abiName}…", message = null, isError = false) }
            val out = File(zip.parentFile, "${zip.nameWithoutExtension}-${abi.abiName}.zip")
            val result = withContext(Dispatchers.IO) {
                ModuleNativeBuild.build(zip, abi, out, toolchain)
            }
            if (!result.ok) {
                // 编译器的原话 + 下一步：只说「编译失败」等于把用户扔在原地
                val log = result.log.lines().takeLast(6).joinToString("\n")
                return@launch _state.update {
                    it.copy(
                        busy = null,
                        isError = true,
                        message = (result.hint ?: "编译失败") + if (log.isBlank()) "" else "\n$log",
                    )
                }
            }
            // 编出来的那张包才是「当前要刷的」：产物另存，原 zip 一个字节没动
            val built = result.outZip
                ?: return@launch message("编译器说成功了，但没有产物文件 —— 这不该发生，把工具链路径记下来报给我们", isError = true)
            load(built.absolutePath, packaged = built.absolutePath, done = "已编出 ${result.soEntry}")
        }
    }

    fun startEdit(path: String) {
        val s = _state.value
        val zip = s.zipPath?.let(::File) ?: return
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ModuleProject.open(zip).use { it.readText(path) }
                }
                if (text == null) {
                    message("$path 读不出文本（可能是二进制条目）", isError = true)
                    return@launch
                }
                _state.update { it.copy(editing = path, editingText = text, message = null) }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun updateEditing(text: String) = _state.update { it.copy(editingText = text) }

    fun cancelEdit() = _state.update { it.copy(editing = null, editingText = "") }

    /** 保存正在编辑的条目，写出新 zip。 */
    fun saveEntry() {
        val s = _state.value
        val zip = s.zipPath?.let(::File) ?: return
        val path = s.editing ?: return

        viewModelScope.launch {
            _state.update { it.copy(busy = "写入…", message = null, isError = false) }
            try {
                val out = withContext(Dispatchers.IO) {
                    val target = editedFileFor(zip)
                    ModuleProject.open(zip).use { p ->
                        p.writeText(path, s.editingText)
                        p.packageTo(target)
                    }
                    target
                }
                _state.update {
                    it.copy(editing = null, editingText = "", packaged = out.absolutePath, busy = null, message = "已写入 $path")
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 设备操作 ────────────────────────────────────────────────

    /** 刷入。有改动产物就刷产物，否则刷原包。 */
    fun install() {
        val s = _state.value
        val zip = s.packaged?.let(::File) ?: s.zipPath?.let(::File) ?: return

        viewModelScope.launch {
            _state.update { it.copy(busy = "刷入…", message = null, isError = false) }
            val r = withContext(Dispatchers.IO) {
                runCatching { ModuleChannels.require() }
                    .fold(onSuccess = { it.install(zip) }, onFailure = { e ->
                        dev.smithy.engine.ModuleOpResult(false, e.message ?: "模块通道不可用")
                    })
            }
            _state.update { it.copy(busy = null, message = r.message, isError = !r.ok) }
            refreshInstalled()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = deviceOp("改状态…") { it.setEnabled(id, enabled) }

    fun scheduleRemove(id: String) = deviceOp("标记卸载…") { it.scheduleRemove(id) }

    fun uninstallNow(id: String) = deviceOp("删除…") { it.uninstallNow(id) }

    /** 软重启 zygote。**会影响所有正在运行的应用**，界面那边必须先确认。 */
    fun restartZygote() = deviceOp("重启 zygote…") { it.restartZygote() }

    private fun deviceOp(busyText: String, block: (dev.smithy.engine.ModuleChannel) -> dev.smithy.engine.ModuleOpResult) {
        viewModelScope.launch {
            _state.update { it.copy(busy = busyText, message = null, isError = false) }
            val r = withContext(Dispatchers.IO) {
                runCatching { ModuleChannels.require() }
                    .fold(
                        onSuccess = { ch -> runCatching { block(ch) }.getOrElse { e ->
                            dev.smithy.engine.ModuleOpResult(false, e.message ?: "执行出错")
                        } },
                        onFailure = { e -> dev.smithy.engine.ModuleOpResult(false, e.message ?: "模块通道不可用") },
                    )
            }
            _state.update { it.copy(busy = null, message = r.message, isError = !r.ok) }
            refreshInstalled()
        }
    }

    // ── 杂项 ────────────────────────────────────────────────────

    private fun editedFileFor(zip: File) = File(zip.parentFile, zip.nameWithoutExtension + "-edited.zip")

    private fun message(text: String, isError: Boolean = false) =
        _state.update { it.copy(message = text, isError = isError) }

    private fun fail(t: Throwable) = _state.update {
        it.copy(busy = null, message = t.message ?: t.toString(), isError = true)
    }
}
