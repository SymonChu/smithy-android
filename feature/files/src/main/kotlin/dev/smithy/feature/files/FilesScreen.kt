package dev.smithy.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
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
import dev.smithy.fs.humanTime
import dev.smithy.fs.RenameRules
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
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
    onShowProperties: (FsItem) -> Unit,
    onRename: (FsItem, String) -> Unit,
    onDelete: (FsItem) -> Unit,
    onDismissProperties: () -> Unit,
    onToggleSelecting: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRulesChange: (RenameRules) -> Unit,
    onApplyRename: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onImport: () -> Unit,
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

    state.properties?.let { props -> PropertiesCard(props, onDismissProperties) }

    Column(modifier.fillMaxSize()) {
        val zip = state.zip
        // 宽屏（平板 / 横屏）才分栏。手机竖屏并排两栏会把每边压到十几列字符宽，
        // 连路径都显示不全 —— 那比单栏更难用，所以阈值卡在 600dp
        val wide = LocalConfiguration.current.screenWidthDp >= WIDE_DP

        if (wide) {
            // 左右并列：目录一栏、包内容一栏。
            // 这两者是**不同层级**的东西（一个在文件系统里、一个在压缩包内部），
            // 并排比上下叠着更贴近它们的关系，而且互不遮挡 —— 改包时能一直看着文件列表
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f)) {
                    DirHeader(state.dir, relative, onGoUp)
                    FilterRow(state.filter, onFilter, onImport)
                    SelectionBar(
                        state = state,
                        onToggleSelecting = onToggleSelecting,
                        onSelectAll = onSelectAll,
                        onClear = onClearSelection,
                        onRulesChange = onRulesChange,
                        onApply = onApplyRename,
                    )
                    DirList(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onOpenItem = onOpenItem,
                        onShowProperties = onShowProperties,
                        onRename = onRename,
                        onDelete = onDelete,
                        onToggleSelected = onToggleSelected,
                    )
                }
                VerticalDivider()
                Column(Modifier.weight(1f)) {
                    if (zip == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "这一栏显示包内条目\n点左边的 apk / zip 打开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        ZipHeader(zip, state.busy)
                        ZipList(
                            zip,
                            Modifier.weight(1f),
                            onReplaceEntry,
                            onDeleteEntry,
                            onUndoEntry,
                            onOpenText,
                        )
                        ZipActions(zip, onCloseZip, onSaveZip)
                    }
                }
            }
        } else if (zip == null) {
            DirHeader(state.dir, relative, onGoUp)
            FilterRow(state.filter, onFilter, onImport)
            SelectionBar(
                state = state,
                onToggleSelecting = onToggleSelecting,
                onSelectAll = onSelectAll,
                onClear = onClearSelection,
                onRulesChange = onRulesChange,
                onApply = onApplyRename,
            )
            DirList(
                state = state,
                modifier = Modifier.weight(1f),
                onOpenItem = onOpenItem,
                onShowProperties = onShowProperties,
                onRename = onRename,
                onDelete = onDelete,
                onToggleSelected = onToggleSelected,
            )
        } else {
            ZipHeader(zip, state.busy)
            ZipList(zip, Modifier.weight(1f), onReplaceEntry, onDeleteEntry, onUndoEntry, onOpenText)
            ZipActions(zip, onCloseZip, onSaveZip)
        }

        state.message?.let { MessageBar(it, state.isError) }
    }
}

/** 到这个宽度就分栏（600dp 是手机竖屏与平板的常见分界，也是 Material 窗口尺寸类的边界）。 */
private const val WIDE_DP = 600

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
private fun FilterRow(filter: String, onFilter: (String) -> Unit, onImport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilter,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("筛选名字") },
        )
        // 「导入」放这里而不是标题栏：它和「筛选」一样是「对当前这一屏做的事」，
        // 而标题栏那排已经被导航按钮占满了
        Spacer(Modifier.width(8.dp))
        Button(onClick = onImport) { Text("导入") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirList(
    state: FilesUiState,
    modifier: Modifier = Modifier,
    onOpenItem: (FsItem) -> Unit,
    onShowProperties: (FsItem) -> Unit,
    onRename: (FsItem, String) -> Unit,
    onDelete: (FsItem) -> Unit,
    onToggleSelected: (String) -> Unit,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<FsItem?>(null) }
    var deleting by remember { mutableStateOf<FsItem?>(null) }
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
            Box {
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                // 选择模式下点击 = 勾选/取消，而不是打开 ——
                                // 否则「想多选」得先退出、再重新进，很别扭
                                if (state.selecting) onToggleSelected(item.path) else onOpenItem(item)
                            },
                            onLongClick = { menuFor = item.path },
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.selecting) {
                        Checkbox(
                            checked = item.path in state.selected,
                            onCheckedChange = { onToggleSelected(item.path) },
                        )
                    }
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
                DropdownMenu(
                    expanded = menuFor == item.path,
                    onDismissRequest = { menuFor = null },
                ) {
                    DropdownMenuItem(
                        text = { Text("属性 / 摘要") },
                        onClick = { menuFor = null; onShowProperties(item) },
                    )
                    DropdownMenuItem(
                        text = { Text("改名") },
                        onClick = { menuFor = null; renaming = item },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuFor = null; deleting = item },
                    )
                }
            }
            HorizontalDivider()
        }
    }

    renaming?.let { target ->
        TextInputDialog(
            title = "改名",
            initial = target.name,
            onConfirm = { renaming = null; onRename(target, it) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { target ->
        ConfirmDialog(
            title = "删除 ${target.name}？",
            body = if (target.dir) "目录连同里面的一切都会删掉，不能撤销。" else "不能撤销。",
            confirm = "删除",
            destructive = true,
            onConfirm = { deleting = null; onDelete(target) },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * 多选与批量改名的工具栏 + 预览。
 *
 * 预览**边调边出**，不设「预览」按钮：批量改名不可撤销，让人对着一份过期的
 * 列表点「应用」是最容易出事的地方，而实时预览让「看到的就是要执行的」。
 */
@Composable
private fun SelectionBar(
    state: FilesUiState,
    onToggleSelecting: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRulesChange: (RenameRules) -> Unit,
    onApply: () -> Unit,
) {
    val plan = state.renamePlan
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleSelecting) {
                    Text(if (state.selecting) "退出多选" else "多选")
                }
                if (state.selecting) {
                    TextButton(onClick = onSelectAll) { Text("全选文件") }
                    TextButton(onClick = onClear) { Text("清空") }
                    Spacer(Modifier.weight(1f))
                    Text("${state.selected.size} 项", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (!state.selecting) return@Column

            RulesEditor(state.renameRules, onRulesChange)

            plan?.let { p ->
                // 撞名时说清后果，而不是只把「应用」变灰 —— 灰按钮不解释为什么
                if (p.conflicts.isNotEmpty()) {
                    Text(
                        "有 ${p.conflicts.size} 个目标名重复或已存在，不能执行：" +
                            p.conflicts.take(3).joinToString() +
                            "（会覆盖掉别的文件）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!p.hasChanges) {
                    Text(
                        "当前规则不会改动任何名字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 预览：只列真正会变的，且最多 8 条（改几百个时列表会淹没界面）
                p.changed.take(8).forEach { item ->
                    Text(
                        "${item.from}  →  ${item.to}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (p.changed.size > 8) {
                    Text(
                        "…以及另外 ${p.changed.size - 8} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Button(onClick = onApply, enabled = p.canApply) {
                    Text("应用（改 ${p.changed.size} 项）")
                }
            }
        }
    }
}

/** 规则编辑：四个字段都直接改模型，预览跟着变。 */
@Composable
private fun RulesEditor(rules: RenameRules, onChange: (RenameRules) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RuleRow("查找", rules.find) { onChange(rules.copy(find = it)) }
        RuleRow("替换为", rules.replaceWith) { onChange(rules.copy(replaceWith = it)) }
        RuleRow("前缀", rules.prefix) { onChange(rules.copy(prefix = it)) }
        RuleRow("后缀", rules.suffix) { onChange(rules.copy(suffix = it)) }
        RuleRow("扩展名", rules.newExtension) { onChange(rules.copy(newExtension = it)) }
        RuleRow("起始编号", rules.numberFrom?.toString() ?: "") { text ->
            // 空串 = 不编号；非数字一律当不编号，不给「改了但没生效」的错觉
            onChange(rules.copy(numberFrom = text.trim().toIntOrNull()))
        }
    }
}

@Composable
private fun RuleRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(64.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 属性 / 摘要卡片。
 *
 * 不用对话框装它：摘要要能**逐行选中复制**（对比包的 MD5 正是主要用途），
 * 而 SHA-256 有 64 个字符，挤在对话框的窄宽度里会折行，抄起来更容易错。
 */
@Composable
private fun PropertiesCard(props: Properties, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("属性", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            PropLine("名字", props.name)
            PropLine("路径", props.path)
            PropLine("大小", humanSize(props.size))
            PropLine("修改时间", humanTime(props.modified))
            props.note?.let { PropLine("摘要", it) }
            props.digests?.let { d ->
                PropLine("MD5", d.md5)
                PropLine("SHA-1", d.sha1)
                PropLine("SHA-256", d.sha256)
            }
        }
    }
}

@Composable
private fun PropLine(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 单输入框对话框（改名用）。 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 确认对话框。
 *
 * 破坏性操作的按钮**写清动作**（「删除」）而不是「确定」——
 * 「确定」是那种点完之后想不起来自己确认了什么的东西。
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirm,
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
