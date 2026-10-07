package dev.smithy.feature.apk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithyRowTitle
import dev.smithy.design.SmithySkeletonList
import dev.smithy.design.SmithySpacing
import dev.smithy.design.rememberSmithyHaptics
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
 *
 * 排版：类型筛选项用药丸、搜索框用圆角药丸、列表行给**行首类型图标 + 资源名 +
 * 值**（原先两行都是同样大小同样颜色的灰字，看不出哪行是名字哪行是值）；
 * 批量替换收在底部一层底色里，它是这一页的「动作区」。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val haptics = rememberSmithyHaptics()

    Column(modifier.fillMaxSize()) {
        // ── 类型 + 搜索 ──
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.gap),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
            ) {
                RES_TYPES.forEach { (value, label) ->
                    Pill(label, { onType(value) }, active = state.resType == value)
                }
            }
            Spacer(Modifier.height(SmithySpacing.gap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = state.resFilter,
                    onValueChange = onFilter,
                    placeholder = "搜资源名或值",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(SmithySpacing.gap))
                Button(onClick = onLoad, enabled = state.busy == null) { Text("列出") }
            }
        }

        // ── 列表 ──
        Box(Modifier.weight(1f)) {
            if (state.resources.isEmpty()) {
                // 「还没读」和「读了但没结果」在这里是同一件事（列表只有点了「列出」
                // 才会变），所以只给一档空态，把下一步和最快的改法说清
                if (state.busy != null) {
                    SmithySkeletonList(Modifier.fillMaxSize())
                } else {
                    SmithyEmptyState(
                        icon = SmithyIcons.Resources,
                        title = "还没读资源表",
                        hint = "点上面的「列出」读一遍（默认只看 string）。改文案先在资源层改 —— " +
                            "200 组替换在资源层是 300ms，走 dex 层要 27 秒",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.resources.size) { i ->
                        val r = state.resources[i]
                        // 复杂条目（样式、数组）不给点：它们的值不是单个字符串，改不了
                        val editable = !r.isComplex && r.value != null
                        Row(
                            Modifier.fillMaxWidth()
                                .animateItem()
                                .clickable(enabled = editable) {
                                    haptics.tap()
                                    editing = r
                                }
                                .defaultMinSize(minHeight = SmithySpacing.rowHeight)
                                .padding(
                                    horizontal = SmithySpacing.rowHorizontal,
                                    vertical = SmithySpacing.rowVertical,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = resTypeIcon(r.type),
                                contentDescription = null,
                                modifier = Modifier.size(SmithySpacing.iconSize),
                                tint = if (editable) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    // 点不了的条目整行压暗：只把图标压暗不够明显
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                            )
                            Spacer(Modifier.width(SmithySpacing.iconGap))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.resName,
                                    style = SmithyRowTitle.copy(fontFamily = dev.smithy.design.SmithyMono),
                                    color = if (editable) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    r.value ?: if (r.isComplex) "（复杂条目，目前不在手机上改）" else "—",
                                    style = SmithyRowMeta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // 可改的条目给一个「改」的提示：没有它，用户不知道哪行能点
                            if (editable) {
                                Icon(
                                    imageVector = SmithyIcons.Rename,
                                    contentDescription = "改这一条",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 批量替换 ──
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
            ) {
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
                Spacer(Modifier.height(SmithySpacing.gap))
                OutlinedTextField(
                    value = batchText,
                    onValueChange = { batchText = it },
                    placeholder = { Text("确定=OK\n取消=Cancel") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SmithySpacing.gap))
                Button(
                    onClick = { onReplaceMany(batchText) },
                    enabled = state.busy == null && batchText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(SmithyIcons.Resources, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("执行替换")
                }
                Spacer(Modifier.height(SmithySpacing.gap))
                OutlinedButton(
                    onClick = onPickIcon,
                    enabled = state.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(SmithyIcons.KindPicture, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("换启动图标…")
                }
            }
        }
    }

    // ── 改单条的对话框 ──
    editing?.let { res ->
        TextInputDialog(
            title = res.resName,
            initial = res.value.orEmpty(),
            icon = resTypeIcon(res.type),
            fieldLabel = "新值",
            onConfirm = { value ->
                editing = null
                onSetResource(res.resName, value)
            },
            onDismiss = { editing = null },
        )
    }
}

/** 资源类型 → 图标。按类型给形状，扫一眼就知道这一屏是文案、图还是布局。 */
private fun resTypeIcon(type: String): ImageVector = when (type.lowercase()) {
    "drawable", "mipmap" -> SmithyIcons.KindPicture
    "color" -> SmithyIcons.Resources
    "layout", "xml" -> SmithyIcons.KindCode
    "raw" -> SmithyIcons.KindBinary
    else -> SmithyIcons.KindDoc
}
