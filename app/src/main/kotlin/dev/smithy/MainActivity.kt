package dev.smithy

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.smithy.feature.apk.ApkWorkbenchScreen
import dev.smithy.feature.chat.ChatScreen
import dev.smithy.feature.chat.ChatSettingsScreen
import dev.smithy.feature.chat.ChatViewModel

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

    // 对话的 VM 提在这一层：切到别的标签再切回来，正在跑的对话不该丢
    val app = LocalContext.current.applicationContext as Application
    val chatVm = remember { ChatViewModel(app) }
    val chatState by chatVm.state.collectAsState()

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
                // M4：双窗口 + zip 直改 + 编辑器
                Tab.Files -> Placeholder("双窗口文件管理（M4）")

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

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
