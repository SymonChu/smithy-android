package dev.smithy.feature.apk

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithySkeletonList
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.File

/**
 * 「工作台」Tab。
 *
 * 选包 → 概览 / 代码 / 资源 / 文件 / 改动 五个标签 → 底部一条动作链：重打包 → 签名 → 安装。
 * M1 的验收路径就是这条链：搜到开屏文案、改掉、打包签名、装回手机。
 *
 * 页面的层级照**文件页**那套来分：顶栏（当前是哪个包）→ 标签条（这一页看哪一段，
 * 底色 `surfaceContainerLow`）→ 内容（不铺底色）→ 动作条（同一层底色）。切标签走淡入
 * 淡出，和一级导航的切换用同一条曲线、同一档时长。
 */
@Composable
fun ApkWorkbenchScreen(
    incomingUri: Uri? = null,
    onIncomingConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm: ApkWorkbenchViewModel = viewModel()
    val state by vm.state.collectAsState()

    // 外部送进来的包直接打开，并**立刻消费掉**这个事件：
    // 留着的话每次重组都会重新打开一遍，用户会看到界面莫名其妙回到初始状态
    LaunchedEffect(incomingUri) {
        incomingUri?.let {
            vm.open(it)
            onIncomingConsumed()
        }
    }

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

    // 导出报告：保存位置交给系统选（SAF），不自己猜路径
    val reportSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri -> uri?.let(vm::exportReport) }

    // 模块是同一页里的另一种包，所以它有自己的状态机，和 apk 那个互不干扰
    val moduleVm: ModuleViewModel = viewModel()
    val moduleState by moduleVm.state.collectAsState()

    // 模块 zip 要反复 seek（它是 zip），而 OpenDocument 只给 URI —— 先拷进缓存目录。
    // 和「导入包」那边同一个理由：URI 流不支持随机访问
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val modulePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val dst = withContext(Dispatchers.IO) {
                    val f = File(ctx.cacheDir, "module-${System.currentTimeMillis()}.zip")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    f
                }
                moduleVm.open(dst.absolutePath)
            }
        }
    }

    /**
     * 灌一份工具链包进来。
     *
     * 为什么要从本地选文件而不是「让 App 自己下」：给 arm64 安卓用的 clang 官方没有现成的
     * （NDK 只有 x86_64/darwin/windows 宿主机版，LLVM 也不发 android 目标），只能在外面产出一份
     * 再传进手机。所以这条「从文件灌进去」的路是必需项，不是备胎。
     */
    val bundlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val dst = withContext(Dispatchers.IO) {
                    // 归档要能按名字判断 zip/tar、还要反复读：先落到缓存目录
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "toolchain.zip"
                    val f = File(ctx.cacheDir, "addon-${System.currentTimeMillis()}-$name")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    f
                }
                moduleVm.importComponent(dst)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // 标签行常驻在「有 apk」或「停在模块标签」时。
        // apk 那几档在没打开包时点了也没内容，所以那种情况下不显示整行，
        // 而是在空状态里给一个「打开模块 zip」的入口
        if (state.phase is Phase.Ready || state.tab == WorkbenchTab.MODULE) {
            WorkbenchTabStrip(selected = state.tab, onSelect = vm::selectTab)
        }

        Box(Modifier.weight(1f)) {
            if (state.tab == WorkbenchTab.MODULE) {
                ModuleScreen(
                    state = moduleState,
                    onOpen = { modulePicker.launch(arrayOf("*/*")) },
                    // 从骨架新建：结构约束在 core:fs 的 ModuleScaffold 里（id 合法性、
                    // 条目在根上、脚本与 system.prop），ViewModel 只管落盘与打开
                    onCreate = moduleVm::create,
                    onClose = moduleVm::close,
                    onVersion = moduleVm::onVersion,
                    onVersionCode = moduleVm::onVersionCode,
                    onName = moduleVm::onName,
                    onDescription = moduleVm::onDescription,
                    onSaveProp = moduleVm::saveProp,
                    onEditEntry = moduleVm::startEdit,
                    onEditingText = moduleVm::updateEditing,
                    onCancelEdit = moduleVm::cancelEdit,
                    onSaveEntry = moduleVm::saveEntry,
                    onInstall = moduleVm::install,
                    // native 编译：zygisk 那一档只有编出 zygisk/<abi>.so 才会生效
                    onCompile = { moduleVm.compile() },
                    onInstallComponent = moduleVm::installComponent,
                    onImportComponent = { bundlePicker.launch(arrayOf("*/*")) },
                    onPrepareRootfs = moduleVm::prepareRootfsEnv,
                    onSetEnabled = moduleVm::setEnabled,
                    onScheduleRemove = moduleVm::scheduleRemove,
                    onUninstall = moduleVm::uninstallNow,
                    onRestartZygote = moduleVm::restartZygote,
                    onRefresh = moduleVm::refreshInstalled,
                )
            } else {
                when (val phase = state.phase) {
                    is Phase.Empty -> EmptyState(pick, onOpenModule = { vm.selectTab(WorkbenchTab.MODULE) })
                    is Phase.Loading -> LoadingState(phase.stage)
                    is Phase.Failed -> FailedState(phase, pick)
                    is Phase.Ready -> ReadyContent(
                        state = state,
                        vm = vm,
                        onPick = pick,
                        onPickIcon = { iconPicker.launch(arrayOf("image/*")) },
                        onExportReport = {
                            reportSaver.launch("${state.meta?.packageName ?: "apk"}-report.md")
                        },
                    )
                }
            }
        }
    }
}

/**
 * 标签条。
 *
 * 不再是 M3 的 [androidx.compose.material3.ScrollableTabRow]（下划线指示器 + 等宽分栏）：
 * 那是「顶部导航」的形状语言，而这一条说的是「同一页里换一段看」。改成**全圆角药丸**，
 * 和文件页底部标签条、面包屑、工具条是同一套形状；每个标签再给一个图标 —— 六个中文
 * 标签光靠字读起来慢，图标能先认出来。
 *
 * 选中态填色并带过渡动画：切标签是高频动作，硬切会闪。
 */
@Composable
private fun WorkbenchTabStrip(
    selected: WorkbenchTab,
    onSelect: (WorkbenchTab) -> Unit,
) {
    val haptics = rememberSmithyHaptics()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
            horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkbenchTab.entries.forEach { tab ->
                val active = tab == selected
                val container by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    animationSpec = SmithyMotion.state(),
                    label = "workbenchTabContainer",
                )
                val content = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    onClick = {
                        // 已经在这一档上就别再触发一次切换和震动
                        if (active) return@Surface
                        haptics.toggle()
                        onSelect(tab)
                    },
                    color = container,
                    shape = RoundedCornerShape(99.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = content)
                        Spacer(Modifier.width(6.dp))
                        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 标签 → 图标。六个标签一一对应，不引「图标一览」当索引表。 */
private val WorkbenchTab.icon: ImageVector
    get() = when (this) {
        WorkbenchTab.OVERVIEW -> SmithyIcons.Dashboard
        WorkbenchTab.CODE -> SmithyIcons.KindCode
        WorkbenchTab.RESOURCES -> SmithyIcons.Resources
        WorkbenchTab.FILES -> SmithyIcons.Package
        WorkbenchTab.PATCHES -> SmithyIcons.Patches
        WorkbenchTab.MODULE -> SmithyIcons.ModuleTab
    }

/**
 * 没打开任何包。
 *
 * 不再是「标题 + 两行灰字 + 两个按钮」的裸排版：图标化空态说清**这是什么情况、
 * 每种包能做什么、下一步点哪里**，两个入口（apk / 模块 zip）留在这里 ——
 * 没打开包时标签行是不显示的，这里是唯一入口。
 */
@Composable
private fun EmptyState(onPick: () -> Unit, onOpenModule: () -> Unit) {
    SmithyEmptyState(
        icon = SmithyIcons.KindApk,
        title = "还没有打开任何包",
        hint = "apk：看它的构成，改里面的文案或代码，再打包签名装回手机\n" +
            "模块：Magisk / Zygisk 模块 zip 的查看、改动、打包与刷入",
        modifier = Modifier.fillMaxSize(),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = onPick) {
                    Icon(SmithyIcons.KindApk, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择 APK")
                }
                Spacer(Modifier.height(SmithySpacing.gap))
                OutlinedButton(onClick = onOpenModule) {
                    Icon(SmithyIcons.KindModule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打开模块 zip")
                }
                Spacer(Modifier.height(SmithySpacing.section))
                Text(
                    "请只处理你自己有权分析的包",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * 正在读包。
 *
 * 原先是一个居中转圈 + 一行阶段文字。改成**忙碌行 + 骨架**：骨架提前把「等会儿这里会有
 * 一屏列表」的布局告诉用户，内容出现时不会整页跳一下（和文件页加载目录时同一个处理）。
 */
@Composable
private fun LoadingState(stage: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = SmithySpacing.gutter)) {
        Spacer(Modifier.height(SmithySpacing.gutter))
        BusyRow(stage)
        Spacer(Modifier.height(SmithySpacing.gutter))
        SmithySkeletonList()
    }
}

/**
 * 打不开这个包。
 *
 * 失败也是空态的一种：说清「哪里不对」+「可以怎么办」，并给一个可点的下一步。
 * 原因与建议都来自 ViewModel（[Phase.Failed]），这里只负责排版。
 */
@Composable
private fun FailedState(state: Phase.Failed, onPick: () -> Unit) {
    SmithyEmptyState(
        icon = SmithyIcons.Warning,
        title = "打不开这个包",
        hint = buildString {
            append(state.message)
            state.hint?.let { append("\n建议：").append(it) }
        },
        modifier = Modifier.fillMaxSize(),
        action = { Button(onClick = onPick) { Text("换一个") } },
    )
}

@Composable
private fun ReadyContent(
    state: WorkbenchUiState,
    vm: ApkWorkbenchViewModel,
    onPick: () -> Unit,
    onPickIcon: () -> Unit,
    onExportReport: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // ── 顶栏：现在改的是哪个包 ──
        // 应用名当标题、包名当副标题，标题字号与各个页面统一（SmithyTopBar）；
        // 「换一个 / 关闭」收成图标按钮 —— 这两个动作每次都在，但都不该占半行文字
        SmithyTopBar(
            title = state.meta?.appLabel ?: state.sourceName,
            subtitle = state.meta?.packageName,
            actions = {
                SmithyIconButton(
                    icon = SmithyIcons.OpenFolder,
                    contentDescription = "换一个包",
                    onClick = onPick,
                )
                SmithyIconButton(
                    icon = SmithyIcons.Close,
                    contentDescription = "关闭这个包",
                    onClick = { vm.close() },
                )
            },
        )

        // 标签行已经在上一级渲染了（它要同时服务于没打开 apk 的模块标签）

        Box(Modifier.weight(1f)) {
            // 换标签淡入淡出：一级导航切换用的是同一档时长（Slow 进 / Fast 出）。
            // 不做左右滑动 —— 滑动表达「同一层级里的相邻关系」，而这六个标签是同一页里
            // 换一段看，划过去的手势会让人以为能横着翻页
            AnimatedContent(
                targetState = state.tab,
                transitionSpec = {
                    fadeIn(tween(SmithyMotion.Standard, easing = SmithyMotion.EaseEnter))
                        .togetherWith(fadeOut(tween(SmithyMotion.Fast, easing = SmithyMotion.EaseExit)))
                },
                label = "workbenchTab",
            ) { tab ->
                when (tab) {
                    WorkbenchTab.OVERVIEW -> state.meta?.let {
                        OverviewTab(
                            meta = it,
                            sourceName = state.sourceName,
                            entryCount = state.entryCount,
                            editLabel = state.editLabel,
                            editVersionName = state.editVersionName,
                            editVersionCode = state.editVersionCode,
                            editMinSdk = state.editMinSdk,
                            editTargetSdk = state.editTargetSdk,
                            busy = state.busy,
                            onLabelChange = vm::onEditLabel,
                            onVersionNameChange = vm::onEditVersionName,
                            onVersionCodeChange = vm::onEditVersionCode,
                            onMinSdkChange = vm::onEditMinSdk,
                            onTargetSdkChange = vm::onEditTargetSdk,
                            onApplyEdits = vm::applyManifestEdits,
                            onExportReport = onExportReport,
                        )
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

                    WorkbenchTab.FILES -> FilesTab(
                        state = state,
                        onFilter = vm::setEntryFilter,
                        onLoad = vm::loadEntries,
                        onReplace = vm::replaceEntry,
                        onDelete = vm::deleteEntry,
                    )

                    WorkbenchTab.PATCHES -> PatchesTab(state = state, onRevert = vm::revert)
                    // 模块标签在上一级就分流出去了（它不依赖已打开的 apk）。
                    // 这里列出来只是为了让 when 穷尽 —— 编译器不认「上面已经拦过」
                    WorkbenchTab.MODULE -> Unit
                }
            }
        }

        ActionBar(state, vm)
    }
}

/** 打包链路的动作条。三步的顺序不能颠倒：签名签的是上一次打包的产物。 */
@Composable
private fun ActionBar(state: WorkbenchUiState, vm: ApkWorkbenchViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
        ) {
            if (state.busy != null) {
                BusyRow(state.busy)
                Spacer(Modifier.height(SmithySpacing.gap))
            }
            // 消息条（成功 / 失败）整条进出都带动画：它常常在操作后立刻冒出来，
            // 硬切会让人以为是自己刚才误触了哪里
            AnimatedVisibility(
                visible = state.message != null,
                enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
                exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
            ) {
                state.message?.let { msg ->
                    Column {
                        MessageBar(msg, state.isError)
                        Spacer(Modifier.height(SmithySpacing.gap))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
                val idle = state.busy == null
                // 按钮上带图标：这三步是一条链，图标让「现在走到哪一步」不必读字
                OutlinedButton(
                    onClick = vm::rebuild,
                    enabled = idle && state.patches.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(SmithyIcons.Package, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重打包")
                }

                OutlinedButton(
                    onClick = vm::sign,
                    enabled = idle && state.rebuiltPath != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(SmithyIcons.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("签名")
                }

                Button(
                    onClick = vm::install,
                    enabled = idle && (state.signedPath != null || state.rebuiltPath != null),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(SmithyIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("安装")
                }
            }
        }
    }
}
