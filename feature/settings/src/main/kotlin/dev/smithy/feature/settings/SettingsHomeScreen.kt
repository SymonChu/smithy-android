package dev.smithy.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.smithy.ai.AiConfig
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTheme
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
 * 一级是四个药丸（常驻，不藏进「更多」—— 四个入口谁都不该被藏），
 * 二级是各自的页面。切页用淡入淡出，与一级 tab 的切换同一套曲线。
 */
@Composable
fun SettingsHome(
    page: SettingsPage,
    state: ExtensionUiState,
    palette: SmithyTheme = SmithyTheme.Default,
    onPaletteChange: (SmithyTheme) -> Unit = {},
    onSelect: (SettingsPage) -> Unit,
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
    aiPage: @Composable (Modifier) -> Unit = {},
) {
    Column(modifier) {
        SettingsNavBar(page = page, onSelect = onSelect)
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val enter = tween<Float>(SmithyMotion.Slow, easing = SmithyMotion.EaseEnter)
                val exit = tween<Float>(SmithyMotion.Fast, easing = SmithyMotion.EaseExit)
                fadeIn(enter).togetherWith(fadeOut(exit))
            },
            label = "settingsPage",
        ) { current ->
            when (current) {
                // 外观：只有主题。亮暗跟随系统，所以没有第二个可选项（理由写在 AppearancePage 里）
                SettingsPage.Appearance -> AppearancePage(
                    current = palette,
                    onChange = onPaletteChange,
                )

                // AI 接口那一页还是 ChatSettingsScreen（原样，不动它的排版）——
                // feature:settings 不依赖 feature:chat（否则两模块互相依赖），
                // 所以由 app 层把它作为内容传进来
                SettingsPage.Ai -> aiPage(Modifier)

                // 权限从扩展页里提出来：它管的是「这台设备上我能碰到什么」，
                // 和「装了什么扩展」是两件事，混在一页里找起来费劲
                SettingsPage.Permissions -> PermissionsPage(
                    state = state,
                    onRequestStorageAccess = onRequestStorageAccess,
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
                )
            }
        }
    }
}

/** 四页的切换行。 */
@Composable
private fun SettingsNavBar(page: SettingsPage, onSelect: (SettingsPage) -> Unit) {
    val haptics = rememberSmithyHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            // 四个药丸在窄屏上放不下 —— 之前是三页所以没这个问题。横向滚而不是压缩间距：
            // 压缩到 12dp 以下那些图标就挤成一团了
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
        horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
    ) {
        SettingsPage.entries.forEach { p ->
            val selected = p == page
            // 选中态：主色药丸。未选中：描边药丸。两者形状一致，只有底色差别 ——
            // 「当前在哪一页」是位置信息，用位置该有的手段（底色）表达
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    )
                    .clickable {
                        if (!selected) {
                            haptics.toggle()
                            onSelect(p)
                        }
                    }
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = p.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        p.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}