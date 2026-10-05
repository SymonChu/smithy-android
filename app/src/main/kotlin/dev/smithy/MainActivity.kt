package dev.smithy

import android.app.Application
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyTheme
import dev.smithy.feature.apk.ApkWorkbenchScreen
import dev.smithy.feature.chat.ChatScreen
import dev.smithy.feature.chat.ChatSettingsScreen
import dev.smithy.feature.chat.ChatViewModel
import dev.smithy.feature.files.FilesScreen
import dev.smithy.feature.files.StorageAccess
import dev.smithy.feature.files.FilesViewModel

class MainActivity : ComponentActivity() {

    /**
     * 外部送进来的包（文件管理器「打开方式」、分享）。
     *
     * 放在 State 里而不是只在 `onCreate` 读一次：App 已经开着时再从外部打开一个包走的是
     * `onNewIntent`，不处理的话第二次打开毫无反应 —— 而用户会以为是那个包有问题。
     *
     * `ACTION_VIEW` 给的是 `data`，`ACTION_SEND` 给的是 `EXTRA_STREAM`，两种都要看：
     * 「打开方式」和「分享到」是两条不同的入口，用户不区分它们，只期望都能用。
     */
    private val incoming = mutableStateOf<Uri?>(null)

    /**
     * 每次 `onResume` 加一。
     *
     * 用来告诉界面「刚从别处回来」。最要紧的场景是**从系统设置页打开「所有文件访问」
     * 之后回来** —— 权限变了但界面不会自己知道，还停在应用沙盒目录里，
     * 用户会以为授权没生效。
     */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：targetSdk 35 起 Android 强制内容延伸到状态栏后面，
        // 各页顶栏会画进状态栏底下 —— 表现为「看不见状态栏」。
        // 显式开启 + 在 SmithyRoot 的 Scaffold 上垫状态栏高度，
        // 状态栏区域交还系统画，内容从它下面开始
        enableEdgeToEdge()
        incoming.value = incomingUriOf(intent)
        setContent {
            // 主题套在最外层：整棵界面树都从它取色板/字阶/圆角。
            // 之前一行主题代码都没有 —— 用的是 M3 内置默认浅色，系统切暗色时界面还是白的
            SmithyTheme {
                SmithyRoot(
                    incomingUri = incoming.value,
                    onIncomingConsumed = { incoming.value = null },
                    resumeTick = resumeTick.intValue,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = incomingUriOf(intent)
    }

    @Suppress("DEPRECATION")
    private fun incomingUriOf(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
    }
}

private enum class Tab(val label: String) {
    Files("文件"),
    Apk("工作台"),
    Chat("对话"),
    Settings("设置"),
}

@Composable
fun SmithyRoot(
    incomingUri: Uri? = null,
    onIncomingConsumed: () -> Unit = {},
    resumeTick: Int = 0,
) {
    var tab by remember { mutableStateOf(Tab.Apk) }

    // VM 都提在这一层：切标签再切回来，正在跑的对话、正在浏览的目录都该保留
    val app = LocalContext.current.applicationContext as Application
    val chatVm = remember { ChatViewModel(app) }
    val chatState by chatVm.state.collectAsState()
    val filesVm = remember { FilesViewModel(app) }

    // 从别处回来时重判一次访问能力。最要紧的场景是**去系统设置打开「所有文件访问」
    // 之后回来** —— 权限变了界面不会自己知道，还停在应用沙盒里
    LaunchedEffect(resumeTick) { filesVm.refreshAccess() }

    // 「所有文件访问」（Android 11+）**没有弹窗可要**，只能把人送到系统设置那一页
    val openStorageSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { filesVm.refreshAccess() }

    // Android 10 及以下还有普通运行时权限，能直接弹
    val askStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { filesVm.refreshAccess() }

    val ctx = LocalContext.current
    val requestStorageAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openStorageSettings.launch(StorageAccess.settingsIntent(ctx))
        } else {
            askStoragePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val filesState by filesVm.state.collectAsState()

    // 替换包内条目：先选设备上的文件，再把它读进待改动列表
    var replacingEntry by remember { mutableStateOf<String?>(null) }
    val pickSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val entry = replacingEntry
        replacingEntry = null
        if (uri != null && entry != null) filesVm.replaceEntryFromUri(entry, uri)
    }
    val pickTarget = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) filesVm.saveZipToUri(uri) }

    // 对话里直接选包，不用先去「工作台」标签。
    // 不限 mime 类型：apk 的 mime 各家 ROM 报得不一样，限了就选不中
    val pickApkForChat = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) chatVm.attachApk(uri) }

    // 从设备任意位置导入要改的包 —— 私有目录里不会凭空出现用户的 apk，
    // 而真实场景要改的包基本都在别处（下载目录、聊天软件收下来的文件）。
    // 不限 mime：apk 各家 ROM 报的类型不一致，限了反而选不中
    val pickImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) filesVm.importFromUri(uri) }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        bottomBar = {
            // 底栏压到 56dp（M3 默认 80dp）：图标为空、只有文字标签，
            // 80dp 的一半高度都是空白 —— 4 个 tab 是导航不是展示，窄一点
            // 每页多出两行内容的可视空间
            NavigationBar(modifier = Modifier.height(56.dp)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {},
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Files -> FilesScreen(
                    state = filesState,
                    relative = filesVm::relative,
                    onOpenDir = filesVm::openDir,
                    onOpenItem = filesVm::open,
                    onGoUp = filesVm::goUp,
                    onCloseZip = filesVm::closeZip,
                    onReplaceEntry = { entry ->
                        replacingEntry = entry
                        pickSource.launch(arrayOf("*/*"))
                    },
                    onDeleteEntry = filesVm::deleteEntry,
                    onUndoEntry = filesVm::undoEntry,
                    onSaveZip = { pickTarget.launch("output.zip") },
                    onExtractTar = filesVm::extractTarHere,
                    onOpenText = filesVm::openText,
                    onSaveText = filesVm::saveText,
                    onCancelEdit = filesVm::cancelEdit,
                    onShowProperties = filesVm::showProperties,
                    onRename = filesVm::rename,
                    onDelete = filesVm::delete,
                    onDismissProperties = filesVm::dismissProperties,
                    onToggleSelecting = filesVm::toggleSelecting,
                    onSelectAll = filesVm::selectAllFiles,
                    onClearSelection = filesVm::clearSelection,
                    onRulesChange = filesVm::onRulesChange,
                    onApplyRename = filesVm::applyRename,
                    onToggleSelected = filesVm::toggleSelected,
                    onImport = { pickImport.launch(arrayOf("*/*")) },
                    onToggleRoot = filesVm::toggleRoot,
                    onCopy = filesVm::copySelected,
                    onCut = filesVm::cutSelected,
                    onDeleteSelected = filesVm::deleteSelected,
                    onPaste = filesVm::paste,
                    onClearClipboard = filesVm::clearClipboard,
                    breadcrumbs = filesVm::breadcrumbs,
                    shortcuts = filesVm::shortcuts,
                    onJumpTo = filesVm::jumpTo,
                    onSort = filesVm::setSort,
                    onToggleHidden = filesVm::toggleHidden,
                    onNewFolder = filesVm::mkdir,
                    onNewFile = filesVm::touch,
                    onChmod = filesVm::chmod,
                    onChown = filesVm::chown,
                    onViewHex = { item -> filesVm.viewHex(item.path) },
                    onHexClose = filesVm::hexClose,
                    onHexGoto = filesVm::hexGoto,
                    onHexPage = filesVm::hexPage,
                    onHexSave = filesVm::hexSave,
                    onRequestAccess = requestStorageAccess,
                    onSearch = filesVm::startSearch,
                    onCancelSearch = filesVm::cancelSearch,
                    onCloseSearch = filesVm::closeSearch,
                    onRevealHit = filesVm::revealHit,
                    onOpenAppPicker = filesVm::openAppPicker,
                    onCloseAppPicker = filesVm::closeAppPicker,
                    onFilterApps = filesVm::filterApps,
                    onToggleSystemApps = filesVm::toggleSystemApps,
                    onExtractApp = filesVm::extractApp,
                    onNewTab = { filesVm.newTab() },
                    onSelectTab = filesVm::selectTab,
                    onCloseTab = filesVm::closeTab,
                    onZipSelected = filesVm::zipSelected,
                    onConnectFtp = { h, p, u, pw -> filesVm.connectFtp(h, p, u, pw) },
                    onFtpOpenDir = filesVm::ftpOpenDir,
                    onFtpDownload = filesVm::ftpDownload,
                    onFtpDisconnect = filesVm::disconnectFtp,
                )

                Tab.Apk -> ApkWorkbenchScreen(
                    incomingUri = incomingUri,
                    onIncomingConsumed = onIncomingConsumed,
                )

                Tab.Chat -> {
                    // 切过来时刷新「当前操作的是哪个包」—— 用户可能刚在工作台换了包
                    LaunchedEffect(tab) { chatVm.refreshWorkspace() }
                    ChatScreen(
                        state = chatState,
                        workspaceName = chatState.workspaceName,
                        onInput = chatVm::onInput,
                        onSend = chatVm::send,
                        onStop = chatVm::stop,
                        onAttach = { pickApkForChat.launch(arrayOf("*/*")) },
                        onClear = chatVm::clear,
                        onConfirm = chatVm::answerConfirm,
                    )
                }

                Tab.Settings -> ChatSettingsScreen(
                    config = chatState.config,
                    problem = chatState.configProblem,
                    trustWrites = chatState.trustWrites,
                    onConfigChange = chatVm::onConfigChange,
                    onTrustWritesChange = chatVm::setTrustWrites,
                )
            }
        }
    }
}
