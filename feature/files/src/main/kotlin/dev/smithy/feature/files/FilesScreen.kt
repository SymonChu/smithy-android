package dev.smithy.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.smithy.fs.FsItem
import dev.smithy.fs.humanSize
import dev.smithy.fs.humanTime
import dev.smithy.fs.RenameRules
import androidx.compose.material3.AlertDialog
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithySpacing
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.smithy.fs.ZipEntryInfo

/**
 * 文件管理。
 *
 * 两种视图：**目录**（列文件）与**压缩包内部**（列条目、改条目）。
 * 两者共用同一块区域而不是并排两个窗口 —— 双窗口在手机上会把每边压到十几列字符宽，
 * 反而看不清路径。等平板布局再分栏。
 */
@Composable
fun FilesScreen(
    state: FilesUiState,
    relative: (String) -> String,
    onOpenDir: (String) -> Unit,
    onOpenItem: (FsItem) -> Unit,
    onGoUp: () -> Unit,
    onCloseZip: () -> Unit,
    onReplaceEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onUndoEntry: (String) -> Unit,
    onSaveZip: () -> Unit,
    onOpenText: (String) -> Unit,
    onSaveText: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onShowProperties: (FsItem) -> Unit,
    onRename: (FsItem, String) -> Unit,
    onDelete: (FsItem) -> Unit,
    onDismissProperties: () -> Unit,
    onToggleSelecting: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRulesChange: (RenameRules) -> Unit,
    onApplyRename: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onImport: () -> Unit,
    onToggleRoot: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDeleteSelected: () -> Unit,
    onPaste: () -> Unit,
    onClearClipboard: () -> Unit,
    breadcrumbs: (String) -> List<Pair<String, String>>,
    shortcuts: () -> List<Pair<String, String>>,
    onJumpTo: (String) -> Unit,
    onSort: (SortBy) -> Unit,
    onToggleHidden: () -> Unit,
    onNewFolder: (String) -> Unit,
    onNewFile: (String) -> Unit,
    onChmod: (String, String) -> Unit,
    onViewHex: (FsItem) -> Unit,
    onHexClose: () -> Unit,
    onHexGoto: (String) -> Unit,
    onHexPage: (Int) -> Unit,
    onHexSave: (String, String) -> Unit,
    onRequestAccess: () -> Unit,
    onSearch: (String, Boolean) -> Unit,
    onCancelSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onRevealHit: (FsItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 新建时问名字。用一个本地状态而不是 VM 状态：它只活在这一个对话框里，
    // 放进 VM 会让「取消」也要绕一圈去清
    var newTarget by remember { mutableStateOf<String?>(null) }

    // 筛选框的开关。它是**按需出现**的：常驻一行去服务一个偶尔用的功能，
    // 就是每次浏览都在付的成本
    var searching by remember { mutableStateOf(false) }

    // 搜索框里的字和「要不要进子目录」。
    // 放在这里而不是 VM：搜索是**按按钮触发**的，不是边打边搜 ——
    // 递归扫描可能几秒，每敲一个字扫一遍纯属浪费，结果还会在打字时乱跳
    var searchText by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }
    // 正在改权限的那个路径（null = 没在改）
    var chmodTarget by remember { mutableStateOf<String?>(null) }
    // 编辑中就让编辑器占满整屏：这时用户的心智在「改这个文件」上，
    // 把列表留在旁边只会把行宽挤到看不清
    state.editingPath?.let { path ->
        TextEditorScreen(
            path = path,
            text = state.editingText,
            onSave = onSaveText,
            onCancel = onCancelEdit,
            modifier = modifier,
        )
        return
    }

    // 十六进制视图也占满整屏 —— 它看的是字节，旁边留一张文件列表没有意义，
    // 而且那一列地址本身就够宽了
    state.hex?.let { hex ->
        HexScreen(
            state = hex,
            onClose = onHexClose,
            onGoto = onHexGoto,
            onPage = onHexPage,
            onSave = onHexSave,
            modifier = modifier,
        )
        return
    }

    state.properties?.let { props ->
        PropertiesCard(props, onDismissProperties, onChmod = { chmodTarget = props.path })
    }

    // 改权限。预填当前值 —— 常见操作是在它基础上改一位（比如 644 → 755），
    // 让人从空框开始重敲一遍四位数字，是没必要的
    chmodTarget?.let { path ->
        TextInputDialog(
            title = "改权限（八进制，如 644 / 755）",
            initial = state.properties?.mode ?: "644",
            onConfirm = { mode ->
                chmodTarget = null
                onChmod(path, mode)
            },
            onDismiss = { chmodTarget = null },
        )
    }

    // 新建时问名字。对话框状态放这一屏而不是 VM —— 它只活在这里，
    // 进 VM 的话「取消」还得绕一圈去清它
    newTarget?.let { kind ->
        TextInputDialog(
            title = kind,
            initial = if (kind == "新建文件夹") "新建文件夹" else "新建文件.txt",
            onConfirm = { name ->
                newTarget = null
                if (kind == "新建文件夹") onNewFolder(name) else onNewFile(name)
            },
            onDismiss = { newTarget = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        val zip = state.zip
        // 宽屏（平板 / 横屏）才分栏。手机竖屏并排两栏会把每边压到十几列字符宽，
        // 连路径都显示不全 —— 那比单栏更难用，所以阈值卡在 600dp
        val wide = LocalConfiguration.current.screenWidthDp >= WIDE_DP

        if (wide) {
            // 左右并列：目录一栏、包内容一栏。
            // 这两者是**不同层级**的东西（一个在文件系统里、一个在压缩包内部），
            // 并排比上下叠着更贴近它们的关系，而且互不遮挡 —— 改包时能一直看着文件列表
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f)) {
                                        DirHeader(
                        crumbs = breadcrumbs(state.dir),
                        onJump = onJumpTo,
                        onGoUp = onGoUp,
                        rootMode = state.rootMode,
                        onToggleRoot = onToggleRoot,
                        clipboard = state.clipboard,
                        onPaste = onPaste,
                        onClearClipboard = onClearClipboard,
                        trailing = {
                            MoreMenu(
                                searching = searching,
                                onImport = onImport,
                                onToggleSearch = {
                                    // 关掉搜索时**把结果也清掉**：只藏起搜索条、留着那屏
                                    // 结果的活，用户会以为自己正站在某个目录里
                                    if (searching) {
                                        searchText = ""
                                        onCloseSearch()
                                    }
                                    searching = !searching
                                },
                            )
                        },
                    )
                    val acc = state.storageAccess
                    if (!state.rootMode && acc != null && acc != StorageAccess.State.Granted) {
                        StorageAccessBar(acc, onRequestAccess)
                    }
                    if (searching) {
                SearchBar(
                    text = searchText,
                    recursive = recursive,
                    search = state.search,
                    onText = { searchText = it },
                    onScope = { recursive = it },
                    onRun = { onSearch(searchText, recursive) },
                    onCancel = onCancelSearch,
                    onClose = {
                        searchText = ""
                        searching = false
                        onCloseSearch()
                    },
                )
            }
                    if (state.search == null) ShortcutBar(shortcuts(), onJumpTo)
                    if (state.search == null) {
                        DirToolbar(
                            state.sortBy, state.showHidden, onSort, onToggleHidden,
                            onNewFolder = { newTarget = "新建文件夹" },
                            onNewFile = { newTarget = "新建文件" },
                        )
                    }
                    SelectionBar(
                        state = state,
                        onToggleSelecting = onToggleSelecting,
                        onSelectAll = onSelectAll,
                        onClear = onClearSelection,
                        onRulesChange = onRulesChange,
                        onApply = onApplyRename,
                        onCopy = onCopy,
                        onCut = onCut,
                        onDelete = onDeleteSelected,
                    )
                    DirList(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onOpenItem = onOpenItem,
                        onShowProperties = onShowProperties,
                        onRename = onRename,
                        onDelete = onDelete,
                        onToggleSelected = onToggleSelected,
                        onViewHex = onViewHex,
                        onRevealHit = onRevealHit,
                        onEnterSelection = { item ->
                            // 进多选并选中刚长按的这一项 —— 用户点「多选」时，
                            // 意思就是「从我开始」，再让他自己回头勾一遍是多余的
                            onToggleSelecting()
                            onToggleSelected(item.path)
                        },
                    )
                }
                VerticalDivider()
                Column(Modifier.weight(1f)) {
                    if (zip == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "这一栏显示包内条目\n点左边的 apk / zip 打开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        ZipHeader(zip, state.busy)
                        ZipList(
                            zip,
                            Modifier.weight(1f),
                            onReplaceEntry,
                            onDeleteEntry,
                            onUndoEntry,
                            onOpenText,
                        )
                        ZipActions(zip, onCloseZip, onSaveZip)
                    }
                }
            }
        } else if (zip == null) {
                                DirHeader(
                        crumbs = breadcrumbs(state.dir),
                        onJump = onJumpTo,
                        onGoUp = onGoUp,
                        rootMode = state.rootMode,
                        onToggleRoot = onToggleRoot,
                        clipboard = state.clipboard,
                        onPaste = onPaste,
                        onClearClipboard = onClearClipboard,
                        trailing = {
                            MoreMenu(
                                searching = searching,
                                onImport = onImport,
                                onToggleSearch = {
                                    // 关掉搜索时**把结果也清掉**：只藏起搜索条、留着那屏
                                    // 结果的活，用户会以为自己正站在某个目录里
                                    if (searching) {
                                        searchText = ""
                                        onCloseSearch()
                                    }
                                    searching = !searching
                                },
                            )
                        },
                    )
            val acc = state.storageAccess
            if (!state.rootMode && acc != null && acc != StorageAccess.State.Granted) {
                StorageAccessBar(acc, onRequestAccess)
            }
            if (searching) {
                SearchBar(
                    text = searchText,
                    recursive = recursive,
                    search = state.search,
                    onText = { searchText = it },
                    onScope = { recursive = it },
                    onRun = { onSearch(searchText, recursive) },
                    onCancel = onCancelSearch,
                    onClose = {
                        searchText = ""
                        searching = false
                        onCloseSearch()
                    },
                )
            }
            if (state.search == null) ShortcutBar(shortcuts(), onJumpTo)
            DirToolbar(
                state.sortBy, state.showHidden, onSort, onToggleHidden,
                onNewFolder = { newTarget = "新建文件夹" },
                onNewFile = { newTarget = "新建文件" },
            )
            SelectionBar(
                state = state,
                onToggleSelecting = onToggleSelecting,
                onSelectAll = onSelectAll,
                onClear = onClearSelection,
                onRulesChange = onRulesChange,
                onApply = onApplyRename,
                onCopy = onCopy,
                onCut = onCut,
                onDelete = onDeleteSelected,
            )
            DirList(
                state = state,
                modifier = Modifier.weight(1f),
                onOpenItem = onOpenItem,
                onShowProperties = onShowProperties,
                onRename = onRename,
                onDelete = onDelete,
                onToggleSelected = onToggleSelected,
                onViewHex = onViewHex,
                onRevealHit = onRevealHit,
                        onEnterSelection = { item ->
                    // 进多选并选中刚长按的这一项 —— 用户点「多选」时，
                    // 意思就是「从我开始」，再让他自己回头勾一遍是多余的
                    onToggleSelecting()
                    onToggleSelected(item.path)
                },
            )
        } else {
            ZipHeader(zip, state.busy)
            ZipList(zip, Modifier.weight(1f), onReplaceEntry, onDeleteEntry, onUndoEntry, onOpenText)
            ZipActions(zip, onCloseZip, onSaveZip)
        }

        state.message?.let { MessageBar(it, state.isError) }
    }
}

/** 到这个宽度就分栏（600dp 是手机竖屏与平板的常见分界，也是 Material 窗口尺寸类的边界）。 */
private const val WIDE_DP = 600

@Composable
private fun DirHeader(
    crumbs: List<Pair<String, String>>,
    onJump: (String) -> Unit,
    onGoUp: () -> Unit,
    rootMode: Boolean,
    onToggleRoot: () -> Unit,
    clipboard: Clipboard?,
    onPaste: () -> Unit,
    onClearClipboard: () -> Unit,
    /**
     * 顶栏最右边留给「更多」菜单。
     *
     * 用插槽而不是加一堆参数：菜单自己的开关状态属于调用方，
     * 传成参数的话 DirHeader 会为了一个按钮多知道四五件事
     */
    trailing: @Composable () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        // Column：Surface 只能有一个子元素，而这里要放两行（路径行 + 粘贴提示行）
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            TextButton(onClick = onGoUp) { Text("↑") }
            // 面包屑：每一级都能点。比「返回上一级」按很多次快得多，也是文件管理器该有的手感。
            // 长路径横向滚动 —— 宁可滚，也不要把中间截掉（截掉的往往正是要确认的那一级）
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs.forEachIndexed { i, (full, name) ->
                    if (i > 0) {
                        Text("/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { onJump(full) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            // 当前这一级加粗：长路径滚起来时，得能一眼认出自己在哪
                            fontWeight = if (i == crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            // root 开关放在路径右边：当前在哪种模式必须**一眼看到** ——
            // 两种模式下同一个路径名含义不同（`/data` 在普通模式指应用自己的，
            // 在 root 模式指系统那个），看不出来就会改错东西
            TextButton(onClick = onToggleRoot) {
                Text(if (rootMode) "root ●" else "普通")
            }
            trailing()
            }
            // 粘贴板有东西时，在路径下方单占一行：它是「下一步动作」，位置要显眼 ——
            // 用户刚点了复制/剪切，下一步就是找地方贴，这时不该让他去找按钮
            clipboard?.let { clip ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (clip.cut) "剪贴板：${clip.paths.size} 项（移动）" else "剪贴板：${clip.paths.size} 项（复制）",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onPaste) { Text("粘贴到此处") }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onClearClipboard) { Text("取消") }
                }
            }
        }
    }
}

/**
 * 列表工具：排序、隐藏文件、新建。
 *
 * 和快捷入口分开成一行：那行是「去哪儿」，这行是「这一屏怎么显示、能加什么」。
 * 混在一起会让两件事互相抢位置，而两者都常用。
 */
@Composable
private fun DirToolbar(
    sortBy: SortBy,
    showHidden: Boolean,
    onSort: (SortBy) -> Unit,
    onToggleHidden: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortBy.entries.forEach { by ->
            // 选中项用字重+颜色区分，不用 Chip：那需要 experimental 注解，
            // 而这里要的只是「哪个是当前排序」
            TextButton(onClick = { onSort(by) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text(
                    by.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (by == sortBy) FontWeight.Bold else FontWeight.Normal,
                    color = if (by == sortBy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("|", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = onToggleHidden, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
            Text(
                if (showHidden) "隐藏项显示中" else "隐藏项",
                style = MaterialTheme.typography.labelSmall,
                color = if (showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("|", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = onNewFolder, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
            Text("新建文件夹", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onNewFile, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
            Text("新建文件", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * 看不到手机文件时的提示条。
 *
 * **必须有这一条**：文件管理器打开是空的，用户分不清是权限、路径、还是应用坏了。
 * 把原因和「去哪打开」都摆在眼前，比让他自己猜要省事得多 ——
 * 尤其 Android 11+ 的「所有文件访问」**没有弹窗**，不去系统设置里点开就永远没有。
 */
@Composable
private fun StorageAccessBar(state: StorageAccess.State, onRequest: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                StorageAccess.reasonFor(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRequest) {
                Text(if (state == StorageAccess.State.Blocked) "去应用详情" else "去授权")
            }
        }
    }
}

/**
 * 快捷入口。
 *
 * 文件管理器最常用的几个位置各给一个按钮 —— 每次从 `/` 一层层点下去，
 * 在最常去的那三四个地方是纯粹的浪费时间。
 */
@Composable
private fun ShortcutBar(items: List<Pair<String, String>>, onJump: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (label, path) ->
            OutlinedButton(
                onClick = { onJump(path) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 搜索条。**只在菜单里点开后出现**。
 *
 * 两档范围共用这一个框：`本层` 是瞬时的（就是原来的「筛选」），`含子目录` 才会真的
 * 递归下去。既然叫搜索，**默认给 `含子目录`** —— 用户输入一个文件名的期待就是
 * 「不管它在哪一层」。想快的时候自己切回「本层」。
 *
 * 搜索是**按按钮触发**的，不是边打边搜：递归扫描可能要几秒，
 * 每敲一个字扫一遍纯属浪费，而且结果会在打字过程中乱跳。
 */
@Composable
private fun SearchBar(
    text: String,
    recursive: Boolean,
    search: SearchState?,
    onText: (String) -> Unit,
    onScope: (Boolean) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SmithySpacing.gutter, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜索文件名") },
            )
            Spacer(Modifier.width(8.dp))
            if (search?.running == true) {
                TextButton(onClick = onCancel) { Text("停止") }
            } else {
                TextButton(onClick = onRun) { Text("搜索") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScopeChip("本层", on = !recursive) { onScope(false) }
            ScopeChip("含子目录", on = recursive) { onScope(true) }
            Spacer(Modifier.weight(1f))
            // 扫描中必须有个东西在动，否则几秒的长扫描看起来就是卡死
            val note = when {
                search == null -> ""
                search.running -> "已看 ${search.scanned} 项 · 命中 ${search.hits.size}"
                search.cancelled -> "已停止 · 命中 ${search.hits.size}"
                search.truncated -> "命中 ${search.hits.size}（到上限，可能还有更多）"
                else -> "命中 ${search.hits.size}"
            }
            if (note.isNotEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClose) { Text("关掉") }
        }
    }
}

@Composable
private fun ScopeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 搜索结果。
 *
 * 每行**必须显示它所在的目录**：结果来自不同目录，只显示名字的话，
 * 一堆同名文件（`config.json` 这种）根本分不清哪个是哪个。
 *
 * 点一行是**跳到它所在的目录**，不是直接打开文件 —— 搜索的用途是找到东西在哪，
 * 而看到它周围有什么通常才是下一步要做的。
 */
@Composable
private fun SearchHits(hits: List<FsItem>, onReveal: (FsItem) -> Unit) {
    if (hits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "没有匹配的条目",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(hits, key = { it.path }) { item ->
            Row(
                Modifier.fillMaxWidth().clickable { onReveal(item) }
                    .padding(horizontal = SmithySpacing.rowHorizontal, vertical = SmithySpacing.rowVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (item.dir) "📁 " else "📄 ") + item.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        item.path.substringBeforeLast('/', ""),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = SmithyMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!item.dir) {
                    Text(
                        humanSize(item.size),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = SmithyMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 路径那一行最右的「更多」菜单。
 *
 * 承接原来占一整行的「筛选 + 导入」。收进菜单的理由很简单：
 * 这两件事偶尔才用一次，而那一行是**每次浏览都在付**的成本。
 */
@Composable
private fun MoreMenu(searching: Boolean, onImport: () -> Unit, onToggleSearch: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text("⋮")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("导入文件") },
                onClick = { open = false; onImport() },
            )
            DropdownMenuItem(
                text = { Text(if (searching) "关闭搜索" else "搜索") },
                onClick = { open = false; onToggleSearch() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirList(
    state: FilesUiState,
    modifier: Modifier = Modifier,
    onOpenItem: (FsItem) -> Unit,
    onShowProperties: (FsItem) -> Unit,
    onRename: (FsItem, String) -> Unit,
    onDelete: (FsItem) -> Unit,
    onToggleSelected: (String) -> Unit,
    onViewHex: (FsItem) -> Unit,
    onEnterSelection: (FsItem) -> Unit,
    onRevealHit: (FsItem) -> Unit,
) {
    // 搜索结果显示在这个位置（列表区），但**不是** DirList 的内容：
    // 那些条目不在当前目录，多选/改名/复制都不该作用在它们身上 ——
    // 所以是整屏换掉，不是往列表里塞
    state.search?.let { s ->
        SearchHits(s.hits, onRevealHit)
        return
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<FsItem?>(null) }
    var deleting by remember { mutableStateOf<FsItem?>(null) }
    val filtered = state.items
    if (filtered.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                state.busy ?: "这个目录是空的",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        items(filtered, key = { it.path }) { item ->
            Box {
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                // 选择模式下点击 = 勾选/取消，而不是打开 ——
                                // 否则「想多选」得先退出、再重新进，很别扭
                                if (state.selecting) onToggleSelected(item.path) else onOpenItem(item)
                            },
                            onLongClick = { menuFor = item.path },
                        )
                        .padding(horizontal = SmithySpacing.rowHorizontal, vertical = SmithySpacing.rowVertical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.selecting) {
                        Checkbox(
                            checked = item.path in state.selected,
                            onCheckedChange = { onToggleSelected(item.path) },
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (item.dir) "📁 " else "📄 ") + item.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!item.dir && item.maybeZip) {
                            Text(
                                "可展开",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // 大小单独一列、右对齐、等宽 —— 结构没动，只是把这一列从
                    // 「挤在名字下面那行」挪到右边并对齐：参差的数字扫一眼比不出大小，
                    // 而那正是列个文件列表最常做的事
                    if (!item.dir) {
                        Text(
                            humanSize(item.size),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = SmithyMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuFor == item.path,
                    onDismissRequest = { menuFor = null },
                ) {
                    // 多选从这里进。长按本来就是「我要对这个东西做点什么」的起手式，
                    // 多选是其中一种 —— 别为它常驻一个按钮
                    DropdownMenuItem(
                        text = { Text("多选") },
                        onClick = { menuFor = null; onEnterSelection(item) },
                    )
                    DropdownMenuItem(
                        text = { Text("属性 / 摘要") },
                        onClick = { menuFor = null; onShowProperties(item) },
                    )
                    DropdownMenuItem(
                        text = { Text("改名") },
                        onClick = { menuFor = null; renaming = item },
                    )
                    // 十六进制只对文件有意义（目录没有字节可看）
                    if (!item.dir) {
                        DropdownMenuItem(
                            text = { Text("十六进制") },
                            onClick = { menuFor = null; onViewHex(item) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuFor = null; deleting = item },
                    )
                }
            }
            HorizontalDivider()
        }
    }

    renaming?.let { target ->
        TextInputDialog(
            title = "改名",
            initial = target.name,
            onConfirm = { renaming = null; onRename(target, it) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { target ->
        ConfirmDialog(
            title = "删除 ${target.name}？",
            body = if (target.dir) "目录连同里面的一切都会删掉，不能撤销。" else "不能撤销。",
            confirm = "删除",
            destructive = true,
            onConfirm = { deleting = null; onDelete(target) },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * 多选与批量改名的工具栏 + 预览。
 *
 * 预览**边调边出**，不设「预览」按钮：批量改名不可撤销，让人对着一份过期的
 * 列表点「应用」是最容易出事的地方，而实时预览让「看到的就是要执行的」。
 */
@Composable
private fun SelectionBar(
    state: FilesUiState,
    onToggleSelecting: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRulesChange: (RenameRules) -> Unit,
    onApply: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
) {
    val plan = state.renamePlan
    // 只在多选中显示。非多选时整栏都不该占位置 ——
    // 进多选的入口是**长按文件弹菜单**，而不是一个常驻按钮
    if (!state.selecting) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 这一栏只在多选中才显示（由调用方控制），所以不需要「多选」入口 ——
                // 进多选靠长按文件弹菜单。常驻一个「多选」按钮是让所有人替少数用法付费
                TextButton(onClick = onToggleSelecting) { Text("退出多选") }
                TextButton(onClick = onSelectAll) { Text("全选文件") }
                TextButton(onClick = onClear) { Text("清空") }
                Spacer(Modifier.weight(1f))
                Text("${state.selected.size} 项", style = MaterialTheme.typography.labelLarge)
            }

            // 常用动作排在改名规则前面：它们是「拿选中的东西做点什么」，
            // 而改名规则是一套要花时间调的参数，顺序上不该抢在前面。
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onCopy, enabled = state.selected.isNotEmpty()) { Text("复制") }
                OutlinedButton(onClick = onCut, enabled = state.selected.isNotEmpty()) { Text("剪切") }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = state.selected.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            }
            Spacer(Modifier.height(6.dp))

            RulesEditor(state.renameRules, onRulesChange)

            plan?.let { p ->
                // 撞名时说清后果，而不是只把「应用」变灰 —— 灰按钮不解释为什么
                if (p.conflicts.isNotEmpty()) {
                    Text(
                        "有 ${p.conflicts.size} 个目标名重复或已存在，不能执行：" +
                            p.conflicts.take(3).joinToString() +
                            "（会覆盖掉别的文件）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!p.hasChanges) {
                    Text(
                        "当前规则不会改动任何名字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 预览：只列真正会变的，且最多 8 条（改几百个时列表会淹没界面）
                p.changed.take(8).forEach { item ->
                    Text(
                        "${item.from}  →  ${item.to}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (p.changed.size > 8) {
                    Text(
                        "…以及另外 ${p.changed.size - 8} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Button(onClick = onApply, enabled = p.canApply) {
                    Text("应用（改 ${p.changed.size} 项）")
                }
            }
        }
    }
}

/** 规则编辑：四个字段都直接改模型，预览跟着变。 */
@Composable
private fun RulesEditor(rules: RenameRules, onChange: (RenameRules) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RuleRow("查找", rules.find) { onChange(rules.copy(find = it)) }
        RuleRow("替换为", rules.replaceWith) { onChange(rules.copy(replaceWith = it)) }
        RuleRow("前缀", rules.prefix) { onChange(rules.copy(prefix = it)) }
        RuleRow("后缀", rules.suffix) { onChange(rules.copy(suffix = it)) }
        RuleRow("扩展名", rules.newExtension) { onChange(rules.copy(newExtension = it)) }
        RuleRow("起始编号", rules.numberFrom?.toString() ?: "") { text ->
            // 空串 = 不编号；非数字一律当不编号，不给「改了但没生效」的错觉
            onChange(rules.copy(numberFrom = text.trim().toIntOrNull()))
        }
    }
}

@Composable
private fun RuleRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(64.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 属性 / 摘要卡片。
 *
 * 不用对话框装它：摘要要能**逐行选中复制**（对比包的 MD5 正是主要用途），
 * 而 SHA-256 有 64 个字符，挤在对话框的窄宽度里会折行，抄起来更容易错。
 */
@Composable
private fun PropertiesCard(props: Properties, onDismiss: () -> Unit, onChmod: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("属性", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            PropLine("名字", props.name)
            PropLine("路径", props.path)
            PropLine("大小", humanSize(props.size))
            PropLine("修改时间", humanTime(props.modified))
            // 权限只有 root 下读得到，读不到就整行不显示 —— 编一个「644」出来
            // 会让人以为那就是当前权限
            if (props.mode != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PropLine("权限", "${props.mode}  ${props.owner ?: ""} ${props.group ?: ""}", Modifier.weight(1f))
                    TextButton(onClick = onChmod) { Text("改") }
                }
            }
            props.note?.let { PropLine("摘要", it) }
            props.digests?.let { d ->
                PropLine("MD5", d.md5)
                PropLine("SHA-1", d.sha1)
                PropLine("SHA-256", d.sha256)
            }
        }
    }
}

@Composable
private fun PropLine(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 单输入框对话框（改名用）。 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 确认对话框。
 *
 * 破坏性操作的按钮**写清动作**（「删除」）而不是「确定」——
 * 「确定」是那种点完之后想不起来自己确认了什么的东西。
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirm,
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ZipHeader(zip: ZipUiState, busy: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "包内浏览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                zip.path.substringAfterLast('/') + "  ·  ${zip.items.size} 个条目" +
                    if (zip.changes.isNotEmpty()) "  ·  待写入 ${zip.changes.size} 处" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (busy != null) {
                Text(busy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ZipList(
    zip: ZipUiState,
    modifier: Modifier = Modifier,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUndo: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(zip.items, key = { it.path }) { entry ->
            val change = zip.changes[entry.path]
            ZipRow(entry, change, onReplace, onDelete, onUndo, onEdit)
            HorizontalDivider()
        }
    }
}

@Composable
private fun ZipRow(
    entry: ZipEntryInfo,
    change: String?,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUndo: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            entry.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            buildString {
                append(if (entry.stored) "未压缩" else "已压缩")
                append("  ·  ${humanSize(entry.size)}")
                if (change != null) append("  ·  $change")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (change != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 是不是文本由 VM 读了内容再判断并解释，这里不做扩展名过滤 ——
                // 像 `META-INF/androidx.core.version` 这种没扩展名但确实是文本的会被误杀
                OutlinedButton(onClick = { onEdit(entry.path) }) { Text("编辑") }
                OutlinedButton(onClick = { onReplace(entry.path) }) { Text("替换") }
                OutlinedButton(onClick = { onDelete(entry.path) }) { Text("删除") }
                if (change != null) {
                    TextButton(onClick = { onUndo(entry.path) }) { Text("撤销") }
                }
            }
        }
    }
}

@Composable
private fun ZipActions(zip: ZipUiState, onClose: () -> Unit, onSave: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "改动先攒着，点保存才写盘。**改过的 apk 签名会失效**，要重签才能装",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("关闭") }
                Button(
                    onClick = onSave,
                    enabled = zip.changes.isNotEmpty() && !zip.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (zip.saving) "保存中…" else "另存为…")
                }
            }
        }
    }
}

@Composable
private fun MessageBar(text: String, isError: Boolean) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        )
    }
}
