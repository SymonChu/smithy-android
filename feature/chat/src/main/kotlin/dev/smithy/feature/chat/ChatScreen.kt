package dev.smithy.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 对话界面。
 *
 * **无状态**：数据与状态全在 [ChatViewModel] 里，这里只负责画和转发事件。
 * 这样「确认条会不会卡住」「停止能不能真停下」这类问题只可能出在一处，界面层不掺和状态机。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    workspaceName: String?,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 新内容进来就滚到底：AI 在流式输出，用户不该自己去追
    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.size - 1)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        TopBar(workspaceName, state.running, onClear)

        if (state.items.isEmpty()) {
            EmptyHint(workspaceName != null)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items, key = { it.id }) { item -> Item(item) }
        }

        state.pendingConfirm?.let { request ->
            val destructive = state.items.filterIsInstance<ChatItem.Confirm>()
                .lastOrNull()?.destructive ?: false
            ConfirmBar(request.toolName, request.summary, destructive, onConfirm)
        }

        state.configProblem?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "AI 还没配好：$it（到「设置」填）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                )
            }
        }

        InputBar(state.input, state.running, onInput, onSend, onStop)
    }
}

@Composable
private fun TopBar(workspaceName: String?, running: Boolean, onClear: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("对话", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    workspaceName?.let { "正在操作：$it" } ?: "没有打开包（先去工作台选一个）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (workspaceName != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (running) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            TextButton(onClick = onClear) { Text("清空") }
        }
    }
}

@Composable
private fun EmptyHint(hasWorkspace: Boolean) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("试着说一句：", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        listOf(
            "把这个包的应用名改成「我的应用」，版本改成 2.0",
            "把「工作台」这个文案改成「操作台」",
            "看看这个包有哪些权限，有没有危险的",
        ).forEach {
            Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        if (!hasWorkspace) {
            Spacer(Modifier.height(10.dp))
            Text(
                "（改包之前要先在工作台打开一个 APK —— 否则我只能聊天，看不到包内容）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Item(item: ChatItem) = when (item) {
    is ChatItem.User -> UserBubble(item.text)
    is ChatItem.Assistant -> AssistantBubble(item)
    is ChatItem.Tool -> ToolCard(item)
    is ChatItem.Notice -> NoticeRow(item)
    is ChatItem.Confirm -> Unit    // 确认条固定在底部，不在列表里重复显示
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AssistantBubble(item: ChatItem.Assistant) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp)) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (item.streaming) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
            }
        }
    }
}

/** 工具调用卡片。点一下展开参数 —— 参数常是 JSON，不展开太占地方。 */
@Composable
private fun ToolCard(item: ChatItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (item.state) {
                        ChatItem.Tool.State.RUNNING -> "⏳"
                        ChatItem.Tool.State.OK -> "✓"
                        ChatItem.Tool.State.FAILED -> "✗"
                    },
                    color = when (item.state) {
                        ChatItem.Tool.State.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "参数",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) 40 else 2,
                    color = if (item.state == ChatItem.Tool.State.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.args,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(item: ChatItem.Notice) {
    Text(
        item.text,
        style = MaterialTheme.typography.bodySmall,
        color = if (item.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

/**
 * 门控确认条。
 *
 * 破坏性操作的措辞要**比普通写入更重**：提示用户这件事靠回退救不回来
 * （比如装机 —— 包已经装到手机上了，撤销改动也换不回来）。
 */
@Composable
private fun ConfirmBar(
    toolName: String,
    summary: String,
    destructive: Boolean,
    onAnswer: (Boolean) -> Unit,
) {
    Surface(
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (destructive) "⚠ 这个操作不可撤销，要执行吗" else "要执行这个操作吗",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$toolName：$summary",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAnswer(true) },
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) { Text("允许") }
                OutlinedButton(onClick = { onAnswer(false) }) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    running: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.height(56.dp)) { Text("停止") }
            } else {
                Button(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                ) { Text("发送") }
            }
        }
    }
}
