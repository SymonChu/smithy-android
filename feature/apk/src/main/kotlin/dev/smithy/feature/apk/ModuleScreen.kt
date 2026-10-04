package dev.smithy.feature.apk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import dev.smithy.design.SmithySpacing
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog

/**
 * 模块页：Magisk / Zygisk 模块的查看、改动、打包与刷入。
 *
 * 和改包工作台分开成两个页面，是因为它们处理的是**两种包**：
 * 一个是 apk（有资源表、签名、dex），一个是模块 zip（只有纯文本加一堆 so）。
 * 混在一个界面里，每个按钮都要先问「现在是哪种」。
 *
 * 这一页的重点是**把结构问题摆在眼前**：缺当前设备 ABI、文件套了一层目录、
 * 还带着 disable 标记 —— 这些都能让模块「装上了但不生效，且不报错」。
 */
@Composable
fun ModuleScreen(
    state: ModuleUiState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onVersion: (String) -> Unit,
    onVersionCode: (String) -> Unit,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSaveProp: () -> Unit,
    onEditEntry: (String) -> Unit,
    onEditingText: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEntry: () -> Unit,
    onInstall: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onScheduleRemove: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onRestartZygote: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmZygote by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }

    // 正在编辑条目时占满整屏：改脚本要看得见上下文，旁边留一列按钮反而挤
    state.editing?.let { path ->
        Column(modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(path, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelEdit) { Text("取消") }
                    Button(onClick = onSaveEntry) { Text("保存") }
                }
            }
            OutlinedTextField(
                value = state.editingText,
                onValueChange = onEditingText,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeaderRow(state, onOpen, onClose, onRefresh)

        state.busy?.let { BusyBar(it) }
        state.message?.let { MessageBar(it, state.isError) }

        if (state.zipPath == null) {
            EmptyHint()
        } else {
            PropCard(state, onVersion, onVersionCode, onName, onDescription, onSaveProp)
            StructureCard(state)
            EntriesCard(state, onEditEntry)
            InstallCard(state, onInstall)
        }

        DeviceCard(
            state = state,
            onSetEnabled = onSetEnabled,
            onScheduleRemove = onScheduleRemove,
            onUninstall = { confirmUninstall = it },
            onRestartZygote = { confirmZygote = true },
        )
    }

    if (confirmZygote) {
        AlertDialog(
            onDismissRequest = { confirmZygote = false },
            title = { Text("软重启 zygote？") },
            text = {
                Text(
                    "所有正在运行的应用都会被重启，**没保存的东西会丢**。\n\n" +
                        "这是让新装的 Zygisk 模块生效的最快方式（不用整机重启）。" +
                        "如果你现在有别的应用正开着没保存，先切过去保存。",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmZygote = false; onRestartZygote() }) { Text("重启") }
            },
            dismissButton = { TextButton(onClick = { confirmZygote = false }) { Text("取消") } },
        )
    }

    confirmUninstall?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text("立即删除「$id」？") },
            text = { Text("只对已停用的模块允许 —— 还在启用时它的 so 可能正被映射进进程，删掉会让进程行为不可预期。") },
            confirmButton = {
                TextButton(onClick = { confirmUninstall = null; onUninstall(id) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun HeaderRow(state: ModuleUiState, onOpen: () -> Unit, onClose: () -> Unit, onRefresh: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.zipPath?.let { it.substringAfterLast('/') } ?: "模块",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("刷新") }
            if (state.zipPath == null) {
                Button(onClick = onOpen) { Text("打开模块 zip") }
            } else {
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun BusyBar(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(text, Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MessageBar(text: String, isError: Boolean) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = bg) {
        Text(text, Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("还没打开模块", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "点右上角选一个模块 zip。模块 zip 的条目直接在根上（module.prop、zygisk/、service.sh），" +
                "不套一层目录。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "改包（apk）在「工作台」那个页面 —— 这里是刷机模块，两种包不是一回事。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PropCard(
    state: ModuleUiState,
    onVersion: (String) -> Unit,
    onVersionCode: (String) -> Unit,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSave: () -> Unit,
) {
    val prop = state.prop ?: return
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("元数据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "id：${prop.id}（不能改 —— Magisk 用目录名当模块标识，改了会和已装的对不上）",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.draftName,
            onValueChange = onName,
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.draftVersion,
                onValueChange = onVersion,
                label = { Text("版本") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.draftVersionCode,
                onValueChange = onVersionCode,
                label = { Text("版本号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.draftDescription,
            onValueChange = onDescription,
            label = { Text("描述") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSave) { Text("保存改动（另存为新 zip）") }
        Spacer(Modifier.height(4.dp))
        Text(
            "不覆盖原包：产物写成 <原名>-edited.zip。刷进去不对还能拿原包再来一次。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun StructureCard(state: ModuleUiState) {
    val layout = state.layout ?: return
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("结构", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        if (layout.isZygisk) {
            Text(
                "ABI 覆盖：${layout.knownAbis.joinToString("  ").ifEmpty { "（没有有效的）" }}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Text("不是 Zygisk 模块（没有 zygisk 下的 so）", style = MaterialTheme.typography.bodySmall)
        }
        if (layout.scripts.isNotEmpty()) {
            Text("脚本：${layout.scripts.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
        }
        if (layout.hasSystemOverlay) {
            Text("含 system/ overlay", style = MaterialTheme.typography.labelSmall)
        }

        layout.warnings.forEach { w ->
            Spacer(Modifier.height(6.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    w,
                    Modifier.fillMaxWidth().padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EntriesCard(state: ModuleUiState, onEditEntry: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("文件（${state.entries.size} 个）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "点文本条目进去改。二进制条目（so / dex）点不了 —— 文本编辑器改不了它们，" +
                "要改字节得用十六进制那条路。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        state.entries.forEach { path ->
            val editable = isTextEntry(path)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (editable) {
                    TextButton(onClick = { onEditEntry(path) }) { Text("编辑") }
                } else {
                    Text("二进制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    HorizontalDivider()
}

/** 能当文本编辑的条目。和文件页那条判断同一个思路：按扩展名。 */
private fun isTextEntry(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext !in setOf("so", "dex", "arsc", "png", "jpg", "jpeg", "webp", "gif", "ttf", "otf", "zip", "jar")
}

@Composable
private fun InstallCard(state: ModuleUiState, onInstall: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("刷入", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            state.packaged?.let { "将刷入改动后的产物：${it.substringAfterLast('/')}" }
                ?: "还没改过，将刷入原包",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = onInstall, enabled = state.rootOk) { Text("刷入设备") }
        if (!state.rootOk) {
            Spacer(Modifier.height(4.dp))
            Text(
                "刷入需要 root（Shizuku 权限不够：改不了 /data/adb/modules，也调不了 Magisk 的 CLI）。" +
                    "先去文件页授权 root。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun DeviceCard(
    state: ModuleUiState,
    onSetEnabled: (String, Boolean) -> Unit,
    onScheduleRemove: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onRestartZygote: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("设备上的模块", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        if (!state.rootOk) {
            Text(
                "看不到已安装的模块：需要 root。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (state.installed.isEmpty()) {
            Text("设备上还没有已安装的模块", style = MaterialTheme.typography.bodySmall)
        } else {
            state.installed.forEach { id ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSetEnabled(id, false) }) { Text("停用") }
                    TextButton(onClick = { onSetEnabled(id, true) }) { Text("启用") }
                    TextButton(onClick = { onScheduleRemove(id) }) { Text("标记卸载") }
                    TextButton(onClick = { onUninstall(id) }) {
                        Text("立即删", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "停用 / 启用 / 标记卸载都是**下次重启后生效**。想反悔：停用就是删掉目录里的 disable 标记，" +
                "标记卸载就是删掉 remove 标记。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRestartZygote,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("软重启 zygote（让新模块生效）") }
        Spacer(Modifier.height(4.dp))
        Text(
            "会影响所有正在运行的应用 —— 进程全部重建，没保存的东西会丢。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
