package dev.smithy.feature.apk

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.smithy.engine.ApkEntry
import dev.smithy.engine.ApkReport

/** 常用的目录前缀。引擎的过滤是 `startsWith`，所以这些正好是「按目录看」。 */
private val QUICK_PREFIXES = listOf(
    "" to "全部",
    "res/" to "res",
    "lib/" to "lib",
    "assets/" to "assets",
    "META-INF/" to "META-INF",
    "classes" to "dex",
)

/**
 * 「文件」标签：看包内条目，替换或删除它们。
 *
 * **删除不是立刻生效**：它被记进工作区覆盖层，重打包时才跳过该条目 ——
 * 所以在打包之前，用户反悔只要去「改动」标签回退。这也是为什么这里敢让用户删东西。
 */
@Composable
internal fun FilesTab(
    state: WorkbenchUiState,
    onFilter: (String) -> Unit,
    onLoad: () -> Unit,
    onReplace: (String, Uri) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var acting by remember { mutableStateOf<ApkEntry?>(null) }
    var confirmingDelete by remember { mutableStateOf<ApkEntry?>(null) }

    // 选文件的回调要等用户选完才回来，所以先把「要替换哪个条目」记下来
    var pendingPath by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val path = pendingPath
        pendingPath = null
        if (uri != null && path != null) onReplace(path, uri)
    }

    Column(modifier.fillMaxSize()) {
        // ── 前缀 + 过滤 ──
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(QUICK_PREFIXES.size) { i ->
                    val (prefix, label) = QUICK_PREFIXES[i]
                    FilterChip(
                        selected = state.entryFilter == prefix,
                        onClick = { onFilter(prefix) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.entryFilter,
                    onValueChange = onFilter,
                    label = { Text("路径前缀，如 res/") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onLoad, enabled = state.busy == null) { Text("列出") }
            }
        }

        // ── 条目列表 ──
        if (state.entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "点「列出」看包里的文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(state.entries.size) { i ->
                    val e = state.entries[i]
                    if (e.isDirectory) return@items
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { acting = e }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            e.path,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            ApkReport.humanSize(e.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // ── 条目操作 ──
    acting?.let { e ->
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(e.path, style = MaterialTheme.typography.titleSmall) },
            text = { Text("${ApkReport.humanSize(e.size)}｜压缩后 ${ApkReport.humanSize(e.compressedSize)}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        acting = null
                        pendingPath = e.path
                        picker.launch(arrayOf("*/*"))
                    },
                ) { Text("替换成设备上的文件…") }
            },
            dismissButton = {
                TextButton(onClick = { acting = null; confirmingDelete = e }) { Text("删除") }
            },
        )
    }

    // 删除要二次确认 —— 它改的是包的内容，不该一次点击就落定
    confirmingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("删除这个条目？") },
            text = {
                Text(
                    "${e.path}\n\n" +
                        "删除只是记在改动里，重打包时才跳过它 —— 打包之前都能在「改动」标签回退。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = null
                        onDelete(e.path)
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("取消") } },
        )
    }
}
