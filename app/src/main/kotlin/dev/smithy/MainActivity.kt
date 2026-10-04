package dev.smithy

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmithyRoot() }
    }
}

private enum class Tab(val label: String) {
    Files("文件"),
    Apk("工作台"),
    Chat("对话"),
    Settings("设置"),
}

@Composable
fun SmithyRoot() {
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
                )

                Tab.Apk -> ApkWorkbenchScreen()

                Tab.Chat -> {
                    // 切过来时刷新「当前操作的是哪个包」—— 用户可能刚在工作台换了包
                    LaunchedEffect(tab) { chatVm.refreshWorkspace() }
                    ChatScreen(
                        state = chatState,
                        workspaceName = chatState.workspaceName,
                        onInput = chatVm::onInput,
                        onSend = chatVm::send,
                        onStop = chatVm::stop,
                        onClear = chatVm::clear,
                        onConfirm = chatVm::answerConfirm,
                    )
                }

                Tab.Settings -> ChatSettingsScreen(
                    config = chatState.config,
                    problem = chatState.configProblem,
                    onConfigChange = chatVm::onConfigChange,
                )
            }
        }
    }
}
