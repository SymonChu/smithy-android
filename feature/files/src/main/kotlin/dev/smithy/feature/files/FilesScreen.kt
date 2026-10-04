package dev.smithy.feature.files

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.smithy.fs.ZipEntryInfo

/**
 * 文件管理。
 *
 * 两种视图：**目录**（列文件）与**压缩包内部**（列条目、改条目）。
 * 两者共用同一块区域而不是并排两个窗口 —— 双窗口在手机上会把每边压到十几列字符宽，
 * 反而看不清路径。等平板布局再分栏。
 */
@Composable
fun FilesScreen(
    state: FilesUiState,
    relative: (String) -> String,
    onOpenDir: (String) -> Unit,
    onOpenItem: (FsItem) -> Unit,
    onGoUp: () -> Unit,
    onFilter: (String) -> Unit,
    onCloseZip: () -> Unit,
    onReplaceEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onUndoEntry: (String) -> Unit,
    onSaveZip: () -> Unit,
    onOpenText: (String) -> Unit,
    onSaveText: (String) -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 编辑中就让编辑器占满整屏：这时用户的心智在「改这个文件」上，
    // 把列表留在旁边只会把行宽挤到看不清
    state.editingPath?.let { path ->
        TextEditorScreen(
            path = path,
            text = state.editingText,
            onSave = onSaveText,
            onCancel = onCancelEdit,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        val zip = state.zip
        if (zip == null) {
            DirHeader(state.dir, relative, onGoUp)
            FilterRow(state.filter, onFilter)
            DirList(state, Modifier.weight(1f), onOpenItem)
        } else {
            ZipHeader(zip, state.busy)
            ZipList(zip, Modifier.weight(1f), onReplaceEntry, onDeleteEntry, onUndoEntry, onOpenText)
            ZipActions(zip, onCloseZip, onSaveZip)
        }

        state.message?.let { MessageBar(it, state.isError) }
    }
}

@Composable
private fun DirHeader(dir: String, relative: (String) -> String, onGoUp: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onGoUp) { Text("↑ 上级") }
            Text(
                relative(dir),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FilterRow(filter: String, onFilter: (String) -> Unit) {
    OutlinedTextField(
        value = filter,
        onValueChange = onFilter,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        singleLine = true,
        placeholder = { Text("按名字过滤") },
    )
}

@Composable
private fun DirList(state: FilesUiState, modifier: Modifier = Modifier, onOpenItem: (FsItem) -> Unit) {
    val filtered = state.items.filter {
        state.filter.isBlank() || it.name.contains(state.filter, ignoreCase = true)
    }
    if (filtered.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                state.busy ?: "这个目录是空的",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        items(filtered, key = { it.path }) { item ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onOpenItem(item) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (item.dir) "📁 " else "📄 ") + item.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (item.dir) "目录" else humanSize(item.size) +
                            if (item.maybeZip) "  ·  可展开" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ZipHeader(zip: ZipUiState, busy: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "包内浏览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                zip.path.substringAfterLast('/') + "  ·  ${zip.items.size} 个条目" +
                    if (zip.changes.isNotEmpty()) "  ·  待写入 ${zip.changes.size} 处" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (busy != null) {
                Text(busy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ZipList(
    zip: ZipUiState,
    modifier: Modifier = Modifier,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUndo: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(zip.items, key = { it.path }) { entry ->
            val change = zip.changes[entry.path]
            ZipRow(entry, change, onReplace, onDelete, onUndo, onEdit)
            HorizontalDivider()
        }
    }
}

@Composable
private fun ZipRow(
    entry: ZipEntryInfo,
    change: String?,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUndo: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            entry.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            buildString {
                append(if (entry.stored) "未压缩" else "已压缩")
                append("  ·  ${humanSize(entry.size)}")
                if (change != null) append("  ·  $change")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (change != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 是不是文本由 VM 读了内容再判断并解释，这里不做扩展名过滤 ——
                // 像 `META-INF/androidx.core.version` 这种没扩展名但确实是文本的会被误杀
                OutlinedButton(onClick = { onEdit(entry.path) }) { Text("编辑") }
                OutlinedButton(onClick = { onReplace(entry.path) }) { Text("替换") }
                OutlinedButton(onClick = { onDelete(entry.path) }) { Text("删除") }
                if (change != null) {
                    TextButton(onClick = { onUndo(entry.path) }) { Text("撤销") }
                }
            }
        }
    }
}

@Composable
private fun ZipActions(zip: ZipUiState, onClose: () -> Unit, onSave: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "改动先攒着，点保存才写盘。**改过的 apk 签名会失效**，要重签才能装",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("关闭") }
                Button(
                    onClick = onSave,
                    enabled = zip.changes.isNotEmpty() && !zip.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (zip.saving) "保存中…" else "另存为…")
                }
            }
        }
    }
}

@Composable
private fun MessageBar(text: String, isError: Boolean) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        )
    }
}
