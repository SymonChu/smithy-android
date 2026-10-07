package dev.smithy.feature.apk

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyNumeric
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySkeletonList
import dev.smithy.design.SmithySpacing
import dev.smithy.design.icon
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.design.tint
import dev.smithy.engine.ApkEntry
import dev.smithy.fs.humanSize

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
 *
 * 行的解剖照文件页的**包内条目**（`ZipRow`）：行首类型图标 + 等宽路径 + 右对齐等宽体积，
 * 点一下**原地展开**出这一行的动作（替换 / 删除），删除走破坏性确认。
 * 之前是「点一行弹一个对话框，对话框里再选动作」—— 多一层，而且对话框标题是一长串路径。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilesTab(
    state: WorkbenchUiState,
    onFilter: (String) -> Unit,
    onLoad: () -> Unit,
    onReplace: (String, Uri) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开了动作的那一行（null = 都收着）。收着是默认：一屏条目里，动作行会把列表拉长一倍
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf<ApkEntry?>(null) }
    val haptics = rememberSmithyHaptics()

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
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.gap),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
            ) {
                QUICK_PREFIXES.forEach { (prefix, label) ->
                    Pill(label, { onFilter(prefix) }, active = state.entryFilter == prefix)
                }
            }
            Spacer(Modifier.height(SmithySpacing.gap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = state.entryFilter,
                    onValueChange = onFilter,
                    placeholder = "路径前缀，如 res/",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(SmithySpacing.gap))
                Button(onClick = onLoad, enabled = state.busy == null) { Text("列出") }
            }
        }

        // ── 条目列表 ──
        if (state.entries.isEmpty()) {
            // 「还没列」和「列了但一条没有」在这里是同一件事：列表只有点了「列出」才会变
            if (state.busy != null) {
                SmithySkeletonList(Modifier.fillMaxSize())
            } else {
                SmithyEmptyState(
                    icon = SmithyIcons.Package,
                    title = "还没列过包里的文件",
                    hint = "点上面的「列出」；也可以先用那排药丸直接看 res / lib / assets / META-INF / dex",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries.size) { i ->
                    val e = state.entries[i]
                    if (e.isDirectory) return@items
                    val kind = kindOf(e.path)
                    val open = expanded == e.path
                    Column(
                        Modifier.fillMaxWidth()
                            .animateItem()
                            .clickable {
                                haptics.tap()
                                expanded = if (open) null else e.path
                            }
                            .padding(
                                horizontal = SmithySpacing.rowHorizontal,
                                vertical = SmithySpacing.rowVertical,
                            ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = kind.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = kind.tint(),
                            )
                            Spacer(Modifier.width(SmithySpacing.iconGap))
                            Text(
                                e.path,
                                style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // 体积单独一列、右对齐、等宽 + 表格数字 —— 参差的数字扫一眼比不出大小
                            Text(
                                humanSize(e.size),
                                style = SmithyNumeric,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 展开箭头会转：不转的话，展开后箭头还指着右边，看起来像「还能再点进去」
                            Icon(
                                imageVector = SmithyIcons.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "压缩后 ${humanSize(e.compressedSize)}",
                            style = SmithyRowMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = SmithySpacing.iconGap + 18.dp),
                        )
                        AnimatedVisibility(
                            visible = open,
                            enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
                            exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
                        ) {
                            Row(
                                Modifier.padding(top = SmithySpacing.gap)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
                            ) {
                                Pill(
                                    "替换…",
                                    {
                                        expanded = null
                                        pendingPath = e.path
                                        picker.launch(arrayOf("*/*"))
                                    },
                                    icon = SmithyIcons.Upload,
                                )
                                Pill(
                                    "删除",
                                    {
                                        expanded = null
                                        confirmingDelete = e
                                    },
                                    icon = SmithyIcons.Delete,
                                    danger = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除要二次确认 —— 它改的是包的内容，不该一次点击就落定
    confirmingDelete?.let { e ->
        ConfirmDialog(
            title = "删除这个条目？",
            body = "${e.path}\n\n" +
                "删除只是记在改动里，重打包时才跳过它 —— 打包之前都能在「改动」标签回退。",
            confirm = "删除",
            destructive = true,
            onConfirm = {
                confirmingDelete = null
                onDelete(e.path)
            },
            onDismiss = { confirmingDelete = null },
        )
    }
}
