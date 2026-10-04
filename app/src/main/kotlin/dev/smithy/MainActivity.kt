package dev.smithy

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.smithy.feature.apk.ApkWorkbenchScreen
import dev.smithy.feature.chat.ChatScreen
import dev.smithy.feature.chat.ChatSettingsScreen
import dev.smithy.feature.chat.ChatViewModel
import dev.smithy.feature.files.FilesScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming.value = incomingUriOf(intent)
        setContent {
            SmithyRoot(
                incomingUri = incoming.value,
                onIncomingConsumed = { incoming.value = null },
            )
        }
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
) {
    var tab by remember { mutableStateOf(Tab.Apk) }

    // VM 都提在这一层：切标签再切回来，正在跑的对话、正在浏览的目录都该保留
    val app = LocalContext.current.applicationContext as Application
    val chatVm = remember { ChatViewModel(app) }
    val chatState by chatVm.state.collectAsState()
    val filesVm = remember { FilesViewModel(app) }
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
        bottomBar = {
            NavigationBar {
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
                    onFilter = filesVm::onFilter,
                    onCloseZip = filesVm::closeZip,
                    onReplaceEntry = { entry ->
                        replacingEntry = entry
                        pickSource.launch(arrayOf("*/*"))
                    },
                    onDeleteEntry = filesVm::deleteEntry,
                    onUndoEntry = filesVm::undoEntry,
                    onSaveZip = { pickTarget.launch("output.zip") },
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
