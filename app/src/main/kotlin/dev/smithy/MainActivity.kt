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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmithyRoot() }
    }
}

/** 四个 Tab 的骨架。M0 只填「工作台」，其余在 M3/M4 补。 */
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
                // M4 起实现：双窗口 + zip 直改 + 编辑器
                Tab.Files -> Placeholder("双窗口文件管理（M4）")
                // M0 起实现：概览 / 代码 / 资源 / 改动
                Tab.Apk -> Placeholder("APK 工作台")
                // M3：Agent 对话、工具卡片、门控确认
                Tab.Chat -> Placeholder("AI 对话")
                // 模型配置 / Shizuku 引导 / 模块管理
                Tab.Settings -> Placeholder("设置")
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
