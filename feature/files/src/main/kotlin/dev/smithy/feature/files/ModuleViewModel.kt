package dev.smithy.feature.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.engine.ModuleChannels
import dev.smithy.fs.ModuleLayout
import dev.smithy.fs.ModuleProp
import dev.smithy.fs.ModuleProject
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
    fun open(zipPath: String) {
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
                        packaged = null,
                        busy = null,
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
