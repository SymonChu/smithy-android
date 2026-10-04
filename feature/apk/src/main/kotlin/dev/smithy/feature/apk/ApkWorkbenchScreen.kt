package dev.smithy.feature.apk

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 「工作台」Tab。
 *
 * 选包 → 概览 / 代码 / 改动 三个标签 → 底部一条动作链：重打包 → 签名 → 安装。
 * M1 的验收路径就是这条链：搜到开屏文案、改掉、打包签名、装回手机。
 */
@Composable
fun ApkWorkbenchScreen(modifier: Modifier = Modifier) {
    val vm: ApkWorkbenchViewModel = viewModel()
    val state by vm.state.collectAsState()

    val picker = rememberLauncherForActivityResult(
        // 用 */* 而不是只限定 apk 的 MIME：不少文件管理器与网盘对
        // application/vnd.android.package-archive 的声明不一致，限定太死会选不中文件。
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::open) }

    val pick = { picker.launch(arrayOf("*/*")) }

    // 换图标单独一个选择器：只收图片，免得用户选到别的文件
    val iconPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::replaceIcon) }

    Box(modifier.fillMaxSize()) {
        when (val phase = state.phase) {
            is Phase.Empty -> EmptyState(pick)
            is Phase.Loading -> LoadingState(phase.stage)
            is Phase.Failed -> FailedState(phase, pick)
            is Phase.Ready -> ReadyContent(state, vm, pick) { iconPicker.launch(arrayOf("image/*")) }
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有打开任何 APK", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "选一个安装包：看它的构成，改里面的文案或代码，再打包签名装回手机",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPick) { Text("选择 APK") }
        Spacer(Modifier.height(12.dp))
        Text(
            "请只处理你自己有权分析的包",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingState(stage: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stage, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedState(state: Phase.Failed, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("打不开这个包", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(state.message, style = MaterialTheme.typography.bodySmall)
        state.hint?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                "建议：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPick) { Text("换一个") }
    }
}

@Composable
private fun ReadyContent(
    state: WorkbenchUiState,
    vm: ApkWorkbenchViewModel,
    onPick: () -> Unit,
    onPickIcon: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.meta?.appLabel ?: state.sourceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Mono(state.meta?.packageName ?: "", Modifier.padding(top = 2.dp))
            }
            TextButton(onClick = onPick) { Text("换一个") }
            TextButton(onClick = vm::close) { Text("关闭") }
        }

        TabRow(selectedTabIndex = state.tab.ordinal) {
            WorkbenchTab.entries.forEach { t ->
                Tab(
                    selected = state.tab == t,
                    onClick = { vm.selectTab(t) },
                    text = { Text(t.label) },
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when (state.tab) {
                WorkbenchTab.OVERVIEW -> state.meta?.let {
                    OverviewTab(it, state.sourceName, state.entryCount)
                }

                WorkbenchTab.CODE -> CodeTab(
                    state = state,
                    onQuery = vm::setQuery,
                    onScope = vm::setScope,
                    onSearch = vm::search,
                    onOpenClass = vm::openClass,
                    onCodeView = vm::showCodeView,
                    onReplace = vm::replaceString,
                )

                WorkbenchTab.RESOURCES -> ResourcesTab(
                    state = state,
                    onType = vm::setResType,
                    onFilter = vm::setResFilter,
                    onLoad = vm::loadResources,
                    onSetResource = vm::setResource,
                    onReplaceMany = vm::replaceMany,
                    onPickIcon = onPickIcon,
                )

                WorkbenchTab.PATCHES -> PatchesTab(state = state, onRevert = vm::revert)
            }
        }

        ActionBar(state, vm)
    }
}

/** 打包链路的动作条。三步的顺序不能颠倒：签名签的是上一次打包的产物。 */
@Composable
private fun ActionBar(state: WorkbenchUiState, vm: ApkWorkbenchViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (state.busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(state.busy, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }
        state.message?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val idle = state.busy == null
            OutlinedButton(
                onClick = vm::rebuild,
                enabled = idle && state.patches.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("重打包") }

            OutlinedButton(
                onClick = vm::sign,
                enabled = idle && state.rebuiltPath != null,
                modifier = Modifier.weight(1f),
            ) { Text("签名") }

            Button(
                onClick = vm::install,
                enabled = idle && (state.signedPath != null || state.rebuiltPath != null),
                modifier = Modifier.weight(1f),
            ) { Text("安装") }
        }
    }
}
