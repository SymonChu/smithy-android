package dev.smithy.feature.apk

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.engine.ApkMeta
import dev.smithy.engine.ApkProject
import dev.smithy.engine.ApkProjects
import dev.smithy.engine.DexHit
import dev.smithy.engine.DexQuery
import dev.smithy.engine.InstallVia
import dev.smithy.engine.PatchRecord
import dev.smithy.engine.SignConfig
import dev.smithy.engine.WorkspaceState
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

    // ── 改动标签 ──
    val patches: List<PatchRecord> = emptyList(),

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
                _state.value = WorkbenchUiState(
                    phase = Phase.Ready,
                    sourceName = name,
                    meta = project.meta,
                    entryCount = count,
                    workspaceState = project.state,
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
        super.onCleared()
    }

    private suspend fun closeCurrent() {
        opened?.let { runCatching { it.close(keepArtifacts = false) } }
        opened = null
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
                            r.ok -> "已安装（通道：${viaLabel(r.via)}）${r.message?.let { m -> "· $m" } ?: ""}"
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
