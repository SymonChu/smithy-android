package dev.smithy.feature.apk

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.engine.ApkEntry
import dev.smithy.engine.ApkMeta
import dev.smithy.engine.ApkProject
import dev.smithy.engine.ApkProjects
import dev.smithy.engine.ApkReport
import dev.smithy.engine.DexHit
import dev.smithy.engine.DexQuery
import dev.smithy.engine.InstallVia
import dev.smithy.engine.ManifestField
import dev.smithy.engine.PatchRecord
import dev.smithy.engine.ReplaceScope
import dev.smithy.engine.ResourceEntry
import dev.smithy.engine.SignConfig
import dev.smithy.engine.StringReplacement
import dev.smithy.engine.WorkspaceState
import dev.smithy.toolkit.WorkspaceHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────
// 工作台状态
// ─────────────────────────────────────────────────────────────

sealed interface Phase {
    data object Empty : Phase
    data class Loading(val stage: String) : Phase
    data object Ready : Phase
    data class Failed(val message: String, val hint: String?) : Phase
}

enum class WorkbenchTab(val label: String) {
    OVERVIEW("概览"),
    CODE("代码"),
    RESOURCES("资源"),
    FILES("文件"),
    PATCHES("改动"),
}

enum class CodeView(val label: String) { JAVA("Java"), SMALI("smali") }

data class WorkbenchUiState(
    val phase: Phase = Phase.Empty,
    val sourceName: String = "",
    val meta: ApkMeta? = null,
    val entryCount: Int = 0,
    val tab: WorkbenchTab = WorkbenchTab.OVERVIEW,

    // ── 代码标签 ──
    val query: String = "",
    val scope: DexQuery.Scope = DexQuery.Scope.STRING,
    val hits: List<DexHit> = emptyList(),
    val openedClass: String? = null,
    val javaCode: String? = null,
    val smaliCode: String? = null,
    val codeView: CodeView = CodeView.JAVA,

    // ── 资源标签 ──
    val resources: List<ResourceEntry> = emptyList(),
    /** 空串表示「全部类型」 */
    val resType: String = "string",
    val resFilter: String = "",

    // ── 文件标签 ──
    val entries: List<ApkEntry> = emptyList(),
    val entryFilter: String = "",

    // ── 改动标签 ──
    val patches: List<PatchRecord> = emptyList(),

    // ── 概览标签：改名 / 改版本 ──
    // 三个输入框的当前文本。打开包时用实际值填充，用户改完点「应用」才写进包。
    val editLabel: String = "",
    val editVersionName: String = "",
    val editVersionCode: String = "",

    /**
     * 支持的系统版本。
     *
     * 单独一组而不是并进上面三行：改它的风险不一样 —— 提高 minSdk 等于**放弃老设备**，
     * 而改名改版本没有这种代价。混在一起容易让人顺手改掉。
     */
    val editMinSdk: String = "",
    val editTargetSdk: String = "",

    // ── 打包链路 ──
    val workspaceState: WorkspaceState = WorkspaceState.IDLE,
    val rebuiltPath: String? = null,
    val signedPath: String? = null,

    /** 正在进行的事（按钮据此置灰）。null = 空闲。 */
    val busy: String? = null,
    /** 最近一次动作的结果，直接展示给用户 */
    val message: String? = null,
    val isError: Boolean = false,
)

/**
 * 工作台。注意下面每一条写操作都走 [ApkProject]，UI 不直接碰任何引擎库 ——
 * 这样将来换引擎实现（比如畸形包交给 rootfs 里的 apktool）不用改这个文件。
 */
class ApkWorkbenchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(WorkbenchUiState())
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()

    private var opened: ApkProject? = null

    /** 签名密钥必须放在持久位置：指纹一变，改过的包就装不上（见 docs/08） */
    private fun keystoreDir(): File =
        File(getApplication<Application>().filesDir, "keystore").apply { mkdirs() }

    // ── 打开 / 关闭 ──────────────────────────────────────────

    fun open(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Loading("读取文件…"), message = null) }
            try {
                val name = queryDisplayName(uri)
                val local = copyToCache(uri, name)

                _state.update { it.copy(phase = Phase.Loading("解析清单 / 签名 / dex…")) }
                val project = ApkProjects.open(
                    apkFile = local,
                    keystoreDir = keystoreDir(),
                    installChannel = InstallChannelRegistry.install,
                )
                val count = project.list().size

                closeCurrent()
                opened = project
                val meta = project.meta
                // 告诉对话层现在操作的是哪个包 —— 两边必须是同一个工程实例，
                // 否则 AI 改的东西用户在这个改动列表里看不到（各开一份会得到两份覆盖层）
                WorkspaceHolder.set(project, name)
                _state.value = WorkbenchUiState(
                    phase = Phase.Ready,
                    sourceName = name,
                    meta = meta,
                    entryCount = count,
                    workspaceState = project.state,
                    // 改名/版本的输入框用当前值打底，用户只需改动他要改的那个
                    editLabel = meta.appLabel,
                    editVersionName = meta.versionName,
                    editVersionCode = meta.versionCode.toString(),
                    editMinSdk = meta.minSdk.toString(),
                    editTargetSdk = meta.targetSdk.toString(),
                )
            } catch (t: Throwable) {
                _state.update {
                    it.copy(phase = Phase.Failed(t.message ?: t::class.java.simpleName, hintFor(t)))
                }
            }
        }
    }

    fun close() {
        viewModelScope.launch {
            closeCurrent()
            _state.value = WorkbenchUiState()
        }
    }

    override fun onCleared() {
        opened?.close(keepArtifacts = false)
        opened = null
        // 对话层不能再拿着一个已经关掉的工程
        WorkspaceHolder.clear()
        super.onCleared()
    }

    private suspend fun closeCurrent() {
        opened?.let { runCatching { it.close(keepArtifacts = false) } }
        opened = null
        WorkspaceHolder.clear()
    }

    // ── 界面事件 ─────────────────────────────────────────────

    fun selectTab(tab: WorkbenchTab) = _state.update { it.copy(tab = tab) }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setScope(scope: DexQuery.Scope) = _state.update { it.copy(scope = scope) }

    fun showCodeView(view: CodeView) = _state.update { it.copy(codeView = view) }

    // ── 代码标签：搜索 / 查看 / 替换 ──────────────────────────

    fun search() {
        val project = opened ?: return
        val s = _state.value
        val q = s.query.trim()
        if (q.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(busy = "搜索中…", message = null, isError = false) }
            try {
                val hits = project.dexSearch(DexQuery(q, s.scope, limit = 300))
                _state.update {
                    it.copy(
                        busy = null,
                        hits = hits,
                        message = if (hits.isEmpty()) "没有命中「$q」" else "命中 ${hits.size} 处",
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 打开一个类。
     *
     * 先取 Java 再取 smali，两步分别更新：jadx 第一次要几百毫秒到数秒，
     * 先让用户看到 Java，别让他对着空白等两件事都做完。
     */
    fun openClass(className: String) {
        val project = opened ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    openedClass = className,
                    javaCode = null,
                    smaliCode = null,
                    codeView = CodeView.JAVA,
                    busy = "反编译 $className…",
                )
            }
            val java = runCatching { project.decompileToJava(className) }
                .getOrElse { "// 反编译失败：${it.message}" }
            _state.update { it.copy(javaCode = java, busy = "反汇编 $className…") }

            val smali = runCatching { project.readSmali(className) }
                .getOrElse { "# 反汇编失败：${it.message}" }
            _state.update { it.copy(smaliCode = smali, busy = null) }
        }
    }

    /** 改字符串常量。这是 M1 的主力动作：走 dexlib2 的常量池改写，只动命中的 dex。 */
    fun replaceString(from: String, to: String) {
        val project = opened ?: return
        if (from.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(busy = "替换字符串…", message = null, isError = false) }
            try {
                val records = project.replaceString(from, to)
                refreshPatches()
                _state.update {
                    it.copy(
                        busy = null,
                        workspaceState = project.state,
                        message = if (records.isEmpty()) {
                            "没有命中的字符串常量「$from」"
                        } else {
                            "已改 ${records.size} 个 dex（${records.joinToString("、") { r -> r.target }}）"
                        },
                        isError = records.isEmpty(),
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 打包链路 ─────────────────────────────────────────────

    fun rebuild() {
        val project = opened ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "重打包…", message = null, isError = false) }
            try {
                val out = project.rebuild()
                _state.update {
                    it.copy(
                        busy = null,
                        rebuiltPath = out.absolutePath,
                        workspaceState = project.state,
                        message = "已打包：${"%.1f".format(out.length() / 1024.0 / 1024.0)}MB" +
                            "（未改动条目原样搬运）",
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun sign() {
        val project = opened ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "签名…", message = null, isError = false) }
            try {
                val out = project.sign(SignConfig())
                val verify = project.verify(out)
                _state.update {
                    it.copy(
                        busy = null,
                        signedPath = out.absolutePath,
                        workspaceState = project.state,
                        message = if (verify.valid) {
                            "已签名并通过验签（方案 ${verify.schemes.joinToString("/") { n -> "v$n" }}）"
                        } else {
                            "签名完成，但验签没过：${verify.messages.firstOrNull() ?: "原因未知"}"
                        },
                        isError = !verify.valid,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 安装。从 Shizuku 起、逐级降到系统安装器（降级逻辑在引擎里）。
     *
     * 结果里会如实说明最后用了哪一档 —— 静默降级到「弹界面让你手点」
     * 却报告"已静默安装"，是最误导人的行为。
     */
    fun install() {
        val project = opened ?: return
        val target = _state.value.signedPath ?: _state.value.rebuiltPath
        if (target == null) {
            _state.update { it.copy(message = "还没有打包产物：先重打包并签名", isError = true) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = "安装…", message = null, isError = false) }
            try {
                val r = project.install(File(target), InstallVia.SHIZUKU)
                _state.update {
                    it.copy(
                        busy = null,
                        workspaceState = project.state,
                        message = when {
                            // 交给安装器 ≠ 装完。说成「已安装」会让人不再去点那个确认
                            r.ok -> if (r.pending) {
                                r.message ?: "已交给系统安装器"
                            } else {
                                "已安装（通道：${viaLabel(r.via)}）"
                            }
                            else -> r.message ?: "安装失败"
                        },
                        isError = !r.ok,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 资源标签：列资源 / 改单条 / 批量替换 ────────────────

    fun setResType(type: String) {
        _state.update { it.copy(resType = type) }
        loadResources()
    }

    fun setResFilter(f: String) = _state.update { it.copy(resFilter = f) }

    /**
     * 列资源。
     *
     * 只把「类型 + 搜索词」交给引擎过滤，不把上万条资源全拉回来 —— 列表本身有 500 条上限，
     * 到上限时提示用户缩小搜索范围，而不是悄悄截断。
     */
    fun loadResources() {
        val project = opened ?: return
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = "读资源表…", message = null, isError = false) }
            try {
                val list = project.resources(s.resType.ifBlank { null }, s.resFilter.ifBlank { null })
                _state.update {
                    it.copy(
                        busy = null,
                        resources = list,
                        message = "列出 ${list.size} 条" +
                            if (list.size >= 500) "（到上限了，用搜索词再缩小一下）" else "",
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 改一条字符串资源，如 `@string/app_name`。 */
    fun setResource(resName: String, value: String) {
        val project = opened ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "改写 $resName…", message = null, isError = false) }
            try {
                val rec = project.setResource(resName, value)
                refreshPatches()
                _state.update { it.copy(busy = null, message = "已改写 $resName（动了 ${rec.target}）") }
                loadResources()   // 列表里的值要跟着变
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 批量替换文案。输入是「每行一条 `旧值=新值`」，`#` 开头的行当注释。
     *
     * 走 `ARSC` 作用域 —— 这是「改文案」的主路径，实测 200 组约 300ms。
     * 走 `BOTH` 会去重写命中的 dex，同样 200 组要 27 秒（见 ReplaceScope 的说明），
     * 所以这里不给用户选：改文案就该只动资源表。
     */
    fun replaceMany(text: String) {
        val project = opened ?: return
        val pairs = parsePairs(text)
        if (pairs.isEmpty()) {
            _state.update {
                it.copy(message = "没解析出规则：每行写成「旧值=新值」，# 开头的行会被忽略", isError = true)
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = "替换 ${pairs.size} 组…", message = null, isError = false) }
            try {
                val t0 = System.currentTimeMillis()
                val records = project.replaceStrings(pairs, ReplaceScope.ARSC)
                val cost = System.currentTimeMillis() - t0
                refreshPatches()
                _state.update {
                    it.copy(
                        busy = null,
                        message = if (records.isEmpty()) {
                            "没有命中的资源文案"
                        } else {
                            "改了 ${records.size} 个条目，耗时 ${cost}ms"
                        },
                        isError = records.isEmpty(),
                    )
                }
                loadResources()
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 概览：改名 / 改版本 ──────────────────────────────────────

    fun onEditLabel(value: String) = _state.update { it.copy(editLabel = value) }

    fun onEditVersionName(value: String) = _state.update { it.copy(editVersionName = value) }

    fun onEditVersionCode(value: String) = _state.update { it.copy(editVersionCode = value) }

    fun onEditMinSdk(value: String) = _state.update { it.copy(editMinSdk = value) }

    fun onEditTargetSdk(value: String) = _state.update { it.copy(editTargetSdk = value) }

    /**
     * 应用概览里的改名 / 改版本。
     *
     * **只提交真正变了的字段**：不然每点一次「应用」都会在改动列表里多出三条记录，
     * 用户想回退时要逐条退，很快就会懒得再用这个功能。
     *
     * 改完重新读一遍 meta —— 应用名和版本都来自清单，不重读的话界面还显示旧值，
     * 而用户看到「没变化」只会再点一次。
     */
    fun applyManifestEdits() {
        val project = opened ?: return
        val s = _state.value
        val meta = s.meta ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "改清单…", message = null, isError = false) }
            try {
                val codeText = s.editVersionCode.trim()
                val code = codeText.toIntOrNull()
                if (codeText.isNotEmpty() && code == null) {
                    throw IllegalArgumentException("版本码必须是整数（系统靠它判断版本新旧）")
                }

                val changed = mutableListOf<String>()
                val newLabel = s.editLabel.trim()
                if (newLabel.isNotEmpty() && newLabel != meta.appLabel) {
                    project.setManifestField(ManifestField.APP_LABEL, newLabel)
                    changed += "应用名"
                }
                val newVersionName = s.editVersionName.trim()
                if (newVersionName.isNotEmpty() && newVersionName != meta.versionName) {
                    project.setManifestField(ManifestField.VERSION_NAME, newVersionName)
                    changed += "版本名"
                }
                if (code != null && code.toLong() != meta.versionCode) {
                    project.setManifestField(ManifestField.VERSION_CODE, code.toString())
                    changed += "版本码"
                }

                val minSdk = s.editMinSdk.trim().toIntOrNull()
                if (minSdk != null && minSdk != meta.minSdk) {
                    project.setManifestField(ManifestField.MIN_SDK, minSdk.toString())
                    // 提高 minSdk 等于放弃老设备，提示里点一下，免得以为没代价
                    changed += if (minSdk > meta.minSdk) {
                        "minSdk $minSdk（不再支持更早的系统）"
                    } else {
                        "minSdk $minSdk"
                    }
                }
                val targetSdk = s.editTargetSdk.trim().toIntOrNull()
                if (targetSdk != null && targetSdk != meta.targetSdk) {
                    project.setManifestField(ManifestField.TARGET_SDK, targetSdk.toString())
                    changed += "targetSdk $targetSdk"
                }

                if (changed.isEmpty()) {
                    _state.update { it.copy(busy = null, message = "没有需要改的（内容没变）") }
                    return@launch
                }

                val fresh = project.meta
                refreshPatches()
                _state.update {
                    it.copy(
                        busy = null,
                        meta = fresh,
                        workspaceState = project.state,
                        editLabel = fresh.appLabel,
                        editVersionName = fresh.versionName,
                        editVersionCode = fresh.versionCode.toString(),
                        editMinSdk = fresh.minSdk.toString(),
                        editTargetSdk = fresh.targetSdk.toString(),
                        message = "已改 ${changed.joinToString("、")}。重打包签名后才生效",
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 换图标：**规划 → 画图 → 应用** 三步。
     *
     * 切成三步是因为两侧能力不可替代：引擎是纯 JVM（有 ARSCLib，能改包结构与资源表），
     * 而画图要用 Android 的 Bitmap。所以引擎说清「每张图多大、内容画在哪个范围」，
     * UI 只负责缩放居中 —— 尺寸规则只有一处定义，不会两边各写一份。
     */
    fun replaceIcon(uri: Uri) {
        val project = opened ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "换图标…", message = null, isError = false) }
            try {
                val plan = project.planIconReplace()
                if (plan.isEmpty) {
                    throw NoSuchElementException(
                        "这个包的图标换不了：没能规划出要替换的图。" +
                            "它的图标可能是纯色或用主题指定的，包里没有任何可用图像",
                    )
                }

                val src = copyToCache(uri, "icon-source")
                // 画图是 CPU 活（裁切 + 缩放 + 合成 5 张），别占着主线程
                val rendered = withContext(Dispatchers.Default) { IconReplacer.render(src, plan) }

                val records = project.applyIconReplace(rendered)
                refreshPatches()

                _state.update {
                    it.copy(
                        busy = null,
                        workspaceState = project.state,
                        message = "换了 ${records.size} 个条目（${plan.renders.size} 个密度）" +
                            plan.notes.lastOrNull()?.let { "：$it" }.orEmpty() +
                            "。重打包签名后装机看效果",
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 导出分析报告到用户选的位置。
     *
     * **现算现写，不缓存报告文本**：一次渲染是毫秒级，缓存反而会造出
     * 「包改了、导出的还是上一份」这种很难察觉的错。
     */
    fun exportReport(uri: Uri) {
        val s = _state.value
        val meta = s.meta
        if (meta == null) {
            _state.update { it.copy(message = "还没有打开任何包", isError = true) }
            return
        }

        val text = ApkReport.toMarkdown(meta, s.entryCount, s.sourceName)
        viewModelScope.launch {
            _state.update { it.copy(busy = "导出报告…", message = null, isError = false) }
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("写不进去（对方应用没给写入权限？）")
                }
                _state.update { it.copy(busy = null, message = "报告已导出：${text.length} 字符") }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    private fun parsePairs(text: String): List<StringReplacement> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                // `=` 在开头或没有 `=` 的行不算规则（空白的「旧值」会把所有值都改坏）
                if (i <= 0) null else StringReplacement(line.substring(0, i), line.substring(i + 1))
            }

    // ── 文件标签：列条目 / 替换条目 / 删除条目 ──────────────

    fun setEntryFilter(f: String) = _state.update { it.copy(entryFilter = f) }

    /**
     * 列包内条目。
     *
     * 过滤是**路径前缀**语义（引擎那边就是 `startsWith`），所以填 `res/` 看资源目录、
     * 填 `lib/` 看 native 库 —— 这比子串匹配更贴合「按目录看」的用法。
     */
    fun loadEntries() {
        val project = opened ?: return
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = "列条目…", message = null, isError = false) }
            try {
                val list = project.list(s.entryFilter.ifBlank { null })
                _state.update {
                    it.copy(busy = null, entries = list, message = "列了 ${list.size} 个条目")
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 删除条目。它不是立刻消失 —— 是记在覆盖层里，重打包时跳过。 */
    fun deleteEntry(path: String) {
        val project = opened ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "删除 $path…", message = null, isError = false) }
            try {
                project.deleteEntry(path)
                refreshPatches()
                loadEntries()
                _state.update {
                    it.copy(message = "已标记删除 $path（重打包后才真正生效，可在「改动」里回退）")
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 用设备上的一个文件替换包内条目（图标、配置、任意资源都走这条路）。 */
    fun replaceEntry(path: String, uri: Uri) {
        val project = opened ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "替换 $path…", message = null, isError = false) }
            try {
                // 先落到缓存再用：content:// 只能顺序读一次，而写入可能要重试
                val src = copyToCache(uri, path.substringAfterLast('/'))
                src.inputStream().use { project.writeEntry(path, it) }
                refreshPatches()
                loadEntries()
                _state.update {
                    it.copy(message = "已替换 $path（重打包后生效）")
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 改动管理 ─────────────────────────────────────────────

    fun revert(patchId: String) {
        val project = opened ?: return
        viewModelScope.launch {
            try {
                project.revert(patchId)
                refreshPatches()
                _state.update {
                    it.copy(workspaceState = project.state, message = "已回退这条改动", isError = false)
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    private suspend fun refreshPatches() {
        val project = opened ?: return
        val list = project.patches()
        _state.update { it.copy(patches = list, workspaceState = project.state) }
    }

    private fun fail(t: Throwable) {
        _state.update {
            it.copy(busy = null, message = t.message ?: t::class.java.simpleName, isError = true)
        }
    }

    // ── 杂项 ─────────────────────────────────────────────────

    private suspend fun queryDisplayName(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "input.apk"
    }

    private suspend fun copyToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "opened").apply { mkdirs() }
        val out = File(dir, name)
        app.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { ins.copyTo(it) }
        } ?: throw IllegalStateException("无法读取该文件（可能是云盘占位文件或权限不足）")
        out
    }

    /** 把异常翻译成「下一步该干什么」，而不是把堆栈丢给用户。 */
    private fun hintFor(t: Throwable): String? = when {
        t is NoSuchElementException -> "该 APK 缺少 AndroidManifest.xml，可能不是完整的安装包"
        t.message?.contains("不是文件") == true -> "请选择设备上已下载完成的 .apk 文件，而不是云盘在线文件"
        t.message?.contains("zip", ignoreCase = true) == true -> "文件不是合法的 zip/APK，建议确认扩展名与完整性"
        t is OutOfMemoryError -> "解析这个包时内存不足，先试小一点的包"
        else -> "若该包经过加固，资源表可能不是标准格式；可先只查看基本信息"
    }

    private fun viaLabel(via: InstallVia) = when (via) {
        InstallVia.SHIZUKU -> "Shizuku 静默安装"
        InstallVia.ROOT -> "Root 静默安装"
        InstallVia.INTENT -> "系统安装器（需要你手动确认）"
    }
}
