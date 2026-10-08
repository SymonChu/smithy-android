package dev.smithy.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import dev.smithy.ai.AiConfig
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySpacing
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.fs.AddOnSpec

/** 设置里的两页。 */
enum class SettingsPage(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Ai("AI 接口", SmithyIcons.Chat),
    Extensions("扩展", SmithyIcons.Package),
}

/**
 * 设置的二级导航壳。
 *
 * 之前设置 tab 直接就是 AI 接口页。加「扩展」之后不能把它塞进那一页的滚动内容里 ——
 * 扩展是一个需要独立思考的整页（有状态、有批量动作、有确认对话框），塞进表单底部
 * 会让「装几百 MB 的东西」看起来像「填个字段」那么轻。所以做成两页切换。
 *
 * 导航行常驻在顶部：两个入口，谁都不该藏在「更多」里。
 */
@Composable
fun SettingsHome(
    page: SettingsPage,
    state: ExtensionUiState,
    aiConfig: AiConfig,
    configProblem: String?,
    trustWrites: Boolean,
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
                // AI 接口那一页还是 ChatSettingsScreen（原样，不动它的排版）——
                // 导航壳只在外面套一层，页内的一切都保持原样
                SettingsPage.Ai -> aiPage(Modifier)
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

/** 两页的切换行。 */
@Composable
private fun SettingsNavBar(page: SettingsPage, onSelect: (SettingsPage) -> Unit) {
    val haptics = rememberSmithyHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
        horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
    ) {
        SettingsPage.entries.forEach { p ->
            val selected = p == page
            // 选中态：主色药丸。未选中：一个描边药丸。两者形状一致，只有底色差别 ——
            // 「当前在哪一页」是位置信息，用位置该有的手段（底色）表达，不靠图标变形
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
