package dev.smithy.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyCard
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySectionTitle
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.fs.AddOnProgress
import dev.smithy.fs.AddOnSpec

/**
 * 扩展中心。
 *
 * 形态照一张 Android 上同类的「扩展中心」页面来：顶栏带一句状态（架构 · 已装 N/M），
 * 下面一排胶囊按钮做批量动作，再按用途分组列条目 —— 每条一个图标方框、一句说明、
 * 一行状态（已装 · 体积 · 时间 / 下载 · 体积），右侧一个动作按钮。
 *
 * **与那张界面的两处刻意不同**：
 * 1. 那一页的动作是「停用」（可开关的扩展）。这里的东西是**要下要删的大文件**，
 *    没有「开着但不用」这个中间态 —— 照抄一个假开关会让人以为能关掉什么。
 *    所以动作是「安装」↔「删除」，装好后立刻能用。
 * 2. 那一页没有顶部权限组。这里的权限状态与扩展是同一台设备上的同一件事
 *    （能不能执行装好的工具链直接取决于权限），放在一屏里不用来回切页。
 */
@Composable
fun ExtensionsScreen(
    state: ExtensionUiState,
    onRefresh: () -> Unit,
    onInstall: (String) -> Unit,
    onInstallAll: () -> Unit,
    onRemove: (AddOnSpec) -> Unit,
    onImportLocal: () -> Unit,
    onDismissNotice: () -> Unit,
    /** 送人去系统设置打开「所有文件访问」（MainActivity 里那个 launcher 传进来）。 */
    onRequestStorageAccess: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberSmithyHaptics()
    // 确认删除：删掉的是几百 MB 的东西，点错的代价不对称，所以要先问一句
    var pendingRemoval by remember { mutableStateOf<AddOnSpec?>(null) }

    // 进页面扫一次。用户可能刚在别的页装了东西，或者从电脑传了包过来
    LaunchedEffect(Unit) { onRefresh() }

    Column(modifier.fillMaxWidth()) {
        SmithyTopBar(
            title = "扩展",
            subtitle = state.subtitle,
            onBack = onBack,
            actions = {
                SmithyIconButton(
                    icon = SmithyIcons.Refresh,
                    contentDescription = "刷新",
                    enabled = !state.isBusy,
                    onClick = {
                        haptics.tap()
                        onRefresh()
                    },
                )
            },
        )

        // 一条状态/结果。放在顶栏下面而不是 Snackbar：它要常驻可读（下载失败的原因
        // 要能回来看），而 Snackbar 一会儿就没了
        state.notice?.let { notice ->
            NoticeBar(
                notice = notice,
                onDismiss = onDismissNotice,
                modifier = Modifier.padding(
                    horizontal = SmithySpacing.gutter,
                    vertical = SmithySpacing.barVertical,
                ),
            )
        }

        state.scanError?.let { err ->
            NoticeBar(
                notice = Notice(false, "扫不到状态", err, "重启 App 应该能解决"),
                onDismiss = {},
                modifier = Modifier.padding(
                    horizontal = SmithySpacing.gutter,
                    vertical = SmithySpacing.barVertical,
                ),
            )
        }

        if (state.catalog.isEmpty() && state.installed.isEmpty()) {
            SmithyEmptyState(
                icon = SmithyIcons.Package,
                title = "这里什么都没有",
                hint = "清单是空的，说明 App 没扫到下载清单。" +
                    "点右上角刷新；还是空的的话重启一次 App",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = SmithySpacing.section),
        ) {
            // ── 批量动作 ──────────────────────────────────────
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = SmithySpacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
                ) {
                    // 「安装全部」用主色实心：它是这一页的主要动作
                    ActionPill(
                        label = if (state.isBusy) "处理中…" else "安装全部",
                        icon = SmithyIcons.Download,
                        onClick = {
                            haptics.tap()
                            onInstallAll()
                        },
                        enabled = !state.isBusy,
                        filled = true,
                    )
                    ActionPill(
                        label = "刷新状态",
                        icon = SmithyIcons.Refresh,
                        onClick = {
                            haptics.tap()
                            onRefresh()
                        },
                        enabled = !state.isBusy,
                    )
                    ActionPill(
                        label = "导入本地包…",
                        icon = SmithyIcons.OpenFolder,
                        onClick = {
                            haptics.tap()
                            onImportLocal()
                        },
                        enabled = !state.isBusy,
                    )
                }
            }

            // ── 与权限相关的提示 ────────────────────────────────
            // 权限的**详情**在「权限」那一页（0.1.5 从这里搬出去的）。
            // 这里只留一句「哪些扩展需要什么权限」—— 扩展页该说的是扩展的事，
            // 而「这台设备上我能碰到什么」是另一类问题。
            item {
                Text(
                    needRootNote(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = SmithySpacing.gutter,
                        vertical = SmithySpacing.gap,
                    ),
                )
            }

            // ── 扩展分组 ──────────────────────────────────────
            state.groups.forEach { group ->
                item {
                    SmithySectionTitle(
                        text = group.title,
                        trailing = group.specs.size.toString(),
                        modifier = Modifier.padding(top = SmithySpacing.section),
                    )
                }
                if (group.hint != null) {
                    item {
                        Text(
                            group.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = SmithySpacing.gutter,
                                vertical = SmithySpacing.gap,
                            ),
                        )
                    }
                }
                items(group.specs, key = { it.id }) { spec ->
                    ExtensionRow(
                        spec = spec,
                        install = state.installOf(spec),
                        progress = state.progressOf(spec),
                        toolchainReady = state.toolchainReady,
                        enabled = !state.isBusy,
                        onInstall = { onInstall(spec.id) },
                        onRemove = { pendingRemoval = spec },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    // 删除确认
    pendingRemoval?.let { spec ->
        RemoveConfirmDialog(
            spec = spec,
            onConfirm = {
                haptics.warn()
                onRemove(spec)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

/**
 * 一条扩展。
 *
 * 状态一行同时说清「装没装」「多大」「什么时候」—— 装完几百 MB 的东西，用户回头
 * 最想确认的就是这三件事，而它们分三个地方显示就等于要人自己拼。
 */
@Composable
private fun ExtensionRow(
    spec: AddOnSpec,
    install: dev.smithy.fs.AddOnInstall?,
    progress: AddOnProgress?,
    toolchainReady: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberSmithyHaptics()
    val installed = install != null

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.rowVertical)
            .animateContentSize(animationSpec = tween(SmithyMotion.Standard)),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconBox(icon = iconFor(spec), tint = if (installed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            })
            Spacer(Modifier.width(SmithySpacing.iconGap))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        spec.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 状态点：照截图那套「一个小圆点表示当前状态」。绿=已装，灰=没装
                    Spacer(Modifier.width(SmithySpacing.gap))
                    StatusDot(ok = installed)
                }
                Text(
                    spec.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    statusLine(spec, install, toolchainReady),
                    style = SmithyRowMeta,
                    color = if (installed && !spec.needsRoot) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = SmithySpacing.gap / 2),
                )

                if (progress != null) {
                    Spacer(Modifier.height(SmithySpacing.gap))
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Text(
                        "${phaseText(progress.phase)} ${progress.percent}%" +
                            "（${ExtensionsViewModel.humanSize(progress.done)} / " +
                            "${ExtensionsViewModel.humanSize(progress.total)}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(SmithySpacing.gap))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
                ) {
                    if (progress != null) {
                        Text(
                            "进行中，先别关页面",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    } else if (installed) {
                        if (spec.url.isNotEmpty()) {
                            ActionPill(
                                label = "重装",
                                icon = SmithyIcons.Refresh,
                                onClick = {
                                    haptics.tap()
                                    onInstall()
                                },
                                enabled = enabled,
                            )
                        }
                        ActionPill(
                            label = "删除",
                            icon = SmithyIcons.Delete,
                            onClick = {
                                haptics.warn()
                                onRemove()
                            },
                            enabled = enabled,
                            danger = true,
                        )
                    } else {
                        ActionPill(
                            label = "安装（${ExtensionsViewModel.humanSize(spec.bytes)}）",
                            icon = SmithyIcons.Download,
                            onClick = {
                                haptics.tap()
                                onInstall()
                            },
                            enabled = enabled,
                            filled = true,
                        )
                    }
                }
            }
        }
    }
}

/** 状态行：已装就说时间与实际占用，没装就说下载体积与前置条件。 */
private fun statusLine(
    spec: AddOnSpec,
    install: dev.smithy.fs.AddOnInstall?,
    toolchainReady: Boolean,
): String {
    if (install != null) {
        val when_ = install.installedAt.takeIf { it > 0 }?.let {
            val days = (System.currentTimeMillis() - it) / 86_400_000
            when {
                days <= 0 -> "今天"
                days == 1L -> "昨天"
                else -> "$days 天前"
            }
        } ?: "本地"
        val size = ExtensionsViewModel.humanSize(install.bytes)
        // 装了但跑不起来要说出来 —— 「显示已装、实际不能用」是用户最难查的一类状态
        return if (spec.kind == dev.smithy.fs.AddOnKind.TOOLCHAIN && !toolchainReady) {
            "已装（$size · $when_）· 但没验过能跑起来 —— 回「模块」页编一次就知道"
        } else {
            "已装 · $size · $when_"
        }
    }
    val dl = ExtensionsViewModel.humanSize(spec.bytes)
    return when {
        spec.needsRoot -> "没装 · 下载 $dl · 需要 root"
        spec.kind == dev.smithy.fs.AddOnKind.SYSROOT -> "没装 · 下载 $dl（只留约 54MB）"
        else -> "没装 · 下载 $dl"
    }
}

private fun phaseText(phase: AddOnProgress.Phase): String = when (phase) {
    AddOnProgress.Phase.DOWNLOAD -> "下载"
    AddOnProgress.Phase.VERIFY -> "校验"
    AddOnProgress.Phase.EXTRACT -> "解包"
}

/**
 * 条目图标。
 *
 * 按 kind 选，**不给每种包配一种颜色** —— 那是 emoji 时代的老路（16 种类型 16 种颜色，
 * 分类反而看不清）。靠形状区分就够。
 */
private fun iconFor(spec: AddOnSpec): ImageVector = when (spec.kind) {
    dev.smithy.fs.AddOnKind.TOOLCHAIN -> SmithyIcons.Run
    dev.smithy.fs.AddOnKind.SYSROOT -> SmithyIcons.KindCode
    dev.smithy.fs.AddOnKind.ROOTFS -> SmithyIcons.KindDisk
    dev.smithy.fs.AddOnKind.OTHER -> SmithyIcons.Package
}

/** 图标方框：截图里每个条目左边那个带细边的圆角方块。 */
@Composable
private fun IconBox(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(SmithySpacing.barIconBox)
            .clip(RoundedCornerShape(10.dp))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    val color by animateFloatAsState(
        targetValue = if (ok) 1f else 0.5f,
        animationSpec = tween(SmithyMotion.Fast),
        label = "dot",
    )
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                if (ok) {
                    MaterialTheme.colorScheme.primary.copy(alpha = color)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = color)
                },
            ),
    )
}

/**
 * 胶囊按钮。
 *
 * 三种外观对应三种权重：[filled] 主色实心（这一页的主要动作）、默认描边（次要）、
 * `danger` 红色描边（删除）。**删除不能和普通按钮长得一样** —— 点错的代价差着量级。
 */
@Composable
private fun ActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
    danger: Boolean = false,
) {
    val tint = when {
        danger -> MaterialTheme.colorScheme.error
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            filled -> if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (filled) null else BorderStroke(
            1.dp,
            if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .defaultMinSize(minHeight = 32.dp)
            .clickable(enabled = enabled) { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

/**
 * 一句话说明权限与扩展的关系（权限详情在「权限」那一页）。
 *
 * **不是把权限状态重复一遍** —— 那一页才是看状态的地方。这里只回答扩展页特有的问题：
 * 「装这些要不要 root？」所以只说**缺不缺权限会不会挡路**，不列状态。
 */
private fun needRootNote(state: ExtensionUiState): String {
    val needRoot = state.catalog.filter { it.needsRoot }
    if (needRoot.isEmpty()) {
        return "下面这些扩展都不需要 root，装上就能用。权限状态在「权限」那一页。"
    }
    val names = needRoot.joinToString("、") { it.name }
    val missing = needRoot.filter { it.needsRoot && !state.caps.rootGranted }
    return if (missing.isNotEmpty()) {
        "$names 需要 root，而这台设备上还没有 root —— 这几条现在装不了。" +
            "权限状态在「权限」那一页；有了 root 之后回这里点「安装全部」即可。"
    } else {
        "$names 需要 root，这台设备上已经有了。权限状态在「权限」那一页。"
    }
}

/** 结果条。常驻可读（下载失败的原因要能回来看），所以不用 Snackbar。 */
@Composable
private fun NoticeBar(notice: Notice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (notice.ok) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(
                start = SmithySpacing.cardPadding,
                end = SmithySpacing.gap,
                top = SmithySpacing.gap,
                bottom = SmithySpacing.gap,
            ),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (notice.ok) SmithyIcons.Check else SmithyIcons.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (notice.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(SmithySpacing.gap))
            Column(Modifier.weight(1f)) {
                Text(
                    notice.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (notice.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
                )
                if (notice.detail.isNotEmpty()) {
                    Text(
                        notice.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notice.ok) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                notice.hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (notice.ok) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            SmithyIconButton(
                icon = SmithyIcons.Close,
                contentDescription = "关掉提示",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun RemoveConfirmDialog(spec: AddOnSpec, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            dev.smithy.design.SmithyDialogTitle(
                icon = SmithyIcons.Delete,
                text = "删掉 ${spec.name}？",
                tint = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column {
                Text(
                    "会删掉装到手机上的那一份（${ExtensionsViewModel.humanSize(spec.bytes)} 下载，" +
                        "装完实际占用可能更大）。之后要再用得重新下一遍。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (spec.kind == dev.smithy.fs.AddOnKind.TOOLCHAIN) {
                    Text(
                        "注意：正在用它编译的模块会失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = SmithySpacing.gap),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("删掉") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("算了") }
        },
    )
}
