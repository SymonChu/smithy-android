package dev.smithy.feature.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.fs.ZipEditor
import dev.smithy.fs.RenameRules
import dev.smithy.fs.RenamePlan
import dev.smithy.fs.planRename
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

/**
 * 列表排序方式。
 *
 * 目录永远排在文件前面（那是文件管理器的通例：先看到能进去的东西），
 * 只在同一类内部按 [SortBy] 排。
 */
enum class SortBy(val label: String) {
    NAME("名字"),
    SIZE("大小"),
    TIME("时间"),
}

/**
 * 待粘贴的条目。
 *
 * [cut] 为 true 是「剪切」（粘贴时移动并删源），false 是「复制」。
 */
data class Clipboard(val paths: List<String>, val cut: Boolean)

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

    /** 是否处于多选状态。 */
    val selecting: Boolean = false,

    /** 选中的条目路径。 */
    val selected: Set<String> = emptySet(),

    /** 批量改名规则。 */
    val renameRules: RenameRules = RenameRules(),

    /** 预览计划（null = 没有可改的）。 */
    val renamePlan: RenamePlan? = null,

    /**
     * 粘贴板里的东西（null = 没有）。有它的时候，目录栏上会出现「粘贴」。
     */
    val clipboard: Clipboard? = null,

    /** 排序方式。目录恒在文件之前，这里只管同一类内部的顺序。 */
    val sortBy: SortBy = SortBy.NAME,

    /**
     * 是否显示以 `.` 开头的条目。
     *
     * **默认关**：Android 的 `/sdcard` 下有 `.thumbnails` 之类一堆东西，
     * 默认显示会把正常内容淹掉。但 `.nomedia`、`.gitignore` 这类又是要改的对象，
     * 所以给个开关而不是一律隐藏。
     */
    val showHidden: Boolean = false,

    /**
     * root 模式。
     *
     * 开着的时候浏览的是**整个文件系统**（系统分区、别的应用的数据、`/data/adb/modules`），
     * 全部经 root shell；关着只能看应用私有目录。界面必须能一眼看出当前在哪种模式 ——
     * 两种模式下同一个路径名的含义完全不同，看不出来就会改错东西。
     */
    val rootMode: Boolean = false,
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
        // 先落在能立刻用的地方，然后**后台**问一次 root —— 没有 root 的人不该看到
        // 一个卡住的界面，有 root 的人也不该先看到一堆进不去的目录。
        //
        // 有 root 就落到 `/sdcard`：那是用户真正常去的地方（下载、聊天记录、
        // 别人发来的包）。而不是「先给你个受限的，等你撞墙了再自己找开关切」。
        openDir(rootDir.absolutePath)
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) { RootFs.isGranted() }
            if (granted) {
                _state.update { it.copy(rootMode = true) }
                openDir("/sdcard")
            }
        }
    }

    // ── 复制 / 移动 / 删除 ─────────────────────────────────────

    /**
     * 剪贴板：待粘贴的条目。
     *
     * 用「复制/剪切 → 切到目标目录 → 粘贴」这套标准交互，而不是「点复制然后弹窗选目录」：
     * 后者要在一个对话框里浏览整个文件系统，既难做又难用，而用户对前者早就有肌肉记忆。
     */
    fun copySelected() {
        val s = _state.value
        if (s.selected.isEmpty()) return
        val n = s.selected.size
        _state.update { it.copy(clipboard = Clipboard(s.selected.toList(), cut = false), selecting = false, selected = emptySet(), message = "已复制 $n 项，切到目标目录后点粘贴", isError = false) }
    }

    fun cutSelected() {
        val s = _state.value
        if (s.selected.isEmpty()) return
        val n = s.selected.size
        _state.update { it.copy(clipboard = Clipboard(s.selected.toList(), cut = true), selecting = false, selected = emptySet(), message = "已剪切 $n 项，切到目标目录后点粘贴", isError = false) }
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null, message = "已取消粘贴板") }

    /**
     * 把剪贴板里的东西贴到当前目录。
     *
     * **同名先问，不覆盖**：粘贴是最容易毁数据的一步（目标目录里往往已经有同名文件），
     * 所以撞名一律跳过并报出来，而不是默默覆盖。跳过而不是中止 —— 十个里撞一个，
     * 另外九个该贴成功。
     */
    fun paste() {
        val s = _state.value
        val clip = s.clipboard ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = if (clip.cut) "移动中…" else "复制中…", message = null, isError = false) }
            try {
                val result = withContext(Dispatchers.IO) {
                    var ok = 0
                    val skipped = mutableListOf<String>()
                    clip.paths.forEach { from ->
                        val name = from.trimEnd('/').substringAfterLast('/')
                        val to = if (s.dir.endsWith("/")) s.dir + name else "${s.dir}/$name"
                        if (from == to) { skipped += name; return@forEach }
                        val done = if (s.rootMode) {
                            RootFs.transfer(from, to, move = clip.cut)
                        } else {
                            runCatching {
                                val src = File(from)
                                val dst = File(to)
                                if (dst.exists()) return@runCatching false
                                if (clip.cut) src.renameTo(dst)
                                else src.copyRecursively(dst, overwrite = false).let { true }
                            }.getOrDefault(false)
                        }
                        if (done) ok++ else skipped += name
                    }
                    ok to skipped
                }
                val (ok, skipped) = result
                _state.update {
                    it.copy(
                        busy = null,
                        // 剪切成功后清掉剪贴板；复制可以留着（常要贴到多处）
                        clipboard = if (clip.cut && skipped.isEmpty()) null else it.clipboard,
                        message = buildString {
                            append(if (clip.cut) "移动 $ok 项" else "复制 $ok 项")
                            if (skipped.isNotEmpty()) append("，跳过 ${skipped.size} 项（目标已存在或同名）：${skipped.take(3).joinToString("、")}")
                        },
                        isError = skipped.isNotEmpty(),
                    )
                }
                openDir(s.dir)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 删除选中的条目。
     *
     * **界面上必须先确认**（这类动作没有回收站，删了就没了）。root 模式走 shell。
     */
    fun deleteSelected() {
        val s = _state.value
        if (s.selected.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "删除中…", message = null, isError = false) }
            try {
                val result = withContext(Dispatchers.IO) {
                    var ok = 0
                    val failed = mutableListOf<String>()
                    s.selected.forEach { path ->
                        val done = if (s.rootMode) {
                            RootFs.delete(path, s.items.firstOrNull { it.path == path }?.dir ?: false)
                        } else {
                            runCatching { File(path).deleteRecursively() }.getOrDefault(false)
                        }
                        if (done) ok++ else failed += path.substringAfterLast('/')
                    }
                    ok to failed
                }
                val (ok, failed) = result
                _state.update {
                    it.copy(
                        busy = null,
                        selecting = false,
                        selected = emptySet(),
                        message = buildString {
                            append("删除 $ok 项")
                            if (failed.isNotEmpty()) append("，${failed.size} 项失败：${failed.take(3).joinToString("、")}")
                        },
                        isError = failed.isNotEmpty(),
                    )
                }
                openDir(s.dir)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 目录浏览 ────────────────────────────────────────────────

    fun openDir(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "读取目录…", message = null, isError = false) }
            try {
                val items = withContext(Dispatchers.IO) {
                    // **两种访问方式在这里分流**：普通模式只能看应用私有目录，
                    // root 模式看整个文件系统。别处的浏览逻辑（筛选/多选/排序）两者共用。
                    if (_state.value.rootMode) {
                        RootFs.list(path)
                    } else {
                        val dir = File(path)
                        if (!dir.isDirectory) throw IllegalArgumentException("不是目录：$path")
                        dir.listFiles().orEmpty().map { f ->
                            FsItem(f.name, f.absolutePath, f.isDirectory, f.length(), f.lastModified())
                        }
                    }.let { list ->
                        // 先过滤隐藏项，再排序 —— 排序前过滤能少排一批，
                        // 而且顺序反了会把「隐藏文件」也算进「全选」的语义里
                        if (_state.value.showHidden) list else list.filter { !it.name.startsWith(".") }
                    }.sortedWith(
                        // 目录恒在前（文件管理器的通例：先看到能进去的东西），
                        // 同一类内部才按所选方式排
                        compareByDescending<FsItem> { it.dir }.thenBy {
                            when (_state.value.sortBy) {
                                SortBy.NAME -> it.name.lowercase()
                                // 大小/时间都是降序（大的、新的在前）更符合直觉，
                                // 用「补零的字符串」实现：比较器要求同类型，转字符串最省事
                                SortBy.SIZE -> "%020d".format(-it.size)
                                SortBy.TIME -> "%020d".format(-it.modified)
                            }
                        },
                    )
                }
                _state.update { it.copy(dir = path, items = items, busy = null) }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 切换 root 模式。
     *
     * 切过去之前先**确认授权**（libsu 会弹对话框），拿不到就留在普通模式并说清原因 ——
     * 而不是切过去、列出一个空目录，让人以为是自己路径写错了。
     */
    fun toggleRoot() {
        val s = _state.value
        if (s.rootMode) {
            _state.update { it.copy(rootMode = false, message = "已回到普通模式（只能看应用私有目录）") }
            openDir(rootDir.absolutePath)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "请求 root…", message = null, isError = false) }
            val granted = withContext(Dispatchers.IO) { RootFs.isGranted() }
            if (!granted) {
                _state.update {
                    it.copy(
                        busy = null,
                        isError = true,
                        message = "没拿到 root 授权：设备没 root，或者刚才那个对话框被拒了",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(rootMode = true, busy = null, message = "root 模式：整个文件系统都能进")
            }
            openDir("/")
        }
    }

    /** 回上级。普通模式不越出起始目录；root 模式到 `/` 为止。 */
    fun goUp() {
        val s = _state.value
        if (s.rootMode) {
            if (s.dir == "/" || s.dir.isEmpty()) {
                _state.update { it.copy(message = "已经到最上层了", isError = false) }
                return
            }
            openDir(s.dir.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" })
            return
        }
        val current = File(s.dir)
        val parent = current.parentFile ?: return
        if (!parent.absolutePath.startsWith(rootDir.absolutePath)) {
            _state.update { it.copy(message = "已经到最上层了", isError = false) }
            return
        }
        openDir(parent.absolutePath)
    }

    /**
     * 跳到任意路径（面包屑、快捷入口用）。
     *
     * 越界检查只在**非 root** 模式下做 —— 有 root 时 `/` 就是边界，没有更上面的东西。
     */
    fun jumpTo(path: String) {
        if (!_state.value.rootMode && !path.startsWith(rootDir.absolutePath)) {
            _state.update { it.copy(message = "这个位置需要 root（顶部可切换）", isError = true) }
            return
        }
        openDir(path)
    }

    fun onFilter(text: String) = _state.update { it.copy(filter = text) }

    /** 换排序方式（只影响当前列表的顺序，不重新读盘）。 */
    fun setSort(by: SortBy) {
        _state.update { it.copy(sortBy = by) }
        openDir(_state.value.dir)
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        openDir(_state.value.dir)
    }

    // ── 新建 ───────────────────────────────────────────────────

    /**
     * 在当前目录新建文件夹。
     *
     * **已存在就报错，不静默通过**：`mkdir` 对已存在的目录返回失败，
     * 如果当成成功，用户会以为建了、其实什么都没发生（而他才刚看到「成功」）。
     */
    fun mkdir(name: String) {
        val s = _state.value
        if (name.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "新建文件夹…", message = null, isError = false) }
            try {
                val target = joinPath(s.dir, name)
                val ok = withContext(Dispatchers.IO) {
                    if (s.rootMode) RootFs.mkdir(target)
                    else File(target).mkdir()
                }
                _state.update {
                    it.copy(
                        busy = null,
                        message = if (ok) "已新建文件夹 $name" else "新建失败：「$name」可能已经存在",
                        isError = !ok,
                    )
                }
                openDir(s.dir)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 新建空文件。 */
    fun touch(name: String) {
        val s = _state.value
        if (name.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "新建文件…", message = null, isError = false) }
            try {
                val target = joinPath(s.dir, name)
                val ok = withContext(Dispatchers.IO) {
                    if (s.rootMode) RootFs.touch(target)
                    else runCatching { File(target).createNewFile() }.getOrDefault(false)
                }
                _state.update {
                    it.copy(
                        busy = null,
                        message = if (ok) "已新建文件 $name" else "新建失败：「$name」可能已经存在",
                        isError = !ok,
                    )
                }
                openDir(s.dir)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 权限 ───────────────────────────────────────────────────

    /**
     * 改权限（八进制串，如 `644`、`755`）。
     *
     * **只在 root 模式下可用**：普通应用改不了别处的属主与位（连自己沙盒里的某些位都受限），
     * 所以这里不假装能用，界面上会说明。
     */
    fun chmod(path: String, mode: String) {
        val s = _state.value
        if (!s.rootMode) {
            _state.update { it.copy(message = "改权限需要 root 模式", isError = true) }
            return
        }
        // 八进制且 3~4 位：挡掉拼错的输入，而不是把它交给 shell 去猜
        if (!Regex("^[0-7]{3,4}$").matches(mode)) {
            _state.update { it.copy(message = "权限要写八进制，比如 644 / 755 / 0644", isError = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "改权限…", message = null, isError = false) }
            try {
                val ok = withContext(Dispatchers.IO) { RootFs.chmod(path, mode) }
                _state.update {
                    it.copy(
                        busy = null,
                        message = if (ok) "已设为 $mode" else "改权限失败（文件可能在不允许写的挂载上）",
                        isError = !ok,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** 拼一个子路径，处理两边可能已经/没有带斜杠的情况。 */
    private fun joinPath(dir: String, name: String): String =
        (if (dir.endsWith("/")) dir else "$dir/") + name

    // ── 属性 / 摘要 / 改名 / 删除 ────────────────────────────────

    /**
     * 从系统选择器挑的文件导入到私有目录。
     *
     * 这是「改别人给我的包」的入口：应用私有目录里不会凭空出现用户的 apk，
     * 而真实场景要改的包基本都在别处（下载目录、聊天软件收下来的文件）。
     */
    fun importFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "导入…", message = null, isError = false) }
            try {
                val dest = withContext(Dispatchers.IO) {
                    FileImporter.import(
                        getApplication(),
                        uri,
                        getApplication<Application>().cacheDir,
                    )
                }
                _state.update {
                    it.copy(busy = null, message = "已导入 ${dest.name}", isError = false)
                }
                openDir(rootDir.absolutePath)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

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

    // ── 多选与批量改名 ────────────────────────────────────────

    fun toggleSelecting() = _state.update {
        if (it.selecting) {
            it.copy(
                selecting = false,
                selected = emptySet(),
                renamePlan = null,
                renameRules = RenameRules(),
            )
        } else {
            it.copy(selecting = true)
        }
    }

    fun toggleSelected(path: String) = _state.update { s ->
        val next = if (path in s.selected) s.selected - path else s.selected + path
        s.copy(selected = next, renamePlan = recomputePlan(s, next))
    }

    /** 全选**文件**：目录改名会牵动它下面所有内容的路径，批量脚本里容易出事。 */
    fun selectAllFiles() = _state.update { s ->
        val all = s.items.filterNot { it.dir }.map { it.path }.toSet()
        s.copy(selected = all, renamePlan = recomputePlan(s, all))
    }

    fun clearSelection() = _state.update { s ->
        s.copy(selected = emptySet(), renamePlan = recomputePlan(s, emptySet()))
    }

    /**
     * 改规则就立刻重算预览。
     *
     * 不做「先点预览再看」：预览本来就是给人边调边看的，分成两步只会让人对着
     * 一份过期的列表去点应用。
     */
    fun onRulesChange(rules: RenameRules) = _state.update { s ->
        s.copy(renameRules = rules, renamePlan = recomputePlan(s, s.selected, rules))
    }

    private fun recomputePlan(
        s: FilesUiState,
        selected: Set<String>,
        rules: RenameRules = s.renameRules,
    ): RenamePlan? {
        if (selected.isEmpty()) return null
        val byPath = s.items.associateBy { it.path }
        val names = selected.mapNotNull { byPath[it]?.name }
        if (names.isEmpty()) return null
        val selectedNames = names.toSet()
        // 「没被选中的」现有名字：改到它们上面会覆盖掉
        val existing = s.items.map { it.name }.filterNot { it in selectedNames }.toSet()
        val dirFlags = s.items.associate { it.name to it.dir }
        return planRename(names, rules, existing) { dirFlags[it] ?: false }
    }

    /**
     * 执行批量改名。
     *
     * **撞名时整体不动**（界面上「应用」也不会亮）：批量改名不可撤销，而撞名的后果
     * 是**静默覆盖** —— 用户可能几天后才发现某个文件不见了。部分成功比整体不动
     * 更难收拾，所以这里要么全改、要么不改。
     */
    fun applyRename() {
        val plan = _state.value.renamePlan ?: return
        if (!plan.canApply) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "改名中…", message = null, isError = false) }
            try {
                val dir = File(_state.value.dir)
                val (ok, failed) = withContext(Dispatchers.IO) {
                    var done = 0
                    val bad = mutableListOf<String>()
                    plan.changed.forEach { item ->
                        if (File(dir, item.from).renameTo(File(dir, item.to))) done++ else bad += item.from
                    }
                    done to bad
                }
                _state.update {
                    it.copy(
                        busy = null,
                        selecting = false,
                        selected = emptySet(),
                        renamePlan = null,
                        renameRules = RenameRules(),
                        message = if (failed.isEmpty()) {
                            "已改名 $ok 项"
                        } else {
                            "改名 $ok 项，${failed.size} 项失败：${failed.take(3).joinToString()}"
                        },
                        isError = failed.isNotEmpty(),
                    )
                }
                openDir(_state.value.dir)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

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

    /**
     * 界面显示的路径 —— **就是完整路径**。
     *
     * 之前这里把应用私有目录的前缀藏掉（「绝对路径太长」），那是把它当配套功能的思路。
     * 文件管理器里位置必须是绝对的：`/data/adb/modules` 和沙盒里一个同名目录是
     * 完全两回事，只显示后半截会让人以为在改系统文件。
     */
    fun relative(path: String): String = path.ifEmpty { "/" }

    /**
     * 路径的各级，从根开始（面包屑）。
     *
     * 每一级都带自己的完整路径，这样点某一级就能跳过去 —— 比「返回上一级」按很多次
     * 快得多，也是文件管理器该有的手感。
     */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var acc = ""
        return parts.map { p -> acc = "$acc/$p"; acc to p }
    }

    /** 常用的几个位置，做成快捷入口。 */
    fun shortcuts(): List<Pair<String, String>> = listOf(
        "内部存储" to "/sdcard",
        "下载" to "/sdcard/Download",
        "根目录" to "/",
        "模块目录" to "/data/adb/modules",
        "应用数据" to "/data/data",
    )
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
