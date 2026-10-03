package dev.smithy.feature.apk

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 「工作台」Tab 的入口。
 *
 * M0 只做只读分析：选包 → 出报告。
 * M1 起这里会长出 代码 / 资源 / 改动 三个标签页，以及底部的 重打包 / 签名 / 安装 动作条。
 */
@Composable
fun ApkWorkbenchScreen(modifier: Modifier = Modifier) {
    val vm: ApkReportViewModel = viewModel()
    val state by vm.state.collectAsState()

    val picker = rememberLauncherForActivityResult(
        // 用 */* 而不是只限定 apk 的 MIME：不少文件管理器与网盘对
        // application/vnd.android.package-archive 的声明不一致，限定太死会选不中文件。
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::open) }

    ApkReportScreen(
        state = state,
        onPick = { picker.launch(arrayOf("*/*")) },
        onClose = vm::close,
        modifier = modifier,
    )
}
