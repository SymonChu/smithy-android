package dev.smithy

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.smithy.feature.apk.ApkWorkbenchScreen

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
                // M0 已可用：选包 → 解析报告
                Tab.Apk -> ApkWorkbenchScreen()
                // M3：Agent 对话、工具卡片、门控确认
                Tab.Chat -> Placeholder("AI 对话（M3）")
                Tab.Settings -> Placeholder("设置")
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
