package dev.smithy.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.ai.AiConfig
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTheme
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.fs.AddOnSpec

/**
 * 设置里的四页。
 *
 * **分页的依据是「谁要改它」而不是「它属于什么」**：主题和权限都是低频、一次改完的，
 * AI 接口是要反复改的，扩展是低频但很重的（一次下几百 MB）。混在一页里，
 * 为了换个颜色要滚过 API key 那一堆输入框。
 *
 * 只列**真有的能力**。docs/05 里规划过但还没做的（MCP 服务端开关、电池白名单、
 * 清空工作区）不在这里出现 —— 菜单里摆一个点进去是空的入口，比不摆更糟。
 */
enum class SettingsPage(val label: String, val icon: ImageVector) {
    Appearance("外观", SmithyIcons.Star),
    Ai("AI 接口", SmithyIcons.Chat),
    Permissions("权限", SmithyIcons.Permission),
    Extensions("扩展", SmithyIcons.Package),
}

/**
 * 设置的二级导航壳。
 *
 * 一级是**竖列菜单**（0.1.6 改的：原来是一排横向药丸，四个挤在窄屏上要横向滚，
 * 而且「当前在哪一页」和「还有哪几页」在视觉上长得一样），
 * 二级是各自整页的内容 —— 每页有自己的顶栏和返回。
 *
 * 菜单每一行右侧显示**该页现状**（用的哪套主题、AI 接好没有、权限拿到没有、装了几个扩展）：
 * 这四个状态原先都要点进去才知道，而它们恰好是「今天要不要进这一页」的判断依据。
 */
@Composable
fun SettingsHome(
    /** null = 停在菜单上；非空 = 已经进到某一页。 */
    page: SettingsPage?,
    state: ExtensionUiState,
    palette: SmithyTheme = SmithyTheme.Default,
    /** AI 那一行的现状副行。由 app 层算 —— 只有它同时看得见配置与「还缺什么」。 */
    aiSummary: String = "",
    onPaletteChange: (SmithyTheme) -> Unit = {},
    onSelect: (SettingsPage?) -> Unit,
    onConfigChange: (AiConfig) -> Unit,
    onTrustWritesChange: (Boolean) -> Unit,
    onRefreshExtensions: () -> Unit,
    onInstallExtension: (String) -> Unit,
    onInstallAllExtensions: () -> Unit,
    onRemoveExtension: (AddOnSpec) -> Unit,
    onImportExtension: () -> Unit,
    onDismissNotice: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    modifier: Modifier = Modifier,
    aiPage: @Composable (Modifier, () -> Unit) -> Unit = { _, _ -> },
) {
    Column(modifier) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val enter = tween<Float>(SmithyMotion.Slow, easing = SmithyMotion.EaseEnter)
                val exit = tween<Float>(SmithyMotion.Fast, easing = SmithyMotion.EaseExit)
                fadeIn(enter).togetherWith(fadeOut(exit))
            },
            label = "settingsPage",
        ) { current ->
            val back = { onSelect(null) }
            when (current) {
                null -> SettingsMenu(
                    palette = palette,
                    state = state,
                    aiSummary = aiSummary,
                    onOpen = onSelect,
                )

                // 外观：只有主题。亮暗跟随系统，所以没有第二个可选项（理由写在 AppearancePage 里）
                SettingsPage.Appearance -> AppearancePage(
                    current = palette,
                    onChange = onPaletteChange,
                    onBack = back,
                )

                // AI 接口那一页是 ChatSettingsScreen（feature:chat）—— feature:settings
                // 不依赖 feature:chat（否则两模块互相依赖），所以由 app 层把它传进来
                SettingsPage.Ai -> aiPage(Modifier, back)

                // 权限从扩展页里提出来：它管的是「这台设备上我能碰到什么」，
                // 和「装了什么扩展」是两件事，混在一页里找起来费劲
                SettingsPage.Permissions -> PermissionsPage(
                    state = state,
                    onRequestStorageAccess = onRequestStorageAccess,
                    onBack = back,
                )

                SettingsPage.Extensions -> ExtensionsScreen(
                    state = state,
                    onRefresh = onRefreshExtensions,
                    onInstall = onInstallExtension,
                    onInstallAll = onInstallAllExtensions,
                    onRemove = onRemoveExtension,
                    onImportLocal = onImportExtension,
                    onDismissNotice = onDismissNotice,
                    onRequestStorageAccess = onRequestStorageAccess,
                    onBack = back,
                )
            }
        }
    }
}

/**
 * 菜单本体。
 *
 * 行不做卡片包裹：这里**只有一层**，卡片是给「同屏里还有别的组」用的分组手段，
 * 而整屏只有一个列表时，卡片只会让每一项都缩进一格又缩回来。
 */
@Composable
private fun SettingsMenu(
    palette: SmithyTheme,
    state: ExtensionUiState,
    aiSummary: String,
    onOpen: (SettingsPage) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SmithyTopBar(title = "设置")
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = SmithySpacing.section),
        ) {
            SettingsPage.entries.forEach { p ->
                MenuRow(
                    icon = p.icon,
                    title = p.label,
                    subtitle = when (p) {
                        SettingsPage.Appearance -> palette.label
                        SettingsPage.Ai -> aiSummary
                        SettingsPage.Permissions -> permissionSummary(state.caps)
                        SettingsPage.Extensions -> state.subtitle
                    },
                    onClick = { onOpen(p) },
                )
            }
        }
    }
}

/** 菜单行：[图标] [名称 + 现状] [箭头]。 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val haptics = rememberSmithyHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SmithySpacing.rowHeight)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(
                horizontal = SmithySpacing.gutter,
                vertical = SmithySpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(SmithySpacing.iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(SmithySpacing.iconGap))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = SmithyRowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(SmithySpacing.gap))
        Icon(
            imageVector = SmithyIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 权限页现状的一句话。
 *
 * 只报**有明确探测手段**的两项：Root 与所有文件访问。Shizuku 也探测得到，
 * 但它只影响保活/杀进程，进不进权限页跟它无关，写进来只会挤掉更该看的字。
 */
private fun permissionSummary(caps: Capabilities): String {
    val storage = if (caps.storageGranted) "所有文件访问 已授权" else "所有文件访问 未开"
    val root = if (caps.rootGranted) "Root 已获取" else "Root 未获取"
    return "$storage · $root"
}
