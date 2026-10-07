package dev.smithy.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyDialogTitle
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.smithy.fs.HexEdit
import dev.smithy.fs.humanSize

/**
 * 十六进制查看 / 编辑。
 *
 * 只渲染**一窗**（见 `HexState`），所以几十上百 MB 的文件也能看，不会 OOM。
 *
 * 每行都点得动：点了就拿那一行的偏移做起点，问你要写什么字节。
 * 这样「在某处改几个字节」是两次点击，而不是先抄下偏移再去别处输一遍。
 */
@Composable
fun HexScreen(
    state: HexState,
    onClose: () -> Unit,
    onGoto: (String) -> Unit,
    onPage: (Int) -> Unit,
    onSave: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var gotoText by remember { mutableStateOf("") }
    var writeAt by remember { mutableStateOf<String?>(null) }

    // 窄屏每行 8 字节：地址列 + 16 个字节 + 文本列约 73 个等宽字符，手机竖屏放不下，
    // 硬塞会把字号压到看不清 —— 宁可少看一半，也要看得清
    val perRow = if (LocalConfiguration.current.screenWidthDp < 400) 8 else 16

    Column(modifier.fillMaxSize()) {
        // 顶栏和其它整屏页面统一：返回图标代替「关闭」文字按钮 ——
        // 这一屏是「从文件列表点进来的」，返回才是它真实的关系
        SmithyTopBar(
            title = "十六进制",
            subtitle = state.path,
            onBack = onClose,
        )

        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        ) {
            TextField(
                value = gotoText,
                onValueChange = { gotoText = it },
                modifier = Modifier.width(132.dp),
                singleLine = true,
                placeholder = { Text("偏移(hex)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = MaterialTheme.shapes.small,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            // 「跳转 / 上一窗 / 下一窗」换成三个图标：这一行本来就窄，
            // 三个文字按钮会把偏移输入框挤到只剩几个字符
            val haptics = rememberSmithyHaptics()
            SmithyIconButton(
                icon = SmithyIcons.Search,
                contentDescription = "跳到偏移",
                onClick = {
                    haptics.tap()
                    onGoto(gotoText)
                },
                tint = MaterialTheme.colorScheme.primary,
            )
            SmithyIconButton(
                icon = SmithyIcons.Up,
                contentDescription = "上一窗",
                onClick = { onPage(-1) },
                enabled = state.hasPrev,
            )
            SmithyIconButton(
                icon = SmithyIcons.Down,
                contentDescription = "下一窗",
                onClick = { onPage(1) },
                enabled = state.hasNext,
            )
        }

        Text(
            "${HexEdit.formatOffset(state.windowStart)} – ${HexEdit.formatOffset(state.windowEnd)}" +
                "  /  ${humanSize(state.size)}",
            style = SmithyRowMeta.copy(fontFamily = SmithyMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = SmithySpacing.gutter,
                vertical = 2.dp,
            ),
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            // key 用偏移：翻页时行会整批换掉，稳定 key 能让 Compose 复用而不是重建
            items(state.rows, key = { it.offset }) { row ->
                HexRow(row, perRow) { writeAt = HexEdit.formatOffset(row.offset) }
            }
        }
    }

    writeAt?.let { offset ->
        WriteHexDialog(
            offset = offset,
            onConfirm = { off, hex ->
                writeAt = null
                onSave(off, hex)
            },
            onDismiss = { writeAt = null },
        )
    }
}

@Composable
private fun HexRow(row: HexEdit.Row, perRow: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            HexEdit.formatOffset(row.offset),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "  " + HexEdit.formatHex(row.bytes, perRow),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            " " + HexEdit.formatAscii(row.bytes),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 问「写什么字节」。
 *
 * 偏移预填成点的那一行，**但允许改** —— 常见需求是从那一行的某个位置开始写，
 * 而不是整行开头。写多少字节由输入长度决定（等长覆盖，不含插入/删除）。
 */
@Composable
private fun WriteHexDialog(
    offset: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var off by remember { mutableStateOf(offset) }
    var hex by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { SmithyDialogTitle(icon = SmithyIcons.Hex, text = "写字节") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = off,
                    onValueChange = { off = it },
                    label = { Text("偏移(hex)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("字节，如 90 90 或 9090") },
                    singleLine = true,
                )
                Text(
                    "只做等长覆盖，不改文件长度。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(off, hex) }) { Text("写入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
