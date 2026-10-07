package dev.smithy.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.smithy.fs.FileKind
import dev.smithy.fs.FsItem
import dev.smithy.fs.humanSize
import dev.smithy.fs.humanTime
import dev.smithy.fs.RenameRules
import dev.smithy.fs.RenamePlan
import dev.smithy.design.SmithyCard
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyNumeric
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithyRowTitle
import dev.smithy.design.SmithySectionTitle
import dev.smithy.design.SmithySkeletonList
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithySpacing
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
    onExtractTar: () -> Unit,
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
    onChown: (String, String) -> Unit,
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
    onOpenAppPicker: () -> Unit,
    onCloseAppPicker: () -> Unit,
    onFilterApps: (String) -> Unit,
    onToggleSystemApps: () -> Unit,
    onExtractApp: (dev.smithy.fs.AppExtractor.AppInfo) -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onZipSelected: (String) -> Unit,
    onConnectFtp: (String, Int, String, String) -> Unit,
    onFtpOpenDir: (String) -> Unit,
    onFtpDownload: (dev.smithy.fs.FtpSession.Entry) -> Unit,
    onFtpDisconnect: () -> Unit,
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
    var chownTarget by remember { mutableStateOf<String?>(null) }
    // FTP 连接对话框的开关（连接动作在 FtpDialog 里组装参数后回调）
    var showFtpDialog by remember { mutableStateOf(false) }
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

    // 属性面板用**对话框**承载：它是「看一眼就走」的临时信息，
    // 之前的实现把它裸浮在列表上层 —— 卡片和底下的列表文字叠在一起，
    // 看起来就是「背景透明、什么都看不清」
    state.properties?.let { props ->
        PropertiesDialog(
            props,
            onDismiss = onDismissProperties,
            onChmod = { chmodTarget = props.path },
            onChown = { chownTarget = props.path },
        )
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

    // FTP 连接：参数只进内存（VM 不落盘），匿名登录留空用户名密码即可
    if (showFtpDialog) {
        FtpDialog(
            onConnect = { host, port, user, pass ->
                showFtpDialog = false
                onConnectFtp(host, port, user, pass)
            },
            onDismiss = { showFtpDialog = false },
        )
    }

    // 改属主。预填「当前属主:当前属组」—— 三个合法形态里最常用的是在此基础上改
    chownTarget?.let { path ->
        TextInputDialog(
            title = "改属主（root / root:shell / :shell）",
            initial = listOfNotNull(state.properties?.owner, state.properties?.group).joinToString(":"),
            onConfirm = { owner ->
                chownTarget = null
                onChown(path, owner)
            },
            onDismiss = { chownTarget = null },
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


        // FTP 浏览：整屏替换（远端条目不属于本地文件系统）
        state.ftp?.let { ftp ->
            FtpScreen(
                ftp,
                onOpenDir = onFtpOpenDir,
                onDownload = onFtpDownload,
                onDisconnect = onFtpDisconnect,
            )
            return@Column
        }

        // 「从设备提取应用」：整屏替换（那些条目不属于文件系统，多选/粘贴
        // 不该作用在它们身上 —— 和搜索结果同一策略）
        state.appPicker?.let { picker ->
            AppPickerScreen(
                picker,
                onClose = onCloseAppPicker,
                onQuery = onFilterApps,
                onToggleSystem = onToggleSystemApps,
                onExtract = onExtractApp,
            )
            return@Column
        }
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
                                onExtractApp = onOpenAppPicker,
                                onFtp = { showFtpDialog = true },
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
                        onZip = onZipSelected,
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
                        ZipActions(zip, onCloseZip, onSaveZip, onExtractTar)
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
                                onExtractApp = onOpenAppPicker,
                                onFtp = { showFtpDialog = true },
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
                onZip = onZipSelected,
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
            ZipActions(zip, onCloseZip, onSaveZip, onExtractTar)
        }


        // 标签条放**底部**（消息栏之上、贴着底栏）：子标签是「切换会话」的动作，
        // 拇指最容易够到的位置就是它该在的位置；顶部留给「当前在哪」的路径信息
        TabStrip(
            tabs = state.tabs,
            activeTabId = state.activeTabId,
            onSelect = onSelectTab,
            onClose = onCloseTab,
            onNew = onNewTab,
        )
        // 消息条也走出现/消失动画：它常常在操作后立刻冒出来，
        // 硬切会让人以为是自己刚才误触了哪里
        AnimatedVisibility(
            visible = state.message != null,
            enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
            exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
        ) {
            state.message?.let { MessageBar(it, state.isError) }
        }
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        // Column：Surface 只能有一个子元素，而这里要放两行（路径行 + 粘贴提示行）
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 返回上一级：真图标而不是「↑」字符 —— 字符的字形跟着系统字体走，
                // 大小、粗细、基线都没法控制，和旁边的矢量图标放在一起就是不齐
                SmithyIconButton(
                    icon = SmithyIcons.Up,
                    contentDescription = "返回上一级",
                    onClick = onGoUp,
                )
                // 面包屑：**胶囊链**而不是文字按钮排。每一级都是一个可点的胶囊，
                // 当前这一级填色加粗 —— 长路径滚起来时得能一眼认出自己在哪。
                // 可点区域比旧版（TextButton 的内边距）大，误触更少
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    crumbs.forEachIndexed { i, (full, name) ->
                        if (i > 0) {
                            // 分隔符：没有它，一串胶囊看起来像并列的按钮而不是一条路径
                            Icon(
                                imageVector = SmithyIcons.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                        Surface(
                            onClick = { onJump(full) },
                            color = if (i == crumbs.lastIndex) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(99.dp),
                        ) {
                            Text(
                                name,
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (i == crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == crumbs.lastIndex) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
                // root 开关：保持**一眼看到**（同一个路径名在两种模式下含义不同），
                // 但从文字按钮收成药丸徽章 —— 常亮状态不需要一整块可点区域
                Surface(
                    onClick = onToggleRoot,
                    color = if (rootMode) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(99.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = SmithyIcons.Root,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (rootMode) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (rootMode) "root" else "普通",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (rootMode) FontWeight.Bold else FontWeight.Normal,
                            color = if (rootMode) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                trailing()
            }
            // 粘贴板有东西时，在路径下方单占一行：它是「下一步动作」，位置要显眼 ——
            // 用户刚点了复制/剪切，下一步就是找地方贴，这时不该让他去找按钮
            clipboard?.let { clip ->
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = SmithySpacing.gutter, end = SmithySpacing.gap, bottom = SmithySpacing.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (clip.cut) SmithyIcons.Cut else SmithyIcons.Copy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Text(
                        if (clip.cut) "${clip.paths.size} 项待移动" else "${clip.paths.size} 项待复制",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onPaste) {
                        Icon(SmithyIcons.Paste, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("粘贴到此处")
                    }
                    SmithyIconButton(
                        icon = SmithyIcons.Close,
                        contentDescription = "取消",
                        onClick = onClearClipboard,
                    )
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
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = SmithySpacing.gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 一个静态的排序图标打头：告诉人「这一串是什么」。没有它，那排药丸看起来
        // 像一排并列的筛选按钮，看不出和排序有关
        Icon(
            imageVector = SmithyIcons.Sort,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortBy.entries.forEach { by ->
            ToolbarPill(label = by.label, onClick = { onSort(by) }, active = by == sortBy)
        }
        // 「|」字符分隔符换成真正的分隔线：字符的粗细、高低都跟着字体走，
        // 在药丸中间显不出层次，只显得脏
        VerticalDivider(
            modifier = Modifier.height(18.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        // 隐藏项：图标 + 文字一起变（只变颜色的话，暗色下几乎看不出来）
        ToolbarPill(
            label = if (showHidden) "含隐藏项" else "隐藏项",
            active = showHidden,
            icon = if (showHidden) SmithyIcons.HiddenOn else SmithyIcons.HiddenOff,
            onClick = onToggleHidden,
        )
        VerticalDivider(
            modifier = Modifier.height(18.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        ToolbarPill(
            label = "新建文件夹",
            icon = SmithyIcons.NewFolder,
            onClick = onNewFolder,
        )
        ToolbarPill(
            label = "新建文件",
            icon = SmithyIcons.NewFile,
            onClick = onNewFile,
        )
    }
}

/**
 * 工具条上的药丸。
 *
 * [active] 用 `secondaryContainer` 而不是「字重加粗 + 主色」：加粗会让这一行的文字
 * 宽度变来变去（同一个名字选中前后宽度不同 → 旁边的药丸会跟着挪），而填色不会。
 */
@Composable
private fun ToolbarPill(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val haptics = rememberSmithyHaptics()
    val container by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = SmithyMotion.state(),
        label = "pillContainer",
    )
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = {
            // 灰掉的药丸仍可点会让「为什么没反应」变成一次困惑，但**不震动**、
            // 也不动 —— 用触感明确回一句「现在不行」
            if (!enabled) {
                haptics.warn()
                return@Surface
            }
            haptics.tap()
            onClick()
        },
        color = if (enabled) container else container.copy(alpha = 0.5f),
        shape = RoundedCornerShape(99.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp), tint = content)
                Spacer(Modifier.width(5.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = content)
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
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 警告图标：这一条说的是「你看到的东西不完整」，光靠浅红底色不够醒目，
            // 而它是整个文件页最要紧的一条信息
            Icon(
                imageVector = SmithyIcons.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(SmithySpacing.gap))
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
 * 文件管理器最常用的几个位置各给一个胶囊 —— 每次从 `/` 一层层点下去，
 * 在最常去的那三四个地方是纯粹的浪费时间。
 *
 * 胶囊（小圆角药丸）而不是 OutlinedButton：这行和面包屑贴在一起，
 * 描边按钮的边框会把视线割碎；实心药丸在明暗两套下都更轻。
 */
@Composable
private fun ShortcutBar(items: List<Pair<String, String>>, onJump: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = SmithySpacing.gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
    ) {
        items.forEach { (label, path) ->
            val icon = shortcutIcon(label, path)
            Surface(
                onClick = { onJump(path) },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(99.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 搜索框改圆角药丸 + 无描边：这是页面上唯一的输入框，方角描边的
            // OutlinedTextField 一眼就是「安卓示例工程」的观感
            TextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜索文件名") },
                shape = RoundedCornerShape(99.dp),
                leadingIcon = {
                    Icon(
                        imageVector = SmithyIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        SmithyIconButton(
                            icon = SmithyIcons.ClearAll,
                            contentDescription = "清空",
                            onClick = { onText("") },
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(SmithySpacing.gap))
            if (search?.running == true) {
                Button(onClick = onCancel) {
                    Icon(SmithyIcons.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            } else {
                Button(onClick = onRun, enabled = text.isNotBlank()) {
                    Text("搜索")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = SmithySpacing.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        ) {
            ToolbarPill("本层", { onScope(false) }, active = !recursive)
            ToolbarPill("含子目录", { onScope(true) }, active = recursive)
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
            SmithyIconButton(
                icon = SmithyIcons.Close,
                contentDescription = "关掉搜索",
                onClick = onClose,
            )
        }
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
        SmithyEmptyState(
            icon = SmithyIcons.Search,
            title = "没有匹配的条目",
            hint = "换个关键词，或者把范围切成「含子目录」再搜一次",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(hits, key = { it.path }) { item ->
            val kind = FileKind.of(item.name, item.dir)
            Row(
                Modifier.fillMaxWidth().clickable { onReveal(item) }
                    .defaultMinSize(minHeight = SmithySpacing.rowHeight)
                    .padding(horizontal = SmithySpacing.rowHorizontal, vertical = SmithySpacing.rowVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = kind.icon,
                    contentDescription = null,
                    modifier = Modifier.size(SmithySpacing.iconSize),
                    tint = kind.tint(),
                )
                Spacer(Modifier.width(SmithySpacing.iconGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = SmithyRowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        item.path.substringBeforeLast('/', ""),
                        style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (!item.dir) {
                    Text(
                        humanSize(item.size),
                        style = SmithyNumeric,
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
private fun MoreMenu(
    searching: Boolean,
    onImport: () -> Unit,
    onToggleSearch: () -> Unit,
    onExtractApp: () -> Unit,
    onFtp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // 用真图标替掉「⋮」字符：字符的粗细、垂直位置都跟系统字体走，
        // 和旁边的矢量图标永远对不齐
        SmithyIconButton(
            icon = SmithyIcons.More,
            contentDescription = "更多",
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("导入文件") },
                leadingIcon = { Icon(SmithyIcons.Upload, null, Modifier.size(20.dp)) },
                onClick = { open = false; onImport() },
            )
            DropdownMenuItem(
                text = { Text(if (searching) "关闭搜索" else "搜索文件") },
                leadingIcon = {
                    Icon(
                        if (searching) SmithyIcons.Close else SmithyIcons.Search,
                        null,
                        Modifier.size(20.dp),
                    )
                },
                onClick = { open = false; onToggleSearch() },
            )
            DropdownMenuItem(
                text = { Text("从设备提取应用") },
                leadingIcon = { Icon(SmithyIcons.Apps, null, Modifier.size(20.dp)) },
                onClick = { open = false; onExtractApp() },
            )
            DropdownMenuItem(
                text = { Text("FTP 网络存储") },
                leadingIcon = { Icon(SmithyIcons.Lan, null, Modifier.size(20.dp)) },
                onClick = { open = false; onFtp() },
            )
        }
    }
}

/**
 * 列表行行首的 22dp 槽位。
 *
 * 平时是文件类型图标，进入多选时**原地**交叉淡入一个勾选圈 —— 两种状态占同一个槽位，
 * 所以切换多选时整列文字纹丝不动。把勾选框「插进」行首（M3 的 Checkbox 自带 48dp
 * 触摸区，实打实占宽）会让每一行同时右移，一百行一起跳看起来就是没打磨。
 */
@Composable
private fun FileRowLead(item: FsItem, selecting: Boolean, selected: Boolean) {
    val kind = FileKind.of(item.name, item.dir)
    val markAlpha by animateFloatAsState(
        targetValue = if (selecting) 1f else 0f,
        animationSpec = SmithyMotion.state(),
        label = "markAlpha",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (selecting) 0f else 1f,
        animationSpec = SmithyMotion.state(),
        label = "iconAlpha",
    )
    val tickAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = SmithyMotion.state(),
        label = "tickAlpha",
    )
    val fill by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        animationSpec = SmithyMotion.state(),
        label = "markFill",
    )
    Box(
        Modifier.size(SmithySpacing.iconSize),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            modifier = Modifier.size(SmithySpacing.iconSize).alpha(iconAlpha),
            tint = kind.tint(),
        )
        // 勾选圈始终参与布局（只是透明），这样它出现时不会触发一次重新测量
        Box(
            Modifier
                .size(20.dp)
                .alpha(markAlpha)
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = 1.5.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SmithyIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp).alpha(tickAlpha),
                tint = MaterialTheme.colorScheme.onPrimary,
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
    val haptics = rememberSmithyHaptics()
    val filtered = state.items
    if (filtered.isEmpty()) {
        // 「还没读完」和「这里确实没有」是两件事，原先都用同一行灰字，加载中看起来像出错：
        // 加载给骨架（预告布局），空目录给空态（说明原因 + 下一步）
        if (state.busy != null) {
            SmithySkeletonList(modifier)
        } else {
            SmithyEmptyState(
                icon = SmithyIcons.Files,
                title = "这个目录是空的",
                hint = "上面那排可以新建文件夹 / 新建文件；也可以从别的目录复制过来",
                modifier = modifier,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        items(filtered, key = { it.path }) { item ->
            // animateItem：重命名、删除、排序变化时那一行是**滑**到新位置而不是瞬间跳过去 ——
            // 密集列表里「谁动了」全靠这个
            Box(Modifier.animateItem()) {
                val selected = item.path in state.selected
                // 选中底色也走动画：多选是连续来回操作，硬切会闪
                val rowBg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    animationSpec = SmithyMotion.state(),
                    label = "rowBg",
                )
                Row(
                    Modifier.fillMaxWidth()
                        .background(rowBg)
                        .combinedClickable(
                            onClick = {
                                // 选择模式下点击 = 勾选/取消，而不是打开 ——
                                // 否则「想多选」得先退出、再重新进，很别扭
                                if (state.selecting) onToggleSelected(item.path) else onOpenItem(item)
                            },
                            onLongClick = {
                                // 长按的触感是「菜单要出来了」的第一反馈，比菜单画出来还早
                                haptics.longPress()
                                menuFor = item.path
                            },
                        )
                        .defaultMinSize(minHeight = SmithySpacing.rowHeight)
                        .padding(horizontal = SmithySpacing.rowHorizontal, vertical = SmithySpacing.rowVertical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 行首**只有一个 22dp 槽位**：平时放类型图标，多选时原地换成勾选圈。
                    // 不把勾选框插进去（那会让整列文字在多选开关的一瞬间横向跳一下 ——
                    // 这是最容易看出「没打磨」的地方）
                    FileRowLead(
                        item = item,
                        selecting = state.selecting,
                        selected = selected,
                    )
                    Spacer(Modifier.width(SmithySpacing.iconGap))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = SmithyRowTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                humanTime(item.modified),
                                style = SmithyRowMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // zip/tar 说清「这个能点进去看」—— 光看名字看不出来
                            if (!item.dir && item.maybeZip) {
                                Text(
                                    "  ·  可展开",
                                    style = SmithyRowMeta,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    // 大小单独一列、右对齐、等宽 + 表格数字 —— 参差的数字扫一眼比不出大小，
                    // 而那正是列个文件列表最常做的事
                    if (!item.dir) {
                        Text(
                            humanSize(item.size),
                            style = SmithyNumeric,
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
                        leadingIcon = { Icon(SmithyIcons.SelectAll, null, Modifier.size(20.dp)) },
                        onClick = { menuFor = null; onEnterSelection(item) },
                    )
                    DropdownMenuItem(
                        text = { Text("属性 / 摘要") },
                        leadingIcon = { Icon(SmithyIcons.Info, null, Modifier.size(20.dp)) },
                        onClick = { menuFor = null; onShowProperties(item) },
                    )
                    DropdownMenuItem(
                        text = { Text("改名") },
                        leadingIcon = { Icon(SmithyIcons.Rename, null, Modifier.size(20.dp)) },
                        onClick = { menuFor = null; renaming = item },
                    )
                    // 十六进制只对文件有意义（目录没有字节可看）
                    if (!item.dir) {
                        DropdownMenuItem(
                            text = { Text("十六进制") },
                            leadingIcon = { Icon(SmithyIcons.Hex, null, Modifier.size(20.dp)) },
                            onClick = { menuFor = null; onViewHex(item) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("权限 / 属主") },
                        leadingIcon = { Icon(SmithyIcons.Permission, null, Modifier.size(20.dp)) },
                        onClick = { menuFor = null; onShowProperties(item) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                SmithyIcons.Delete,
                                null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menuFor = null; deleting = item },
                    )
                }
            }
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
    onZip: (String) -> Unit,
) {
    // 整栏的进出做成展开动画：多选是个「模式」，模式切换如果瞬间、无声，
    // 用户会不确定自己是不是真的进了多选
    AnimatedVisibility(
        visible = state.selecting,
        enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
        exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
    ) {
        SelectionBarContent(
            state = state,
            onToggleSelecting = onToggleSelecting,
            onSelectAll = onSelectAll,
            onClear = onClear,
            onRulesChange = onRulesChange,
            onApply = onApply,
            onCopy = onCopy,
            onCut = onCut,
            onDelete = onDelete,
            onZip = onZip,
        )
    }
}

/**
 * 多选栏本体。
 *
 * 从 [SelectionBar] 里拆出来，是为了让它被 `AnimatedVisibility` 包住时不必整体缩进一层；
 * 顺带把「什么时候显示」和「显示什么」分成两个函数，读起来也更清楚。
 */
@Composable
private fun SelectionBarContent(
    state: FilesUiState,
    onToggleSelecting: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRulesChange: (RenameRules) -> Unit,
    onApply: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onZip: (String) -> Unit,
) {
    var zipping by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    val plan = state.renamePlan
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 三个图标 + 一个计数：这一行原本是三个 TextButton，占掉半个屏幕宽，
                // 而它们的含义（退出 / 全选 / 清空）都强到用一个图标就够
                SmithyIconButton(
                    icon = SmithyIcons.Close,
                    contentDescription = "退出多选",
                    onClick = onToggleSelecting,
                )
                SmithyIconButton(
                    icon = SmithyIcons.SelectAll,
                    contentDescription = "全选文件",
                    onClick = onSelectAll,
                )
                SmithyIconButton(
                    icon = SmithyIcons.Deselect,
                    contentDescription = "清空选择",
                    onClick = onClear,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "已选 ${state.selected.size} 项",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Spacer(Modifier.height(SmithySpacing.gap))

            // 动作用药丸而不是描边按钮：描边按钮在这一屏会出现四个，边框把视线割碎；
            // 药丸靠底色区分，能一眼看出「哪个是按下去的」
            val none = state.selected.isEmpty()
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
            ) {
                ToolbarPill("复制", onCopy, icon = SmithyIcons.Copy, enabled = !none)
                ToolbarPill("剪切", onCut, icon = SmithyIcons.Cut, enabled = !none)
                ToolbarPill("打包 zip", { zipping = true }, icon = SmithyIcons.Zip, enabled = !none)
                ToolbarPill(
                    "删除",
                    onDelete,
                    icon = SmithyIcons.Delete,
                    enabled = !none,
                    danger = true,
                )
                // 改名规则默认**收起**：进多选多半是为了复制 / 删除 / 打包，
                // 而六个输入框 + 预览 + 应用按钮是这一屏最重的一块内容
                ToolbarPill(
                    "批量改名",
                    { showRules = !showRules },
                    icon = SmithyIcons.Rename,
                    active = showRules,
                )
            }

            // 打包：问名字（默认 archive.zip），目标已存在时 VM 会拒绝并说明
            if (zipping) {
                TextInputDialog(
                    title = "打包为 zip（存到当前目录）",
                    initial = "archive.zip",
                    onConfirm = { name ->
                        zipping = false
                        onZip(name)
                    },
                    onDismiss = { zipping = false },
                )
            }

            // 改名规则整块**收起**：进多选多半是为了复制 / 删除 / 打包，而六个输入框
            // 加预览加应用按钮是这一屏最重的一块内容，不该一进多选就砸在眼前
            AnimatedVisibility(
                visible = showRules,
                enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
                exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
            ) {
                RenameRulesPanel(
                    rules = state.renameRules,
                    onRulesChange = onRulesChange,
                    plan = plan,
                    onApply = onApply,
                )
            }
        }
    }
}

/**
 * 批量改名的规则与预览。
 *
 * 「预览边调边出」是这块的核心约定，见 [RulesEditor] 的注释 —— 改名的规则是在这一屏
 * 现场试出来的，所以预览必须和输入框在同一个视野里，不能藏进二级页面。
 */
@Composable
private fun RenameRulesPanel(
    rules: RenameRules,
    onRulesChange: (RenameRules) -> Unit,
    plan: RenamePlan?,
    onApply: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = SmithySpacing.gap)) {
        RulesEditor(rules, onRulesChange)

        plan?.let { p ->
            Spacer(Modifier.height(SmithySpacing.gap))
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

            Spacer(Modifier.height(SmithySpacing.gap))
            Button(
                onClick = onApply,
                enabled = p.canApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(SmithyIcons.Rename, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("应用（改 ${p.changed.size} 项）")
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
 * 属性 / 摘要对话框。
 *
 * 曾经是裸浮在列表上层的 Surface —— 卡片和底下的列表文字叠着画，
 * 看起来「背景是透明的、什么都看不清」。改成 AlertDialog：
 * 有自己的不透明 scrim 和窗口背景，内容再长也在对话框里滚动。
 * 摘要仍逐行可选可复制（SelectionContainer 保留）。
 */
@Composable
private fun PropertiesDialog(
    props: Properties,
    onDismiss: () -> Unit,
    onChmod: () -> Unit,
    onChown: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            DialogTitle(icon = SmithyIcons.Info, text = "属性")
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PropLine("名字", props.name)
                PropLine("路径", props.path)
                PropLine("大小", humanSize(props.size))
                PropLine("修改时间", humanTime(props.modified))
                // 权限只有 root 下读得到，读不到就整行不显示 —— 编一个「644」出来
                // 会让人以为那就是当前权限
                if (props.mode != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PropLine("权限", props.mode, Modifier.weight(1f))
                        TextButton(onClick = onChmod) { Text("改权限") }
                    }
                }
                // 属主同理：读不到不显示。改属主是独立入口 —— 和权限分开，
                // 因为常见操作是其一不是其二，合在一起要多敲一段
                if (props.owner != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PropLine("属主", "${props.owner}:${props.group ?: ""}", Modifier.weight(1f))
                        TextButton(onClick = onChown) { Text("改属主") }
                    }
                }
                props.note?.let { PropLine("摘要", it) }
                props.digests?.let { d ->
                    PropLine("MD5", d.md5)
                    PropLine("SHA-1", d.sha1)
                    PropLine("SHA-256", d.sha256)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
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
        shape = MaterialTheme.shapes.extraLarge,
        title = { DialogTitle(icon = SmithyIcons.Rename, text = title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 对话框标题的统一长相：图标 + 文字。
 *
 * 对话框是「突然盖住整屏」的东西，一个图标能让人在半秒内认出这是哪一类操作
 * （属性 / 破坏性 / 连接），而纯文字标题得读一遍才知道。
 */
@Composable
private fun DialogTitle(icon: ImageVector, text: String, tint: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint ?: MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(SmithySpacing.gap))
        Text(text)
    }
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
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            DialogTitle(
                icon = if (destructive) SmithyIcons.Warning else SmithyIcons.Info,
                text = title,
                tint = if (destructive) MaterialTheme.colorScheme.error else null,
            )
        },
        text = { Text(body) },
        confirmButton = {
            // 破坏性操作用**填色**按钮：文字按钮和「取消」长得一样，
            // 而这两个按钮点错的代价并不对称
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ZipHeader(zip: ZipUiState, busy: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = SmithyIcons.Package,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(SmithySpacing.gap))
                Text(
                    "包内浏览",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                zip.path.substringAfterLast('/') + "  ·  ${zip.items.size} 个条目" +
                    if (zip.changes.isNotEmpty()) "  ·  待写入 ${zip.changes.size} 处" else "",
                style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy != null) {
                // 忙碌时给一个转圈：光有文字的话，几秒的解压看起来就是卡死
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Text(
                        busy,
                        style = SmithyRowMeta,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
            // 去分割线：条目靠图标列 + 行高分组，横线在长列表里只是噪音
            Box(Modifier.animateItem()) {
                ZipRow(entry, change, onReplace, onDelete, onUndo, onEdit)
            }
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
    val haptics = rememberSmithyHaptics()
    val name = entry.path.substringAfterLast('/')
    val kind = FileKind.of(name, false)

    Column(
        Modifier.fillMaxWidth()
            .clickable {
                haptics.tap()
                expanded = !expanded
            }
            .padding(horizontal = SmithySpacing.rowHorizontal, vertical = SmithySpacing.rowVertical),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = kind.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (change != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(SmithySpacing.iconGap))
            Text(
                entry.path,
                style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                humanSize(entry.size),
                style = SmithyNumeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 展开箭头会转：不转的话，展开后箭头还指着右边，看起来像「还能再点进去」
            Icon(
                imageVector = SmithyIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            buildString {
                append(if (entry.stored) "未压缩" else "已压缩")
                if (change != null) append("  ·  $change")
            },
            style = SmithyRowMeta,
            modifier = Modifier.padding(start = SmithySpacing.iconGap + 18.dp),
            color = if (change != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
            exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
        ) {
            Row(
                Modifier.padding(top = SmithySpacing.gap),
                horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
            ) {
                // 是不是文本由 VM 读了内容再判断并解释，这里不做扩展名过滤 ——
                // 像 `META-INF/androidx.core.version` 这种没扩展名但确实是文本的会被误杀
                ToolbarPill("编辑", { onEdit(entry.path) }, icon = SmithyIcons.Rename)
                ToolbarPill("替换", { onReplace(entry.path) }, icon = SmithyIcons.Upload)
                ToolbarPill("删除", { onDelete(entry.path) }, icon = SmithyIcons.Delete, danger = true)
                if (change != null) {
                    ToolbarPill("撤销", { onUndo(entry.path) }, icon = SmithyIcons.Undo)
                }
            }
        }
    }
}

@Composable
private fun ZipActions(
    zip: ZipUiState,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onExtract: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
        ) {
            if (zip.isTar) {
                // tar 没有改动攒不攒的问题 —— 它是只读的，主操作就是解压
                Text(
                    "tar / tar.gz 只读浏览。解压会在旁边建同名文件夹，已存在的文件跳过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "改动先攒着，点保存才写盘。**改过的 apk 签名会失效**，要重签才能装",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(SmithySpacing.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Icon(SmithyIcons.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("关闭")
                }
                if (zip.isTar) {
                    Button(
                        onClick = onExtract,
                        enabled = !zip.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(SmithyIcons.Unzip, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (zip.saving) "解压中…" else "解压到旁边文件夹")
                    }
                } else {
                    Button(
                        onClick = onSave,
                        enabled = zip.changes.isNotEmpty() && !zip.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(SmithyIcons.Save, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (zip.saving) "保存中…" else "另存为…")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBar(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier.padding(horizontal = SmithySpacing.gap, vertical = 4.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.barVertical + 4.dp, vertical = SmithySpacing.barVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 成功/失败得能一眼分开：原先两者只差底色，而浅红和浅灰在户外几乎一样
            Icon(
                imageVector = if (isError) SmithyIcons.Warning else SmithyIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Spacer(Modifier.width(SmithySpacing.gap))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 「从设备提取应用」整屏。
 *
 * 和目录列表是**两种东西**：这里的行没有 FsItem 的路径语义（点开没有
 * 「它所在的目录」），所以不复用 DirList —— 复用会诱导多选/粘贴作用上去。
 */
@Composable
private fun AppPickerScreen(
    picker: AppPickerState,
    onClose: () -> Unit,
    onQuery: (String) -> Unit,
    onToggleSystem: () -> Unit,
    onExtract: (dev.smithy.fs.AppExtractor.AppInfo) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题。这里不该出现面包屑 —— 我们不在文件系统里
        SmithyTopBar(
            title = "从设备提取应用",
            subtitle = if (picker.loading) "正在读取已安装应用…" else "${picker.apps.size} 个应用",
            onBack = onClose,
            actions = {
                ToolbarPill(
                    label = if (picker.includeSystem) "含系统应用" else "只看用户应用",
                    onClick = onToggleSystem,
                    active = picker.includeSystem,
                    icon = SmithyIcons.Apps,
                )
            },
        )
        // 过滤框：装了两百个应用的设备上，没有它就得滚很久
        TextField(
            value = picker.query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("按名字 / 包名过滤") },
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = 4.dp),
            shape = RoundedCornerShape(99.dp),
            leadingIcon = {
                Icon(
                    imageVector = SmithyIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (picker.loading) {
            // 读已装应用要一两秒，而且行数是可以预期的 —— 给骨架比给转圈更少「等」的感觉
            SmithySkeletonList(Modifier.weight(1f), rows = 9)
            return@Column
        }
        val q = picker.query.trim().lowercase()
        val shown = if (q.isEmpty()) picker.apps else picker.apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            SmithyEmptyState(
                icon = SmithyIcons.Apps,
                title = "没有匹配的应用",
                hint = "换个关键词；如果是系统应用，先把上面的范围切到「含系统应用」",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.packageName }) { app ->
                val haptics = rememberSmithyHaptics()
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            haptics.tap()
                            onExtract(app)
                        }
                        .defaultMinSize(minHeight = SmithySpacing.rowHeight)
                        .padding(horizontal = SmithySpacing.rowHorizontal, vertical = SmithySpacing.rowVertical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = SmithyIcons.KindApk,
                        contentDescription = null,
                        modifier = Modifier.size(SmithySpacing.iconSize),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(SmithySpacing.iconGap))
                    Column(Modifier.weight(1f)) {
                        Text(
                            app.label,
                            style = SmithyRowTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            buildString {
                                append(app.packageName)
                                append("  ·  ")
                                append(app.versionName)
                                if (app.splits.isNotEmpty()) append("  ·  split ×${app.splits.size}")
                                if (app.isUpdatedSystem) append("  ·  系统应用的更新")
                            },
                            style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Icon(
                        imageVector = SmithyIcons.Download,
                        contentDescription = "提取到当前目录",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 文件管理器的标签条。
 *
 * 和浏览器标签同一个心智模型：每个标签记住自己浏览到哪，× 关闭、＋ 新开。
 * 当前标签填色、其余描边——和面包屑胶囊是同一套形状语言。
 * 只有一个标签时不显示 ×（关了它就什么都不剩了），但 ＋ 始终在。
 */
@Composable
private fun TabStrip(
    tabs: List<FileTab>,
    activeTabId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = SmithySpacing.gap, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        ) {
            tabs.forEach { tab ->
                val active = tab.id == activeTabId
                // 全圆角药丸，不是「上圆下直」的标签片：这一条在屏幕**底部**，
                // 上圆角的形状是「贴在顶部标签栏」的语言，挪到底部就不再成立
                val container by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    animationSpec = SmithyMotion.state(),
                    label = "tabContainer",
                )
                Surface(
                    onClick = { onSelect(tab.id) },
                    color = container,
                    shape = RoundedCornerShape(99.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.widthIn(max = 96.dp),
                            color = if (active) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        // 只剩一个标签时不显示关闭：关了就什么都不剩，那个 × 是个陷阱
                        if (tabs.size > 1) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { onClose(tab.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = SmithyIcons.Close,
                                    contentDescription = "关闭标签",
                                    modifier = Modifier.size(13.dp),
                                    tint = if (active) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        } else {
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
            // ＋ 新建标签
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNew),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SmithyIcons.Plus,
                    contentDescription = "新建标签",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** FTP 连接参数对话框。匿名登录：用户名填 anonymous、密码随便。 */
@Composable
private fun FtpDialog(
    onConnect: (host: String, port: Int, user: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { DialogTitle(icon = SmithyIcons.Lan, text = "连接 FTP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    label = { Text("地址（如 192.168.1.10）") },
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    label = { Text("端口（默认 21）") },
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text("用户名（匿名填 anonymous）") },
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    label = { Text("密码") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                Text(
                    "凭据只在本次连接内使用，不会保存。FTP 是明文协议，只建议在局域网用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(host, port.toIntOrNull() ?: 21, user, pass) },
                enabled = host.isNotBlank(),
            ) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * FTP 浏览屏。
 *
 * 和本地列表的交互刻意保持一致（点目录进、点文件下载），但**只读**：
 * 上传/删除涉及服务端写权限与并发冲突，第一版不做半吊子。
 * 每行右侧「下载」把文件拉到断开前的本地目录。
 */
@Composable
private fun FtpScreen(
    ftp: FtpBrowseState,
    onOpenDir: (String) -> Unit,
    onDownload: (dev.smithy.fs.FtpSession.Entry) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 断开挂在返回位上：这一屏的「返回」含义就是离开这个连接，
        // 另给一个「断开」按钮是同一件事的两种说法
        SmithyTopBar(
            title = "FTP · ${ftp.host}",
            subtitle = ftp.path,
            onBack = onDisconnect,
        )
        if (ftp.entries.isEmpty()) {
            SmithyEmptyState(
                icon = SmithyIcons.OpenFolder,
                title = "这个目录是空的",
                hint = "远端目录里没有条目。返回上一级，或断开连接",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(ftp.entries, key = { it.path }) { e ->
                val kind = FileKind.of(e.name, e.dir)
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (e.dir) onOpenDir(e.path) else onDownload(e)
                    }
                    .defaultMinSize(minHeight = SmithySpacing.rowHeight)
                    .padding(horizontal = SmithySpacing.rowHorizontal, vertical = SmithySpacing.rowVertical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = kind.icon,
                        contentDescription = null,
                        modifier = Modifier.size(SmithySpacing.iconSize),
                        tint = kind.tint(),
                    )
                    Spacer(Modifier.width(SmithySpacing.iconGap))
                    Text(
                        e.name,
                        style = SmithyRowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (e.dir) {
                        Icon(
                            imageVector = SmithyIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            if (ftp.downloading == e.name) "下载中…" else humanSize(e.size),
                            style = if (ftp.downloading == e.name) {
                                SmithyRowMeta
                            } else {
                                SmithyNumeric
                            },
                            color = if (ftp.downloading == e.name) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
