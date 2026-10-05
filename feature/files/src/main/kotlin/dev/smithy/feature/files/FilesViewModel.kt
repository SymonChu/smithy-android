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
import dev.smithy.fs.HexEdit
import dev.smithy.fs.FsItem
import dev.smithy.fs.FileSearch
import dev.smithy.fs.NavBounds
import dev.smithy.fs.AppExtractor
import dev.smithy.fs.TarReader
import dev.smithy.fs.FtpSession
import dev.smithy.fs.ZipCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 能不能当压缩包打开。
 *
 * 按扩展名粗判（点开失败会报错，不会静默）—— 比读文件头判断快得多，
 * 而且这里只是决定「点了要不要试一下」。
 *
 * 留在这一层而不是 [FsItem] 里：扩展名表是各模块自己的业务常量，
 * 挪进 core 会把那层拽上一堆和它无关的东西。
 */
val FsItem.maybeZip: Boolean
    get() = !dir && name.substringAfterLast('.', "").lowercase() in ZIP_EXTS

/** 能被当「归档」打开的全部扩展名（zip 系 + tar 系）。 */
val FsItem.maybeArchive: Boolean
    get() = maybeZip || TarReader.handles(name)

private val ZIP_EXTS = setOf("zip", "apk", "jar", "apks", "xapk")

/**
 * 搜索状态（null = 没在搜）。
 *
 * **和「筛选」是两件事**：筛选只在当前已列出的条目里按名字过滤（瞬时、不出这一层），
 * 搜索会**进子目录**把匹配项翻出来（可能要几秒、可能几百条）。成本差几个数量级，
 * 所以状态分开存，界面上也分开显示。
 *
 * 搜索期间**不动 [FilesUiState.dir]**：结果可能来自别的目录，而当前目录是用户正在
 * 浏览的位置，把它换掉会让人分不清自己在哪。
 */
data class SearchState(
    /** 从哪个目录开始找。 */
    val root: String,
    val query: String,
    val recursive: Boolean,
    val hits: List<FsItem> = emptyList(),
    /** 已经看过多少条 —— 界面要显示它，否则长扫描看起来像卡死。 */
    val scanned: Int = 0,
    val running: Boolean = true,
    /** 到了上限就收手（不是用户取消）。 */
    val truncated: Boolean = false,
    val cancelled: Boolean = false,
)

/** 打开的压缩包的浏览状态。 */
data class ZipUiState(
    val path: String,
    val items: List<ZipEntryInfo>,
    /** 条目 → 改动说明（「替换 1.2KB」「删除」）。攒在内存里，保存时才写。 */
    val changes: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    /** tar 系（只读浏览，「保存」= 解压整档）。 */
    val isTar: Boolean = false,
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

/** 待粘贴的条目。 [cut] 为 true 是「剪切」（粘贴时移动并删源），false 是「复制」。 */
data class Clipboard(val paths: List<String>, val cut: Boolean)

/** 「从设备提取应用」屏的状态。 */
data class AppPickerState(
    val apps: List<AppExtractor.AppInfo> = emptyList(),
    val loading: Boolean = false,
    /** 关键词过滤（按显示名/包名）。 */
    val query: String = "",
    val includeSystem: Boolean = false,
)

/**
 * 文件管理器的一个标签。每个标签只隔离**浏览位置**：A 标签在 `/data`、
 * B 标签在 `/storage/emulated/0/Download`，切回来还在原地。
 *
 * zip / hex / 属性这些模态状态**不**按标签隔离：它们是「正在进行的操作」，
 * 切标签时关掉（关 zip 顺带丢掉未保存的改动会有提示，见 closeTab）。
 */
data class FileTab(
    val id: Long,
    val dir: String,
) {
    /** 标签上显示的名字：目录最后一段，根目录给「/」。 */
    val label: String
        get() = dir.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
}

/** FTP 连接参数。只存内存：凭据落盘等于把 NAS 密码交给能读应用数据的进程。 */
data class FtpConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
)

/** FTP 浏览会话的状态（null = 没在浏览 FTP）。 */
data class FtpBrowseState(
    val host: String,
    val path: String,
    val entries: List<FtpSession.Entry>,
    val downloading: String? = null,
)

data class FilesUiState(
    val dir: String = "",
    val items: List<FsItem> = emptyList(),
    val zip: ZipUiState? = null,
    /** 搜索状态（null = 没在搜）。进子目录那种，和「本层」那一档都走它。 */
    val search: SearchState? = null,
    val busy: String? = null,
    val message: String? = null,
    val isError: Boolean = false,

    /**
     * 打开的标签。第一个标签永远存在；切换只换「浏览位置」，
     * zip/hex/属性等模态状态是全局的（切标签即收起）。
     */
    val tabs: List<FileTab> = listOf(FileTab(id = 0, dir = "")),
    /** 当前激活的标签 id。 */
    val activeTabId: Long = 0L,

    /** 正在编辑的文本条目（null = 没在编辑）。 */
    val editingPath: String? = null,

    /** 编辑框里的内容（保存前的草稿）。 */
    val editingText: String = "",

    /** 属性 / 摘要面板的数据（null = 没在显示）。 */
    val properties: Properties? = null,

    /** 十六进制查看状态（null = 没在看）。 */
    val hex: HexState? = null,

    /**
     * 能不能读手机上的文件（`/sdcard`）。
     *
     * null = 还没判断过。**这个状态必须显示出来**：看不到手机文件时，用户分不清是
     * 权限没给、路径不对、还是应用坏了 —— 而「打开是空的」是最难自查的状态。
     */
    val storageAccess: StorageAccess.State? = null,

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

    /**
     * 「从设备提取应用」列表（null = 没打开这个界面）。
     *
     * 独立成屏而不是塞进目录列表：那些不是文件系统的条目，多选/删除/
     * 粘贴都不该作用在它们身上 —— 和搜索结果整屏换掉是同一个理由。
     */
    val appPicker: AppPickerState? = null,

    /** FTP 浏览状态（null = 没在浏览 FTP）。整屏替换本地列表，理由同上。 */
    val ftp: FtpBrowseState? = null,

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
    /**
     * 权限（八进制串，如 `644`）、属主、属组。root 模式下才有 —— 普通模式下
     * 读不到就不显示，而不是编一个看着像真的的默认值。
     */
    val mode: String? = null,
    val owner: String? = null,
    val group: String? = null,
)

/**
 * 十六进制查看 / 编辑的状态。
 *
 * **只持有窗口，不持有整个文件** —— 被改的往往是几十上百 MB 的 apk / so，
 * 整读进内存既慢又可能直接 OOM。所以按 [windowStart] 读一窗，翻页再读下一窗。
 */
data class HexState(
    val path: String,
    /** 文件总大小（用来显示「x / y」和判断有没有下一页）。 */
    val size: Long,
    val windowStart: Long,
    val rows: List<HexEdit.Row>,
) {
    /** 这一窗覆盖到的末尾偏移（不含）。 */
    val windowEnd: Long get() = windowStart + rows.sumOf { it.bytes.size.toLong() }

    val hasPrev: Boolean get() = windowStart > 0
    val hasNext: Boolean get() = windowEnd < size
}

/** 一窗读多少字节。4096 = 256 行十六进制，一屏翻几次就到底，也不至于读得太慢。 */
private const val HEX_WINDOW = 4096

/**
 * 用户的共享存储——**按访问模式给不同路径**：
 *
 * - root shell 的挂载命名空间里是 `/storage/emulated/0`（真实挂载点），
 *   `/sdcard` 那条软链在 root `ls` 下有时解析得不如实体路径干净；
 * - 应用进程自己的 File API 视图是 `/storage/sdcard0`（各 ROM 指向
 *   emulated/0 的另一个名字），非 root 时用这个才读得到。
 *
 * 同一块存储的两个名字——不是谁对谁错，是**视图不同**，跟着模式走就都对。
 */
private fun sharedStorageRoot(root: Boolean): String =
    if (root) "/storage/emulated/0" else "/storage/sdcard0"

/**
 * 文件管理 + zip 直改。
 *
 * **浏览是完整的文件管理器**：有 root 就自动用它（落在 `/sdcard`），能进系统分区、
 * 别的应用的数据、`/data/adb/modules`；没有 root 才退回应用私有目录。
 *
 * 早先这里只能看 `getExternalFilesDir` —— 那是把它当「改包的配套功能」时的选择，
 * 而文件管理器该是「打开就能到任何地方」。没走 SAF 的理由仍然成立（要处理
 * `DocumentFile` 的树形授权、虚拟文档、同一条目多 URI），但有 root 就不需要 SAF 了。
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    /** 打开的 zip。改动攒在内存里，保存时才落盘。 */
    private var zipEditor: ZipEditor? = null

    private val rootDir: File = app.getExternalFilesDir(null) ?: app.filesDir

    init {
        // 先落在能立刻用的地方，然后**后台**判断两件事：
        //  1. 有没有 root —— 有就落到 `/sdcard`（用户真正常去的地方：下载、聊天记录、
        //     别人发来的包）
        //  2. 没有 root 的话，有没有「所有文件访问」—— 有也能落到 `/sdcard`
        //
        // 两者都没有时**必须把原因写出来**。看不到手机文件却只显示一个空目录，
        // 用户分不清是权限、路径、还是应用坏了。
        openDir(rootDir.absolutePath)
        viewModelScope.launch { probeAccess() }
    }

    /**
     * 重新判断访问能力。
     *
     * 从系统设置页回来时必须再调一次 —— 「所有文件访问」没有弹窗，
     * 用户是去系统设置里手动打开的，回来时权限可能刚变化。
     */
    fun refreshAccess() {
        viewModelScope.launch { probeAccess() }
    }

    private suspend fun probeAccess() {
        val root = withContext(Dispatchers.IO) { RootFs.isGranted() }
        val access = StorageAccess.state(getApplication())
        val canSeeFiles = root || access == StorageAccess.State.Granted
        val cur = _state.value

        _state.update {
            it.copy(
                rootMode = root,
                storageAccess = access,
                // 原因由界面上的提示条来说（它带「去授权」按钮，是可操作的那一处）。
                // 这里再写一遍就成了同一句话在一屏上出现两次
                message = null,
                isError = false,
            )
        }

        // 已经能看 /sdcard 了却还待在应用沙盒里 → 挪过去。
        // 只在「还在沙盒」时挪，免得把用户自己切到的目录顶掉
        if (canSeeFiles && cur.dir.startsWith(rootDir.absolutePath)) {
            openDir(sharedStorageRoot(root = false))
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
                        val visible = if (_state.value.showHidden) list else list.filter { !it.name.startsWith(".") }
                        // **按路径去重**：挂载命名空间里同一目录可能出现两个同名条目
                        //（/storage 双挂载视图最常见），而列表用 path 当 key ——
                        // 重复 key 会让 LazyColumn 在滑动时直接崩（IllegalArgumentException）
                        visible.distinctBy { it.path }
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
                _state.update { s ->
                    s.copy(
                        dir = path,
                        items = items,
                        busy = null,
                        // 浏览位置记到当前标签上 —— 切走再切回来还在这个目录
                        tabs = s.tabs.map { if (it.id == s.activeTabId) it.copy(dir = path) else it },
                    )
                }
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

    /** 回上级。能不能上去由 [NavBounds] 判断：root 全通，非 root 看拿到什么权限。 */
    fun goUp() {
        val s = _state.value
        // 上一级交给 NavBounds 算，不自己切字符串：`/sdcard` 在字符串上容易被切成空串，
        // 而 openDir("") 什么都不做 —— 又是一次静默失败
        val parent = NavBounds.parentOf(s.dir)
        if (parent == null) {
            _state.update { it.copy(message = "已经是根目录了", isError = false) }
            return
        }
        // 边界按「现在实际够得着什么」判断。
        // 原先这里拿 rootDir（应用私有目录）当边界 —— 那是「浏览只能待在自己目录里」
        // 那个设计的遗留，导致在 /sdcard 下点 ↑ 会被判成「已经到最上层了」，一动不动
        if (!reachable(parent)) {
            _state.update { it.copy(message = "上面那层需要 root（顶部可切换）", isError = true) }
            return
        }
        openDir(parent)
    }

    /**
     * 这个路径在当前权限下够不够得着。判断本身在 [NavBounds] 里（有单测盯着）。
     *
     * 主要是给调用点留个短名字，免得每一处都写四行参数。
     */
    private fun reachable(path: String): Boolean {
        val s = _state.value
        return NavBounds.canReach(
            path = path,
            root = s.rootMode,
            allFilesAccess = s.storageAccess == StorageAccess.State.Granted,
            sandboxDir = rootDir.absolutePath,
        )
    }

    /**
     * 跳到任意路径（面包屑、快捷入口用）。
     *
     * 边界判断走 [NavBounds]，和 [goUp] 是同一个 —— 两处各写一份的结果就是
     * 一个能上去、一个不能，而用户只觉得「返回坏了」。
     */
    fun jumpTo(path: String) {
        // 和 goUp 用同一个边界判断。之前这里也是拿 rootDir 当边界，
        // 于是点面包屑里的上一级会被判成「这个位置需要 root」—— 和 ↑ 一起坏掉，
        // 用户看到的就是「返回没有用，点路径也没用」
        if (!reachable(path)) {
            _state.update { it.copy(message = "这个位置需要 root（顶部可切换）", isError = true) }
            return
        }
        openDir(path)
    }

    // ── 搜索 ─────────────────────────────────────────────────

    private var searchJob: Job? = null

    /**
     * 取消标记。
     *
     * **不用 `job.cancel()` 来停**：那样遍历会以取消异常结束，已经找到的结果就丢了 ——
     * 而用户按「停止」是想停，不是想丢掉已找到的。所以让它看到这个标记后**正常返回**，
     * 带上部分结果。
     */
    @Volatile
    private var searchCancelled = false

    /**
     * 开始搜索。
     *
     * [recursive] 为 false 时只看当前一层（原来「筛选」的行为）—— 保留它是因为
     * 「我大概知道东西就在这层」时快得多，而两种用法共用同一个输入框。
     */
    fun startSearch(query: String, recursive: Boolean) {
        val s = _state.value
        if (query.isBlank()) {
            _state.update { it.copy(search = null) }
            return
        }
        searchCancelled = false
        searchJob?.cancel()
        _state.update { it.copy(search = SearchState(root = s.dir, query = query, recursive = recursive)) }

        val root = s.dir
        val rootMode = s.rootMode
        val showHidden = s.showHidden

        searchJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileSearch.walk(
                    root = root,
                    query = query,
                    // root 模式走 shell、普通模式走 File API。遍历本身不知道这件事
                    listDir = { p -> if (rootMode) RootFs.list(p) else plainList(p) },
                    recursive = recursive,
                    showHidden = showHidden,
                    onProgress = { scanned ->
                        _state.update { st ->
                            st.search?.let { se -> st.copy(search = se.copy(scanned = scanned)) } ?: st
                        }
                    },
                    isCancelled = { searchCancelled },
                )
            }
            _state.update { st ->
                val se = st.search ?: return@update st
                st.copy(
                    search = se.copy(
                        hits = result.hits,
                        scanned = result.scanned,
                        running = false,
                        truncated = result.truncated,
                        cancelled = result.cancelled,
                    ),
                )
            }
        }
    }

    /** 停止（**保住已找到的结果**，并告诉用户是停下来的、不是搜完了）。 */
    fun cancelSearch() {
        searchCancelled = true
    }

    /** 关掉搜索，回到正常浏览。 */
    fun closeSearch() {
        searchCancelled = true
        searchJob?.cancel()
        _state.update { it.copy(search = null) }
    }

    /**
     * 从搜索结果跳过去：目录就进去，文件就进它所在的目录。
     *
     * 不做「直接打开这个文件」：搜索的用途是**找到东西在哪**，而看到它周围有什么
     * 通常才是下一步要做的（改名、复制、看谁在旁边）。
     */
    fun revealHit(item: FsItem) {
        val target = if (item.dir) item.path else item.path.substringBeforeLast('/', item.path)
        closeSearch()
        if (reachable(target)) {
            openDir(target)
        } else {
            _state.update { it.copy(message = "这个位置需要 root（顶部可切换）", isError = true) }
        }
    }

    /**
     * 普通模式的列目录。
     *
     * 和 [openDir] 里那段保持一致（同样的五个字段）；抽出来是给搜索复用 ——
     * 两处各写一份的话，字段一改就会有一处悄悄跟不上。
     */
    private fun plainList(path: String): List<FsItem> = runCatching {
        File(path).listFiles().orEmpty().map { f ->
            FsItem(f.name, f.absolutePath, f.isDirectory, f.length(), f.lastModified())
        }
    }.getOrDefault(emptyList())

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

    /**
     * 改属主/属组（root 专属）。
     *
     * 格式三选一：`root`、`root:shell`、`:shell`（只改组）。和 [chmod] 一样，
     * 挡在 VM 而不是丢给 shell —— 数值 uid 拼错一个数字就静默把文件给了
     * 不存在的 uid，那种「成功」比失败更糟。
     */
    fun chown(path: String, owner: String) {
        val s = _state.value
        if (!s.rootMode) {
            _state.update { it.copy(message = "改属主需要 root 模式", isError = true) }
            return
        }
        if (!Regex("^[A-Za-z_][A-Za-z0-9_.-]*(:[A-Za-z_][A-Za-z0-9_.-]*)?$|^:[A-Za-z_][A-Za-z0-9_.-]*$").matches(owner)) {
            _state.update {
                it.copy(message = "属主写名字，如 root、root:shell 或 :shell（只改组）", isError = true)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "改属主…", message = null, isError = false) }
            try {
                val ok = withContext(Dispatchers.IO) { RootFs.chown(path, owner) }
                _state.update {
                    it.copy(
                        busy = null,
                        message = if (ok) "属主已改为 $owner" else "改属主失败（名字可能不存在，或文件在只读挂载上）",
                        isError = !ok,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ── 从设备提取应用 ─────────────────────────────────────────

    /** 打开「已装应用」列表。 */
    fun openAppPicker() {
        _state.update { it.copy(appPicker = AppPickerState(loading = true), message = null, isError = false) }
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    AppExtractor(getApplication()).list(_state.value.appPicker?.includeSystem ?: false)
                }
                _state.update { it.copy(appPicker = it.appPicker?.copy(apps = apps, loading = false)) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(appPicker = it.appPicker?.copy(loading = false), message = "读应用列表失败：${t.message}", isError = true)
                }
            }
        }
    }

    fun closeAppPicker() {
        _state.update { it.copy(appPicker = null) }
    }

    /** 过滤关键词。只影响显示，不重查 PM。 */
    fun filterApps(q: String) {
        _state.update { it.copy(appPicker = it.appPicker?.copy(query = q)) }
    }

    /** 切「含系统应用」—— 要重查（纯系统应用默认根本没列进来）。 */
    fun toggleSystemApps() {
        val next = !(_state.value.appPicker?.includeSystem ?: false)
        _state.update { it.copy(appPicker = it.appPicker?.copy(includeSystem = next, loading = true)) }
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { AppExtractor(getApplication()).list(next) }
                _state.update { it.copy(appPicker = it.appPicker?.copy(apps = apps, loading = false)) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(appPicker = it.appPicker?.copy(loading = false), message = "读应用列表失败：${t.message}", isError = true)
                }
            }
        }
    }

    /**
     * 把选中的应用 APK 提取到**当前目录**。
     *
     * 当前目录可能是 root 路径 —— 那样要写进 `/data/...`，普通 IO 会失败，
     * root 模式下走 [RootFs.write]。同名不覆盖（提取的是留档，覆盖上次留档
     * 是拿方便换丢东西）。
     */
    fun extractApp(app: AppExtractor.AppInfo) {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = "提取 ${app.label}…", message = null, isError = false) }
            try {
                val outFiles = withContext(Dispatchers.IO) {
                    if (s.rootMode) {
                        // root 路径：拷到缓存再 RootFs.write 过去（和 zip 保存同一条路）
                        val tmpDir = File(getApplication<Application>().cacheDir, "app-extract")
                        tmpDir.mkdirs()
                        val files = AppExtractor(getApplication()).extract(app, tmpDir)
                        files.forEach { f ->
                            val target = File(s.dir, f.name)
                            val ok = RootFs.write(target.absolutePath, f.readBytes())
                            if (!ok) throw IllegalArgumentException("写不进 ${s.dir}（只读挂载？）")
                        }
                        files
                    } else {
                        AppExtractor(getApplication()).extract(app, File(s.dir))
                    }
                }
                closeAppPicker()
                openDir(s.dir)
                _state.update {
                    it.copy(
                        busy = null,
                        message = "已提取 ${outFiles.size} 个文件到当前目录（base" +
                            (if (app.splits.isNotEmpty()) " + ${app.splits.size} split" else "") + "）",
                        isError = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = null, message = "提取失败：${t.message}", isError = true) }
            }
        }
    }

    // ── 标签 ────────────────────────────────────────────────────

    /** 标签 id 发生器（递增，不回收 —— id 唯一性比数字好看重要）。 */
    private var nextTabId = 1L

    /**
     * 新开一个标签，落在 [startDir]（默认内部存储）。
     *
     * 标签数上限 6：再多了标签条要横向滚很久，那不是多标签是收藏夹。
     */
    fun newTab(startDir: String? = null) {
        val start = startDir ?: sharedStorageRoot(_state.value.rootMode)
        val s = _state.value
        if (s.tabs.size >= 6) {
            _state.update { it.copy(message = "最多 6 个标签", isError = false) }
            return
        }
        val id = nextTabId++
        // 切标签是「换位置」：正在进行的模态操作（zip 改动、hex、属性）随旧标签收起
        closeZip()
        _state.update {
            it.copy(
                tabs = it.tabs + FileTab(id = id, dir = start),
                activeTabId = id,
                properties = null,
                search = null,
            )
        }
        openDir(start)
    }

    /** 切到某个标签。该标签记住自己上次在哪个目录，回去时恢复。 */
    fun selectTab(id: Long) {
        val s = _state.value
        val target = s.tabs.find { it.id == id } ?: return
        if (id == s.activeTabId) return
        closeZip()
        _state.update {
            it.copy(activeTabId = id, properties = null, search = null)
        }
        if (target.dir.isNotBlank()) {
            openDir(target.dir)
        } else {
            openDir(sharedStorageRoot(_state.value.rootMode))
        }
    }

    /**
     * 关标签。至少留一个；关的是激活标签就切到相邻那个。
     * zip 里有未保存改动时**先拦截**：丢改动得让用户点头，不能替他决定。
     */
    fun closeTab(id: Long) {
        val s = _state.value
        if (s.tabs.size <= 1) return
        if (s.zip != null && s.zip.changes.isNotEmpty()) {
            _state.update {
                it.copy(message = "压缩包里还有没保存的改动，先保存或关闭压缩包再关标签", isError = true)
            }
            return
        }
        val remaining = s.tabs.filterNot { it.id == id }
        _state.update { it.copy(tabs = remaining) }
        if (id == s.activeTabId) {
            // 切到被关标签的左邻（列表顺序里它前面那个），没有就新的第一个
            val idx = s.tabs.indexOfFirst { it.id == id }
            val neighbor = remaining.getOrNull(idx - 1) ?: remaining.first()
            closeZip()
            _state.update { it.copy(activeTabId = neighbor.id, properties = null, search = null) }
            openDir(neighbor.dir.ifBlank { sharedStorageRoot(_state.value.rootMode) })
        }
    }

    // ── FTP 网络存储 ───────────────────────────────────────────

    private var ftpSession: FtpSession? = null
    private var ftpConfig: FtpConfig? = null

    /**
     * 连接 FTP 并列出根目录。成功后进入 FTP 浏览模式（整屏替换本地列表，
     * 和搜索结果/应用列表同一策略——那些条目不属于本地文件系统）。
     */
    fun connectFtp(host: String, port: Int, user: String, password: String) {
        val cfg = FtpConfig(host.trim(), port, user.trim(), password)
        if (cfg.host.isBlank()) {
            _state.update { it.copy(message = "地址不能为空（如 192.168.1.10）", isError = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "连接 $host…", message = null, isError = false) }
            try {
                val session = withContext(Dispatchers.IO) { FtpSession.connect(cfg.host, cfg.port, cfg.user, cfg.password) }
                val entries = withContext(Dispatchers.IO) { session.list("/") }
                ftpSession?.close()
                ftpSession = session
                ftpConfig = cfg
                _state.update {
                    it.copy(
                        ftp = FtpBrowseState(host = cfg.host, path = "/", entries = entries),
                        busy = null,
                        message = "已连接 ${cfg.host}（FTP 明文传输，仅建议局域网）",
                        isError = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = null, message = "FTP 连接失败：${t.message}", isError = true) }
            }
        }
    }

    /** 进 FTP 的某个目录。 */
    fun ftpOpenDir(path: String) {
        val session = ftpSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "读取 $path…", message = null, isError = false) }
            try {
                val entries = withContext(Dispatchers.IO) { session.list(path) }
                _state.update {
                    it.copy(
                        ftp = it.ftp?.copy(path = path, entries = entries),
                        busy = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = null, message = "列目录失败：${t.message}", isError = true) }
            }
        }
    }

    /** 下载一个远端文件到**当前本地目录**。 */
    fun ftpDownload(entry: FtpSession.Entry) {
        val session = ftpSession ?: return
        val localDir = _state.value.dir
        viewModelScope.launch {
            _state.update { it.copy(ftp = it.ftp?.copy(downloading = entry.name), message = null, isError = false) }
            try {
                val target = withContext(Dispatchers.IO) {
                    var f = File(localDir, entry.name)
                    var i = 1
                    while (f.exists()) {   // 不覆盖：和粘贴/提取同一规则
                        f = File(localDir, entry.name.substringBeforeLast('.') + "($i)." + entry.name.substringAfterLast('.', ""))
                        i++
                    }
                    session.download(entry.path, f, entry.size)
                }
                _state.update {
                    it.copy(
                        ftp = it.ftp?.copy(downloading = null),
                        message = "已下载 ${entry.name} → $localDir",
                        isError = false,
                    )
                }
                openDir(localDir)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(ftp = it.ftp?.copy(downloading = null), message = "下载失败：${t.message}", isError = true)
                }
            }
        }
    }

    /** 断开 FTP，回到本地浏览。 */
    fun disconnectFtp() {
        runCatching { ftpSession?.close() }
        ftpSession = null
        ftpConfig = null
        _state.update { it.copy(ftp = null, message = "已断开 FTP", isError = false) }
    }

    /**
     * 把多选的条目打包成一个 zip，落在当前目录。
     *
     * 覆盖确认放在界面层（弹 ConfirmDialog 后才调这里）；
     * 这里的职责是执行 + 报告。
     */
    fun zipSelected(targetName: String) {
        val s = _state.value
        val selected = s.selected.toList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "打包中…", message = null, isError = false) }
            try {
                val report = withContext(Dispatchers.IO) {
                    val target = File(s.dir, targetName)
                    ZipCreator.create(selected.map { File(it) }, target)
                }
                toggleSelecting()
                openDir(s.dir)
                _state.update {
                    it.copy(
                        busy = null,
                        message = "已打包 ${report.fileCount} 个文件 → ${report.target.name}（${humanSize(report.bytes)}）",
                        isError = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = null, message = "打包失败：${t.message}", isError = true) }
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
                    val root = _state.value.rootMode
                    // 权限/属主只有 root 下读得到（`stat -c` 在普通身份下对系统路径
                    // 要么拒绝要么给错），所以读不到就留 null，界面上不显示这一块
                    val meta = if (root) RootFs.stat(item.path) else null

                    // **摘要必须按模式分流**：`File(path)` 对 `/data/adb/...` 这类
                    // 路径在应用身份下直接失败 —— root 模式下非走 RootFs 不可
                    val digests = if (item.dir) {
                        null
                    } else if (root) {
                        RootFs.read(item.path)?.let { Hashing.ofBytes(it) }
                    } else {
                        runCatching { Hashing.of(File(item.path)) }.getOrNull()
                    }

                    Properties(
                        name = item.name,
                        path = item.path,
                        size = digests?.size ?: item.size,
                        modified = item.modified,
                        digests = digests,
                        note = when {
                            item.dir -> "目录没有摘要"
                            digests == null -> "读不到内容，算不了摘要"
                            else -> null
                        },
                        mode = meta?.first,
                        owner = meta?.second,
                        group = meta?.third,
                    )
                }
                _state.update { it.copy(busy = null, properties = props) }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun dismissProperties() = _state.update { it.copy(properties = null) }

    // ── 十六进制查看 / 编辑 ─────────────────────────────────────

    /** 打开十六进制视图（从文件开头看起）。 */
    fun viewHex(path: String) = loadWindow(path, 0, note = null)

    fun hexClose() = _state.update { it.copy(hex = null) }

    /** 跳到指定偏移。偏移按十六进制解析（见 [HexEdit.parseOffset]）。 */
    fun hexGoto(offsetText: String) {
        val s = _state.value.hex ?: return
        val off = HexEdit.parseOffset(offsetText)
        if (off == null) {
            _state.update {
                it.copy(message = "偏移按十六进制算，比如 1a2b 或 0x1a2b；十进制要写 0d100", isError = true)
            }
            return
        }
        if (off >= s.size) {
            _state.update {
                it.copy(message = "偏移 ${HexEdit.formatOffset(off)} 超出文件末尾（共 ${s.size} 字节）", isError = true)
            }
            return
        }
        loadWindow(s.path, off, note = null)
    }

    /** 翻页。[delta] 为 -1 / +1。 */
    fun hexPage(delta: Int) {
        val s = _state.value.hex ?: return
        val target = s.windowStart + delta.toLong() * HEX_WINDOW
        if (target < 0 || target >= s.size) return
        loadWindow(s.path, target, note = null)
    }

    /**
     * 在 [offsetText] 处覆盖写入 [hexText]。
     *
     * 写完之后**重新从磁盘读那一窗**再显示，而不是就地改内存里的那份副本：
     * 磁盘才是事实。就地改的话，写失败（挂载只读、权限不够）时界面也照样显示
     * 「改好了」—— 而用户会带着这个错误认知去装机。
     */
    fun hexSave(offsetText: String, hexText: String) {
        val s = _state.value.hex ?: return
        val off = HexEdit.parseOffset(offsetText)
        if (off == null) {
            _state.update { it.copy(message = "偏移要写成十六进制，比如 1a2b", isError = true) }
            return
        }
        val patch = HexEdit.parseBytes(hexText)
        if (patch == null) {
            _state.update {
                it.copy(message = "要写的字节写成两位一组，比如 90 90 或 9090（不补零）", isError = true)
            }
            return
        }
        // 等长覆盖才允许：写到文件末尾之外会撑大文件，而变长会移动后面所有字节
        if (off + patch.size > s.size) {
            _state.update {
                it.copy(
                    message = "写到文件外面去了：偏移 ${HexEdit.formatOffset(off)} + ${patch.size} 字节 > 共 ${s.size} 字节。这里只做等长覆盖",
                    isError = true,
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = "写入…", message = null, isError = false) }
            try {
                val root = _state.value.rootMode
                val ok = withContext(Dispatchers.IO) { FileWindow.write(s.path, off, patch, root) }
                if (!ok) {
                    _state.update {
                        it.copy(
                            busy = null,
                            message = "写入失败。root 模式下文件可能在不允许写的挂载上（需要先 remount 成可写）",
                            isError = true,
                        )
                    }
                    return@launch
                }
                loadWindow(
                    path = s.path,
                    start = s.windowStart,
                    note = "已写入 ${patch.size} 字节 @ ${HexEdit.formatOffset(off)}",
                )
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /**
     * 读一窗并渲染。
     *
     * [note] 非 null 时会显示在消息栏 —— 用它把「写入了什么」明确说出来，
     * 而不是让界面悄悄变一下、由用户自己去发现哪几个字节变了。
     */
    private fun loadWindow(path: String, start: Long, note: String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "读文件…", message = note, isError = false) }
            try {
                val root = _state.value.rootMode
                val data = withContext(Dispatchers.IO) {
                    val size = FileWindow.size(path, root)
                    val bytes = if (start == 0L) {
                        FileWindow.read(path, 0, HEX_WINDOW, root)
                    } else {
                        FileWindow.read(path, start, HEX_WINDOW, root)
                    }
                    Triple(size, bytes, start)
                }
                val (size, bytes, from) = data
                if (bytes == null) {
                    _state.update {
                        it.copy(busy = null, message = "读不到 $path 的内容", isError = true)
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        busy = null,
                        hex = HexState(
                            path = path,
                            size = size ?: (from + bytes.size),
                            windowStart = from,
                            rows = HexEdit.rows(bytes, from),
                        ),
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

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
        // 7z / rar：认出来但明确说支持不了 —— 「点了没反应」是最差的失败形态
        if (TarReader.isUnsupportedArchive(item.name)) {
            _state.update {
                it.copy(
                    message = "${item.name}：7z / rar 暂不支持（解析器太重）。zip / apk / jar / tar / tar.gz 都能开",
                    isError = true,
                )
            }
            return
        }
        if (TarReader.handles(item.name)) {
            openTar(item)
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

    /** tar / tar.gz：只读浏览 + 整档解压。复用 zip 的展示（ZipEntryInfo 有同构字段）。 */
    private fun openTar(item: FsItem) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "打开 tar…", message = null, isError = false) }
            try {
                closeZip()
                val entries = withContext(Dispatchers.IO) {
                    TarReader.list(File(item.path)).map {
                        // tar 没有「压缩后大小/加密方式」，同构字段填合理值
                        ZipEntryInfo(it.path, it.size, it.size, 0, -1, it.dir)
                    }
                }
                _state.update {
                    it.copy(
                        zip = ZipUiState(item.path, entries).copy(isTar = true),
                        busy = null,
                        message = "tar 是只读浏览；「保存」会把整档解压到同目录的文件夹里",
                        isError = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = null, message = "打不开这个 tar：${t.message}", isError = true)
                }
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
            val current = _state.value.zip ?: return@launch
            // tar：没有编辑器，「保存」= 解压整档到所选位置附近。SAF 给的是 URI，
            // 而解压要目录 —— tar 走独立的 extractTarAs，别把两套语义搅在一起
            if (current.isTar) {
                _state.update { it.copy(message = "tar 请用「解压到…」（解压需要目录，不是单个文件位置）", isError = true) }
                return@launch
            }
            val editor = zipEditor ?: return@launch
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

    /**
     * tar 的「解压到…」：解到 tar 所在目录下的同名文件夹（`x.tar.gz` → `x/`）。
     *
     * 不弹目录选择器：解压目标 99% 是「就在旁边」，而 SAF 的目录树授权
     * 对 root 路径根本不适用。已存在的文件跳过（和粘贴同一规则）。
     */
    fun extractTarHere() {
        val current = _state.value.zip ?: return
        if (!current.isTar) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "解压 tar…", message = null, isError = false) }
            try {
                val src = File(current.path)
                val baseName = src.name.substringBefore(".tar").ifBlank { "tar-out" }
                val targetDir = File(src.parentFile ?: File(sharedStorageRoot(_state.value.rootMode)), baseName)
                val files = withContext(Dispatchers.IO) {
                    targetDir.mkdirs()
                    TarReader.extractAll(src, targetDir)
                }
                closeZip()
                openDir(targetDir.absolutePath)
                _state.update {
                    it.copy(busy = null, message = "解出 ${files.size} 个文件 → ${targetDir.name}/", isError = false)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = null, message = "解压失败：${t.message}", isError = true) }
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
        val crumbs = parts.map { p -> acc = "$acc/$p"; acc to p }
        // 最前面补一个「根」。之前 `/sdcard/Download` 只有「sdcard / Download」两级 ——
        // 想回 `/` 得一层层点上去，而没有 root 时 `/sdcard` 恰好又是个上不去的顶
        return listOf("/" to "根") + crumbs
    }

    /**
     * 常用的几个位置，做成快捷入口。
     *
     * 「内部存储 / 下载」按当前模式给对应视图的路径（见 [sharedStorageRoot]）——
     * 快捷入口的意义是「点了就能用」，指到一个当前模式下读不到的路径就没意义了。
     */
    fun shortcuts(): List<Pair<String, String>> {
        val sd = sharedStorageRoot(_state.value.rootMode)
        return listOf(
            "内部存储" to sd,
            "下载" to "$sd/Download",
            "根目录" to "/",
            "模块目录" to "/data/adb/modules",
            "应用数据" to "/data/data",
        )
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
