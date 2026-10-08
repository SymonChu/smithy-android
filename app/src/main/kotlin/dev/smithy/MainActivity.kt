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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyTheme
import dev.smithy.design.ThemeStore
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.feature.apk.ApkWorkbenchScreen
import dev.smithy.feature.chat.ChatScreen
import dev.smithy.feature.chat.ChatSettingsScreen
import dev.smithy.feature.chat.ChatViewModel
import dev.smithy.feature.files.FilesScreen
import dev.smithy.feature.files.StorageAccess
import dev.smithy.feature.files.FilesViewModel
import dev.smithy.feature.settings.ExtensionsScreen
import dev.smithy.feature.settings.ExtensionsViewModel
import dev.smithy.feature.settings.ExtensionUiState
import dev.smithy.feature.settings.SettingsHome
import dev.smithy.feature.settings.SettingsPage

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

    /**
     * 主题存储。
     *
     * 放在 Activity 而不是 `SmithyApp`：主题是**界面状态**，跟着 Activity 生命周期走更清楚
     * （App 进程被回收后重建时会重新读一次，读到用户最后选的那套）。
     */
    private val themeStore by lazy { ThemeStore(this) }

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
            //
            // 主题（色板）是**状态**，所以要用 remember 而不是常量：设置页改一下，
            // 整棵树要立刻换色板（换个 MaterialTheme 就够了，不需要重建 Activity）。
            // 存哪由 ThemeStore 管，设置页通过 onThemeChange 写进去。
            var palette by remember { mutableStateOf(themeStore.current()) }
            SmithyTheme(palette = palette) {
                // 启动淡入：冷启动时用户先看到的是窗口底色（themes.xml，Android 12+ 是
                // 系统启动画面），Compose 首帧画出来那一刻如果整屏「啪」地出现，观感上
                // 就是闪一下。给根节点一段 320ms 的淡入 + 极轻微放大，把
                // 「静态启动画面 → 界面」这一段接起来。
                // 幅度刻意压得很小（0.985 起步）：大了就变成「缩放动画」，那是展示页
                // 的做法，工具类应用只需要「稳地出现」
                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appeared = true }
                val launchAlpha by animateFloatAsState(
                    targetValue = if (appeared) 1f else 0f,
                    animationSpec = tween(SmithyMotion.Slow, easing = SmithyMotion.EaseEnter),
                    label = "launchAlpha",
                )
                val launchScale by animateFloatAsState(
                    targetValue = if (appeared) 1f else 0.985f,
                    animationSpec = tween(SmithyMotion.Slow, easing = SmithyMotion.EaseEnter),
                    label = "launchScale",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = launchAlpha
                            scaleX = launchScale
                            scaleY = launchScale
                        },
                ) {
                    SmithyRoot(
                        incomingUri = incoming.value,
                        onIncomingConsumed = { incoming.value = null },
                        resumeTick = resumeTick.intValue,
                        palette = palette,
                        onPaletteChange = { picked ->
                            palette = picked
                            themeStore.set(picked)
                        },
                    )
                }
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

/**
 * 一级导航。
 *
 * **带图标**：之前底栏是 `icon = {}` —— 四个纯文字标签排在一行，既不像导航栏，
 * 也没法在扫视时快速定位（文字要读，图标要认）。图标走 [SmithyIcons] 的自绘集，
 * 单色、跟随主题着色。
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    Files("文件", SmithyIcons.Files),
    Apk("工作台", SmithyIcons.Workbench),
    Chat("对话", SmithyIcons.Chat),
    Settings("设置", SmithyIcons.Settings),
}

@Composable
fun SmithyRoot(
    incomingUri: Uri? = null,
    onIncomingConsumed: () -> Unit = {},
    resumeTick: Int = 0,
    /** 当前色板。换成别的就会整棵树换色（MaterialTheme 换 colorScheme 即可）。 */
    palette: dev.smithy.design.SmithyTheme = dev.smithy.design.SmithyTheme.Default,
    onPaletteChange: (dev.smithy.design.SmithyTheme) -> Unit = {},
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

    val haptics = rememberSmithyHaptics()

    // 设置里四页。默认落在「外观」——主题是唯一「改完立刻能看到效果」的设置，
    // 放在第一页最省事；而 AI 接口是要反复改的，不该占着第一眼
    var settingsPage by remember { mutableStateOf(SettingsPage.Appearance) }
    val extVm = remember { ExtensionsViewModel(app) }
    val extState by extVm.state.collectAsState()

    // 从手机本地导入一份扩展包（电脑传过来的工具链走这条）
    val pickLocalPackage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 文件名当扩展 id：记账文件叫 `<id>.installed`，重名的话 installFromLocal 会覆盖，
        // 这比在清单里硬造一个条目诚实（本地包本来就不该混进下载清单）
        runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val name = c.getString(0)?.substringAfterLast('/')?.substringBeforeLast('.')
                if (!name.isNullOrBlank()) {
                    // FileImporter 那套把 Uri 落成临时文件，这里只需要一个 File 句柄
                    val tmp = java.io.File.createTempFile("addon-", ".pkg", app.cacheDir)
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    extVm.importLocal(tmp, name)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        // 底色显式给 background：Scaffold 默认取 surface，而列表/卡片用的是
        // surfaceContainer 系列 —— 不区分的话「列表底」和「底栏」是同一个颜色，
        // 分组感就没了
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 64dp：比 M3 默认的 80 窄，但比上一版的 56 高 —— 加进图标后
            // 56dp 装不下「22dp 图标 + 11sp 标签」，标签会贴着底边；
            // 64 是能装下又不多占一行的那一档
            NavigationBar(
                modifier = Modifier.height(64.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                Tab.entries.forEach { t ->
                    val selected = tab == t
                    NavigationBarItem(
                        selected = selected,
                        // 已经在这个 tab 上就别再触发一次重绘和震动
                        onClick = {
                            if (!selected) {
                                haptics.toggle()
                                tab = t
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = t.icon,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = {
                            Text(t.label, style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }
        },
    ) { padding ->
        // 键盘/输入法（ime）的处理必须在这一层和 Scaffold 的 padding 协同，不能各算各的。
        //
        // 之前只有 ChatScreen 内部挂了 `.imePadding()`，而它拿到的 modifier 已经吃了
        // Scaffold 的 contentPadding（那里面含 64dp 底栏 + 系统导航栏）。`imePadding()`
        // 的高度是**从屏幕底边**算的键盘遮挡量 —— 两个高度叠在一起，输入栏被推到键盘
        // 底下，点开键盘就「什么都看不见」。
        //
        // 现在：`consumeWindowInsets(padding)` 把 Scaffold 已经垫掉的 bottomBar inset
        // 标记为已消费（内层再算 ime 时不会重复计入这部分），`imePadding()` 仍挂在
        // content Box 上、由各页共享 —— 键盘弹起时整个内容区（含输入栏）一起上移，
        // 输入栏永远压在键盘上方。
        Box(
            Modifier
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 切标签用 fade-through：旧页淡出（略缩），新页淡入。
            // 一级导航**不做左右滑动** —— 滑动表达「同一层级里的相邻关系」，
            // 而四个 tab 之间没有这种空间关系；滑动反而会让人以为能横着划过去。
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val enter = tween<Float>(SmithyMotion.Slow, easing = SmithyMotion.EaseEnter)
                    val exit = tween<Float>(SmithyMotion.Fast, easing = SmithyMotion.EaseExit)
                    (fadeIn(enter) + scaleIn(enter, initialScale = 0.96f)).togetherWith(
                        fadeOut(exit) + scaleOut(exit, targetScale = 0.96f),
                    )
                },
                label = "tab",
            ) { current ->
                when (current) {
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
                        // 模块页缺工具链时不自己下载（那是扩展中心的事），只把人送过去
                        onGoToExtensions = {
                            settingsPage = SettingsPage.Extensions
                            tab = Tab.Settings
                        },
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

                    Tab.Settings -> SettingsHome(
                        page = settingsPage,
                        state = extState,
                        palette = palette,
                        onPaletteChange = onPaletteChange,
                        onSelect = { settingsPage = it },
                        onConfigChange = chatVm::onConfigChange,
                        onTrustWritesChange = chatVm::setTrustWrites,
                        onRefreshExtensions = extVm::refresh,
                        onInstallExtension = extVm::install,
                        onInstallAllExtensions = extVm::installAll,
                        onRemoveExtension = extVm::remove,
                        onImportExtension = { pickLocalPackage.launch(arrayOf("*/*")) },
                        onDismissNotice = extVm::dismissNotice,
                        onRequestStorageAccess = requestStorageAccess,
                        // AI 接口页还是 ChatSettingsScreen（原样搬过来）—— feature:settings 不依赖
                        // feature:chat（否则两模块互相依赖），所以由 app 层把它作为内容传进来
                        aiPage = { m ->
                            ChatSettingsScreen(
                                config = chatState.config,
                                problem = chatState.configProblem,
                                trustWrites = chatState.trustWrites,
                                onConfigChange = chatVm::onConfigChange,
                                onTrustWritesChange = chatVm::setTrustWrites,
                                modifier = m,
                            )
                        },
                    )
                }
            }
        }
    }
}
