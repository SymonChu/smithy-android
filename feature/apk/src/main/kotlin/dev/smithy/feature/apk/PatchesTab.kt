package dev.smithy.feature.apk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.smithy.engine.PatchRecord
import dev.smithy.engine.WorkspaceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「改动」标签。
 *
 * 这里要讲清一件容易误解的事：**代码视图读的是原包，改动只在工作区覆盖层里**。
 * 不说清楚的话，用户改完字符串回到代码标签，会发现「怎么还是旧的」，
 * 然后以为工具坏了 —— 实际上改动要重打包才合并进包。
 */
@Composable
internal fun PatchesTab(
    state: WorkbenchUiState,
    onRevert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SectionCardWrap {
            Text("改动记录（${state.patches.size}）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(
                "所有改动都记在这里，原包一个字节都没动。点「重打包」才会合并成新包；" +
                    "代码标签看到的是原包内容，所以在打包之前那里不会显示你的改动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "工作区状态：${state.workspaceState.label()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.patches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有改动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // 最新的在最上面，回退也按这个顺序（同一 entry 连续改多次时，先退后改的那次）
        val ordered = state.patches.reversed()
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(ordered.size) { i ->
                val p = ordered[i]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${p.kind.label()}  ${p.target}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        p.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Text(
                            timeFormat.format(Date(p.ts)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRevert(p.id) }) { Text("回退") }
                }
            }
        }
    }
}

@Composable
private fun SectionCardWrap(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(12.dp)) { SectionCard("说明", content) }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun PatchRecord.PatchKind.label() = when (this) {
    PatchRecord.PatchKind.SMALI -> "smali"
    PatchRecord.PatchKind.ARSC -> "资源表"
    PatchRecord.PatchKind.AXML -> "清单 xml"
    PatchRecord.PatchKind.MANIFEST -> "清单"
    PatchRecord.PatchKind.ENTRY_ADD -> "新增"
    PatchRecord.PatchKind.ENTRY_DEL -> "删除"
    PatchRecord.PatchKind.ENTRY_REPLACE -> "替换条目"
}

private fun WorkspaceState.label() = when (this) {
    WorkspaceState.IDLE -> "未打开"
    WorkspaceState.UNPACKED -> "已就绪（还没改）"
    WorkspaceState.DIRTY -> "有未打包的改动"
    WorkspaceState.REBUILDING -> "正在打包"
    WorkspaceState.REBUILT -> "已打包（未签名）"
    WorkspaceState.SIGNED -> "已签名（可安装）"
    WorkspaceState.INSTALLED -> "已安装"
    WorkspaceState.FAILED -> "上一步失败"
}
