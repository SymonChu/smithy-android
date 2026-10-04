package dev.smithy.feature.apk

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smithy.engine.DexQuery

/** 「代码」标签：搜 → 看 → 改。 */
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
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    label = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSearch, enabled = state.busy == null) { Text("搜") }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DexQuery.Scope.entries.forEach { s ->
                    FilterChip(
                        selected = state.scope == s,
                        onClick = { onScope(s) },
                        label = { Text(scopeLabel(s)) },
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                state.openedClass != null -> ClassView(state, onCodeView, onOpenClass)
                state.hits.isNotEmpty() -> HitList(state, onOpenClass)
                else -> Hint("搜类名、方法名、字段名或字符串常量。\n匹配是子串语义、大小写敏感的。")
            }
        }

        // ── 替换字符串（M1 的主力改法）──
        Column(Modifier.padding(12.dp)) {
            Text(
                "改字符串常量（只动命中的 dex，不重编整个包）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text("原文") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text("改成") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onReplace(from, to) },
                enabled = state.busy == null && from.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("替换") }
        }
    }
}

@Composable
private fun HitList(state: WorkbenchUiState, onOpenClass: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(state.hits.size) { i ->
            val hit = state.hits[i]
            Column(
                Modifier.fillMaxWidth()
                    .clickable { onOpenClass(hit.className) }
                    .padding(vertical = 8.dp),
            ) {
                Text(hit.className, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hit.methodSig?.let {
                    Mono(it, Modifier.padding(top = 2.dp))
                }
                Text(
                    hit.snippet.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 2.dp)) {
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(state.openedClass!!, Modifier.weight(1f))
            TextButton(onClick = { onOpenClass(state.openedClass!!) }) { Text("重载") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodeView.entries.forEach { v ->
                FilterChip(
                    selected = state.codeView == v,
                    onClick = { onCodeView(v) },
                    label = { Text(v.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (code == null) {
            Hint("正在准备代码…")
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
 */
@Composable
private fun CodeBlock(code: String, modifier: Modifier = Modifier) {
    val lines = remember(code) { code.lines() }
    Box(
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f))
            .horizontalScroll(rememberScrollState()),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines.size) { i ->
                Row {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp).padding(end = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    Text(
                        lines[i],
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun scopeLabel(scope: DexQuery.Scope) = when (scope) {
    DexQuery.Scope.CLASS -> "类"
    DexQuery.Scope.METHOD -> "方法"
    DexQuery.Scope.FIELD -> "字段"
    DexQuery.Scope.STRING -> "字符串"
}
