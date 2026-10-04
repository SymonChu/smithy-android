package dev.smithy.feature.apk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.engine.ResourceEntry

/** 「全部」用空串表示：引擎那边 null 就是不过滤。 */
private val RES_TYPES = listOf(
    "" to "全部",
    "string" to "string",
    "drawable" to "drawable",
    "mipmap" to "mipmap",
    "color" to "color",
    "layout" to "layout",
    "xml" to "xml",
    "raw" to "raw",
)

/**
 * 「资源」标签：列资源、改单条、批量换文案。
 *
 * 与「代码」标签的分工要说清：**文案优先在资源层改**。
 * 200 组替换在资源层是 300ms，在 dex 层是 27 秒（要重建命中的每个 dex 的对象树）。
 * 只有硬编码在代码里的文案才值得去「代码」标签。
 */
@Composable
internal fun ResourcesTab(
    state: WorkbenchUiState,
    onType: (String) -> Unit,
    onFilter: (String) -> Unit,
    onLoad: () -> Unit,
    onSetResource: (String, String) -> Unit,
    onReplaceMany: (String) -> Unit,
    onPickIcon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<ResourceEntry?>(null) }
    var batchText by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        // ── 类型 + 搜索 ──
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(RES_TYPES.size) { i ->
                    val (value, label) = RES_TYPES[i]
                    FilterChip(
                        selected = state.resType == value,
                        onClick = { onType(value) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.resFilter,
                    onValueChange = onFilter,
                    label = { Text("搜资源名或值") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onLoad, enabled = state.busy == null) { Text("列出") }
            }
        }

        // ── 列表 ──
        Box(Modifier.weight(1f)) {
            if (state.resources.isEmpty()) {
                ResHint("点「列出」读资源表（默认只看 string）")
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(state.resources.size) { i ->
                        val r = state.resources[i]
                        Column(
                            Modifier.fillMaxWidth()
                                // 复杂条目（样式、数组）不给点：它们的值不是单个字符串，改不了
                                .clickable(enabled = !r.isComplex && r.value != null) { editing = r }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(r.resName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                r.value ?: if (r.isComplex) "（复杂条目，目前不在手机上改）" else "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // ── 批量替换 ──
        Column(Modifier.padding(12.dp)) {
            Text(
                "批量换文案（只动资源表，200 组约 300ms）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "每行一条：旧值=新值；# 开头的行当注释",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = batchText,
                onValueChange = { batchText = it },
                placeholder = { Text("确定=OK\n取消=Cancel") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onReplaceMany(batchText) },
                enabled = state.busy == null && batchText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("执行替换") }

            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = onPickIcon,
                enabled = state.busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("换启动图标…") }
        }
    }

    // ── 改单条的对话框 ──
    editing?.let { res ->
        var value by remember(res) { mutableStateOf(res.value.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(res.resName, style = MaterialTheme.typography.titleSmall) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("新值") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetResource(res.resName, value)
                        editing = null
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ResHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
