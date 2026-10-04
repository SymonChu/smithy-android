package dev.smithy.feature.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.fs.ZipEditor
import dev.smithy.fs.ZipEntryInfo
import dev.smithy.fs.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 文件列表里的一项。 */
data class FsItem(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long,
    val modified: Long,
) {
    /**
     * 能不能当压缩包打开。
     *
     * 按扩展名粗判（点开失败会报错，不会静默）—— 比读文件头判断快得多，
     * 而且这里只是决定「点了要不要试一下」。
     */
    val maybeZip: Boolean
        get() = !dir && name.substringAfterLast('.', "").lowercase() in ZIP_EXTS

    companion object {
        private val ZIP_EXTS = setOf("zip", "apk", "jar", "apks", "xapk")
    }
}

/** 打开的压缩包的浏览状态。 */
data class ZipUiState(
    val path: String,
    val items: List<ZipEntryInfo>,
    /** 条目 → 改动说明（「替换 1.2KB」「删除」）。攒在内存里，保存时才写。 */
    val changes: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
)

/** 已知的二进制扩展名 —— 这些条目不当文本编辑。 */
private val BINARY_EXTS = setOf(
    "arsc", "dex", "so", "png", "jpg", "jpeg", "webp", "gif", "bmp",
    "ttf", "otf", "woff", "woff2", "ogg", "mp3", "mp4", "wav", "webm", "9",
)

data class FilesUiState(
    val dir: String = "",
    val items: List<FsItem> = emptyList(),
    val zip: ZipUiState? = null,
    val filter: String = "",
    val busy: String? = null,
    val message: String? = null,
    val isError: Boolean = false,

    /** 正在编辑的文本条目（null = 没在编辑）。 */
    val editingPath: String? = null,

    /** 编辑框里的内容（保存前的草稿）。 */
    val editingText: String = "",

    /** 属性面板的数据（null = 没在显示）。 */
    val properties: Properties? = null,
)

/**
 * 文件属性面板的数据。
 *
 * 摘要可能没有（目录就没有），所以单独用 [note] 说明原因，而不是显示三个空字符串 ——
 * 空白会让人以为「算出来就是空的」。
 */
data class Properties(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val digests: Hashing.Digests?,
    val note: String? = null,
)

/**
 * 文件管理 + zip 直改。
 *
 * **为什么从应用的外部私有目录起步**（`getExternalFilesDir`）：走 SAF 能访问用户
 * 任意目录，但要处理 `DocumentFile` 的树形授权、虚拟文档、以及「同一条目有多个 URI」
 * 这些问题 —— 那是独立的一块工作。这里先把「看得见、改得动、存得下」跑通，
 * 而且这个目录不需要任何权限、也不会被系统清理。
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    /** 打开的 zip。改动攒在内存里，保存时才落盘。 */
    private var zipEditor: ZipEditor? = null

    private val rootDir: File = app.getExternalFilesDir(null) ?: app.filesDir

    init {
        openDir(rootDir.absolutePath)
    }

    // ── 目录浏览 ────────────────────────────────────────────────

    fun openDir(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "读取目录…", message = null, isError = false) }
            try {
                val dir = File(path)
                if (!dir.isDirectory) throw IllegalArgumentException("不是目录：$path")
                val items = withContext(Dispatchers.IO) {
                    dir.listFiles().orEmpty().map { f ->
                        FsItem(f.name, f.absolutePath, f.isDirectory, f.length(), f.lastModified())
                    }.sortedWith(compareByDescending<FsItem> { it.dir }.thenBy { it.name.lowercase() })
                }
                _state.update { it.copy(dir = path, items = items, busy = null) }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 回上级。已经在根就什么都不做（不越出起始目录，免得用户迷路）。 */
    fun goUp() {
        val current = File(_state.value.dir)
        val parent = current.parentFile ?: return
        if (!parent.absolutePath.startsWith(rootDir.absolutePath)) {
            _state.update { it.copy(message = "已经到最上层了", isError = false) }
            return
        }
        openDir(parent.absolutePath)
    }

    fun onFilter(text: String) = _state.update { it.copy(filter = text) }

    // ── 属性 / 摘要 / 改名 / 删除 ───────────────────────────────

    fun showProperties(item: FsItem) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "算摘要…", message = null, isError = false) }
            try {
                val props = withContext(Dispatchers.IO) {
                    if (item.dir) {
                        Properties(item.name, item.path, 0, item.modified, null, "目录没有摘要")
                    } else {
                        val d = Hashing.of(File(item.path))
                        Properties(item.name, item.path, d.size, item.modified, d)
                    }
                }
                _state.update { it.copy(busy = null, properties = props) }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun dismissProperties() = _state.update { it.copy(properties = null) }

    /**
     * 给文件改名。
     *
     * 先挡「同名已存在」再动手：`renameTo` 在不同文件系统上行为不一致（有的覆盖、
     * 有的失败），让它自己决定会得到「有时默默覆盖了另一个文件」。
     */
    fun rename(item: FsItem, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            _state.update { it.copy(message = "名字不能为空，也不能带斜杠", isError = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "改名…", message = null, isError = false) }
            try {
                withContext(Dispatchers.IO) {
                    val source = File(item.path)
                    val target = File(source.parentFile, clean)
                    if (target.exists()) throw IllegalArgumentException("$clean 已经存在")
                    if (!source.renameTo(target)) {
                        throw IllegalStateException("改名失败（可能没权限，或跨了存储设备）")
                    }
                }
                _state.update { it.copy(busy = null, message = "已改名为 $clean", isError = false) }
                openDir(_state.value.dir)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 删除。界面上必须先确认 —— 这里是真的删。 */
    fun delete(item: FsItem) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "删除…", message = null, isError = false) }
            try {
                val ok = withContext(Dispatchers.IO) { File(item.path).deleteRecursively() }
                if (!ok) throw IllegalStateException("删除失败（可能没有权限）")
                _state.update { it.copy(busy = null, message = "已删除 ${item.name}", isError = false) }
                openDir(_state.value.dir)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 文本编辑 ──────────────────────────────────────────────

    /**
     * 打开包内一个文本条目来编辑。
     *
     * **只对看起来是文本的条目开放**：apk 里的 xml 是**二进制** XML，当文本编辑只会把它改坏
     * （而且改完装不上）。所以挡一道并说清原因 —— 让用户打开一堆乱码再自己猜发生了什么，
     * 比直接拒绝糟得多。
     */
    fun openText(path: String) {
        val editor = zipEditor ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "读取…", message = null, isError = false) }
            try {
                val bytes = withContext(Dispatchers.IO) { editor.read(path) }
                if (!looksTextual(path, bytes)) {
                    throw IllegalArgumentException(
                        "$path 看着不是文本：apk 里的 xml 是二进制格式（用「工作台」的资源标签改），" +
                            "图和 so 更不能当文本编辑",
                    )
                }
                _state.update {
                    it.copy(busy = null, editingPath = path, editingText = bytes.toString(Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 保存编辑结果到**待写改动**（还没落盘）。 */
    fun saveText(text: String) {
        val editor = zipEditor ?: return
        val path = _state.value.editingPath ?: return
        editor.put(path, text.toByteArray(Charsets.UTF_8))
        _state.update {
            it.copy(
                editingPath = null,
                editingText = "",
                message = "已记下改动（还要点「保存到…」才真正写进包）",
                isError = false,
            )
        }
    }

    fun cancelEdit() = _state.update { it.copy(editingPath = null, editingText = "") }

    /**
     * 粗判一个条目是不是文本。
     *
     * 两道：扩展名（已知二进制类型直接否）+ 内容（有 NUL 字节就当二进制）。
     * 不做严格编码校验 —— 目的是「别让人对着乱码发呆」，不是精确分类。
     */
    private fun looksTextual(path: String, bytes: ByteArray): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_EXTS) return false
        return bytes.none { it == 0.toByte() }
    }

    // ── 打开条目 ────────────────────────────────────────────────

    fun open(item: FsItem) {
        if (item.dir) {
            openDir(item.path)
            return
        }
        if (!item.maybeZip) {
            _state.update {
                it.copy(message = "${item.name}：文本编辑器在下一步做，暂时只能浏览和改压缩包", isError = false)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "打开压缩包…", message = null, isError = false) }
            try {
                closeZip()
                val editor = withContext(Dispatchers.IO) { ZipEditor.open(File(item.path)) }
                zipEditor = editor
                val entries = withContext(Dispatchers.IO) { editor.entries() }
                _state.update { it.copy(zip = ZipUiState(item.path, entries), busy = null) }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun closeZip() {
        runCatching { zipEditor?.close() }
        zipEditor = null
        _state.update { it.copy(zip = null) }
    }

    // ── 改条目 ──────────────────────────────────────────────────

    /** 用设备上的某个文件替换包内条目。 */
    fun replaceEntry(entryPath: String, source: File) {
        viewModelScope.launch {
            val editor = zipEditor ?: return@launch
            _state.update { it.copy(busy = "读取文件…", message = null, isError = false) }
            try {
                val bytes = withContext(Dispatchers.IO) { source.readBytes() }
                editor.put(entryPath, bytes)
                markChange(entryPath, "替换 ${humanSize(bytes.size.toLong())}")
                _state.update { it.copy(busy = null, message = "已替换 $entryPath（还没写盘）") }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 删除包内条目。只记改动，保存时才生效。 */
    fun deleteEntry(entryPath: String) {
        val editor = zipEditor ?: return
        runCatching { editor.delete(entryPath) }
            .onSuccess {
                markChange(entryPath, "删除")
                _state.update { it.copy(message = "已标记删除 $entryPath（还没写盘）", isError = false) }
            }
            .onFailure { fail(it) }
    }

    /** 从用户选的 URI 读文件替换条目（SAF 给的是 URI，不是路径）。 */
    fun replaceEntryFromUri(entryPath: String, uri: android.net.Uri) {
        viewModelScope.launch {
            val editor = zipEditor ?: return@launch
            _state.update { it.copy(busy = "读取文件…", message = null, isError = false) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("读不到这个文件")
                }
                editor.put(entryPath, bytes)
                markChange(entryPath, "替换 ${humanSize(bytes.size.toLong())}")
                _state.update { it.copy(busy = null, message = "已替换 $entryPath（还没写盘）") }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 写到用户选的位置。
     *
     * `ZipEditor` 需要 `File`（要按偏移做对齐），而 SAF 给的是 URI ——
     * 所以先写到应用的缓存文件，再整份拷进目标。多一次拷贝，换来「不依赖 SAF 的
     * 随机写能力」：内容提供者不一定支持 seek，边写边对齐会直接失败。
     */
    fun saveZipToUri(uri: android.net.Uri) {
        viewModelScope.launch {
            val editor = zipEditor ?: return@launch
            val current = _state.value.zip ?: return@launch
            _state.update { it.copy(zip = it.zip?.copy(saving = true), message = null, isError = false) }
            val tmp = File(
                getApplication<Application>().cacheDir,
                "zip-out-${System.currentTimeMillis()}.tmp",
            )
            try {
                val report = withContext(Dispatchers.IO) { editor.writeTo(tmp) }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalArgumentException("写不进这个位置")
                }
                val isApk = current.path.endsWith(".apk", ignoreCase = true)
                _state.update {
                    it.copy(
                        zip = it.zip?.copy(saving = false),
                        message = buildString {
                            append("已保存（搬运 ${report.copied}、写入 ${report.written}、删除 ${report.deleted}）")
                            if (isApk) append("。改过内容的 apk 签名已失效，要重签才能安装")
                        },
                        isError = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(zip = it.zip?.copy(saving = false)) }
                fail(t)
            } finally {
                tmp.delete()
            }
        }
    }

    /** 撤回某个条目的改动。改动还没写盘，撤回就是把它从待写列表里去掉。 */
    fun undoEntry(entryPath: String) {
        val editor = zipEditor ?: return
        editor.revert(entryPath)
        _state.update { s ->
            val zip = s.zip ?: return@update s
            s.copy(
                zip = zip.copy(changes = zip.changes - entryPath),
                message = "已撤销 $entryPath 的改动",
                isError = false,
            )
        }
    }

    /**
     * 保存成新文件。
     *
     * **不覆盖原包**：写一半失败会把原文件毁掉。保存后如果产物是 apk，
     * 界面会提示「签名已失效，要重签才能装」—— 动过压缩包内容的 apk 一定装不上。
     */
    fun saveZipAs(target: File) {
        viewModelScope.launch {
            val editor = zipEditor ?: return@launch
            val current = _state.value.zip ?: return@launch
            _state.update { it.copy(zip = it.zip?.copy(saving = true), message = null, isError = false) }
            try {
                val report = withContext(Dispatchers.IO) { editor.writeTo(target) }
                val isApk = target.name.endsWith(".apk", ignoreCase = true)
                _state.update {
                    it.copy(
                        zip = it.zip?.copy(saving = false),
                        message = buildString {
                            append("已保存到 ${target.absolutePath}")
                            append("（搬运 ${report.copied}、写入 ${report.written}、删除 ${report.deleted}）")
                            if (isApk) append("。注意：改过内容的 apk 签名已失效，要重签才能安装")
                        },
                        isError = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(zip = it.zip?.copy(saving = false)) }
                fail(t)
            }
        }
    }

    private fun markChange(entryPath: String, note: String) {
        _state.update { s ->
            val zip = s.zip ?: return@update s
            s.copy(zip = zip.copy(changes = zip.changes + (entryPath to note)))
        }
    }

    private fun fail(t: Throwable) {
        _state.update {
            it.copy(
                busy = null,
                zip = it.zip?.copy(saving = false),
                message = t.message ?: t::class.java.simpleName,
                isError = true,
            )
        }
    }

    /** 相对起始目录的路径，界面显示用（绝对路径太长）。 */
    fun relative(path: String): String =
        path.removePrefix(rootDir.absolutePath).trimStart('/').ifEmpty { "/" }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
