package dev.smithy.feature.apk

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithyNumeric
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithyRowTitle
import dev.smithy.design.SmithySkeletonList
import dev.smithy.design.SmithySpacing
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.engine.DexQuery

/**
 * 「代码」标签：搜 → 看 → 改。
 *
 * 三段结构不变（搜索 / 结果 / 替换），但每一段的解剖和文件页对齐：
 * 输入框改成圆角无描边的搜索条（页面上唯一的输入框）、筛选项用药丸、结果行给
 * **行首图标 + 主文案 + 副行**（不再是一条条光秃秃的路径）、空结果给图标化空态、
 * 扫描中给骨架（原先「正在准备代码…」和「没有结果」共用一行灰字）。
 */
@Composable
internal fun CodeTab(
    state: WorkbenchUiState,
    onQuery: (String) -> Unit,
    onScope: (DexQuery.Scope) -> Unit,
    onSearch: () -> Unit,
    onOpenClass: (String) -> Unit,
    onCodeView: (CodeView) -> Unit,
    onReplace: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        // ── 搜索 ──
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = state.query,
                    onValueChange = onQuery,
                    placeholder = "搜类名 / 方法名 / 字符串常量",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(SmithySpacing.gap))
                Button(onClick = onSearch, enabled = state.busy == null) { Text("搜索") }
            }
            Spacer(Modifier.height(SmithySpacing.gap))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
            ) {
                DexQuery.Scope.entries.forEach { s ->
                    Pill(scopeLabel(s), { onScope(s) }, active = state.scope == s)
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                state.openedClass != null -> ClassView(state, onCodeView, onOpenClass)
                state.hits.isNotEmpty() -> HitList(state, onOpenClass)
                state.busy != null -> SmithySkeletonList(Modifier.fillMaxSize(), rows = 8)
                else -> SmithyEmptyState(
                    icon = SmithyIcons.Search,
                    title = "搜类名、方法名、字段名或字符串常量",
                    hint = "匹配是子串语义、大小写敏感的",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── 替换字符串（M1 的主力改法）──
        // 单独一层底色：它是这一页的「动作区」，和内容区分开（同文件页的底部动作条）
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
            ) {
                Text(
                    "改字符串常量（只动命中的 dex，不重编整个包）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(SmithySpacing.gap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it },
                        label = { Text("原文") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(SmithySpacing.gap))
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        label = { Text("改成") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(SmithySpacing.gap))
                Button(
                    onClick = { onReplace(from, to) },
                    enabled = state.busy == null && from.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(SmithyIcons.Rename, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("替换")
                }
            }
        }
    }
}

/**
 * 命中列表。
 *
 * 行解剖照文件页：行首一个 [SmithySpacing.iconSize] 的图标槽、主文案（类名）走
 * [SmithyRowTitle]、副行走 [SmithyRowMeta]、最后一行给「命中在哪个 dex」——
 * 光有类名的话，同一个类名出现在多个 dex 里分不清点进去看的是哪一份。
 */
@Composable
private fun HitList(state: WorkbenchUiState, onOpenClass: (String) -> Unit) {
    val haptics = rememberSmithyHaptics()
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.hits.size) { i ->
            val hit = state.hits[i]
            Row(
                Modifier.fillMaxWidth()
                    .clickable { haptics.tap(); onOpenClass(hit.className) }
                    .defaultMinSize(minHeight = SmithySpacing.rowHeight)
                    .padding(
                        horizontal = SmithySpacing.rowHorizontal,
                        vertical = SmithySpacing.rowVertical,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = SmithyIcons.KindCode,
                    contentDescription = null,
                    modifier = Modifier.size(SmithySpacing.iconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(SmithySpacing.iconGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        hit.className,
                        style = SmithyRowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    hit.methodSig?.let {
                        Mono(it, Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        hit.snippet.take(160),
                        style = SmithyRowMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        hit.dexName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassView(
    state: WorkbenchUiState,
    onCodeView: (CodeView) -> Unit,
    onOpenClass: (String) -> Unit,
) {
    val code = when (state.codeView) {
        CodeView.JAVA -> state.javaCode
        CodeView.SMALI -> state.smaliCode
    }
    Column(Modifier.fillMaxSize()) {
        // 顶条：当前看的是哪个类 + 重载。重载收成图标按钮 —— 它每次都在，
        // 但不该和类名抢那一行的宽度
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = SmithySpacing.gutter,
                    end = SmithySpacing.gap,
                    top = SmithySpacing.barVertical,
                    bottom = SmithySpacing.barVertical,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.openedClass!!,
                    style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SmithyIconButton(
                    icon = SmithyIcons.Refresh,
                    contentDescription = "重新加载这个类",
                    onClick = { onOpenClass(state.openedClass!!) },
                )
            }
        }
        Row(
            Modifier.padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.gap),
            horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        ) {
            CodeView.entries.forEach { v ->
                Pill(v.label, { onCodeView(v) }, active = state.codeView == v)
            }
        }
        if (code == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BusyRow("正在准备代码…")
            }
        } else {
            CodeBlock(code, Modifier.weight(1f))
        }
    }
}

/**
 * 按行渲染。
 *
 * 用 LazyColumn 而不是一个大 Text：一个类的 smali 常有几千行，
 * 整块文本会让滚动掉帧，而按行只渲染可见部分。
 *
 * 行号用 [SmithyNumeric]（等宽 + tabular 数字）：不这样的话，位数一变整列代码会左右抖。
 */
@Composable
private fun CodeBlock(code: String, modifier: Modifier = Modifier) {
    val lines = remember(code) { code.lines() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lines.size) { i ->
                    Row {
                        Text(
                            "${i + 1}",
                            style = SmithyNumeric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(44.dp).padding(end = SmithySpacing.gap),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                        Text(
                            lines[i],
                            style = SmithyRowMeta.copy(fontFamily = SmithyMono, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private fun scopeLabel(scope: DexQuery.Scope) = when (scope) {
    DexQuery.Scope.CLASS -> "类"
    DexQuery.Scope.METHOD -> "方法"
    DexQuery.Scope.FIELD -> "字段"
    DexQuery.Scope.STRING -> "字符串"
}
