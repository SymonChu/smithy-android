package dev.smithy.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyCard
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTheme
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics

/**
 * 主题（色板）选择。
 *
 * **每项带一块色板预览，而不是只给名字** —— 四个中文名（「石墨」「钢青」「深林」「酒红」）
 * 传达不出色相差别，而选主题这件事的本质就是「挑一个颜色」。预览用该套色板
 * **当前这一套亮暗**里真实取色（跟着系统），所以用户看到的就是切过去之后的样子。
 *
 * 选中态：整张卡填 `primaryContainer` + 勾。不用描边 —— 暗色下描边发灰、投影看不见，
 * 这条「层次靠表面色差」在 `ui-language.md` 里是硬约定。
 */
@Composable
fun ThemePicker(
    current: SmithyTheme,
    onChange: (SmithyTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberSmithyHaptics()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
        SmithyTheme.entries.forEach { theme ->
            ThemeOption(
                theme = theme,
                selected = theme == current,
                onClick = {
                    if (theme != current) {
                        haptics.toggle()
                        onChange(theme)
                    }
                },
            )
        }
    }
}

@Composable
private fun ThemeOption(theme: SmithyTheme, selected: Boolean, onClick: () -> Unit) {
    // 切主题时这一行本身会换色 —— 颜色做过渡而不是瞬变，否则「点了没反应」的感觉很强
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(SmithyMotion.Standard),
        label = "themeContainer",
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            Modifier.padding(SmithySpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **预览色取自该主题自己的色板**（而不是当前主题的 token）——
            // 否则四张预览在切过去之前全都是同一个颜色，预览就没意义了
            Swatch(theme)
            Spacer(Modifier.width(SmithySpacing.cardPadding))
            Column(Modifier.weight(1f)) {
                Text(
                    theme.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    theme.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(
                    SmithyIcons.Check,
                    contentDescription = "当前用的主题",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * 一块色板预览：四档表面色 + 主色 + 错误色，横排。
 *
 * 画的是**层次**（background → 卡片 → 高层）而不是只画主色 —— 用户真正要挑的是
 * 「整个界面是什么气质」，单看一个色点判断不了。
 */
@Composable
private fun Swatch(theme: SmithyTheme) {
    // 亮暗跟随系统：预览必须和切过去之后的观感一致，不能固定画亮色
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (dark) theme.dark else theme.light
    Box(
        Modifier
            .size(52.dp, 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // 三档表面：从低到高，模拟卡片叠上去
            listOf(
                scheme.surfaceContainerLow,
                scheme.surfaceContainerHigh,
                scheme.primary,
            ).forEach { c ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(c),
                )
            }
        }
        // 错误色点：这一套的破坏性色是它最需要被「认出来」的部分
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(scheme.error),
        )
    }
}

/**
 * 设置 → 外观，整页。
 *
 * 独立成页而不是塞进 AI 接口那一页（0.1.4 时的位置）：换主题是**全局外观**的事，
 * 和「填 API key」不是一类。放在一起时，为了换个颜色要滚过三个输入框。
 */
@Composable
fun AppearancePage(
    current: SmithyTheme,
    onChange: (SmithyTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SmithyTopBar(
            title = "外观",
            subtitle = "主题色板",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = SmithySpacing.section),
        ) {
            // 说明放在选择器**上面**：先说清「亮暗跟随系统、为什么没有开关」，
            // 少一个开关用户就会去找；写在下面就变成「少了个功能」
            Text(
                "亮暗跟随系统设置，每套色板各有独立的亮色与暗色两版 —— " +
                    "所以这里没有亮/暗开关：加了会和系统打架，" +
                    "白天开了「亮」而系统是暗的时候，那个开关就永远生效不了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = SmithySpacing.gutter,
                    vertical = SmithySpacing.gap,
                ),
            )
            ThemePicker(current = current, onChange = onChange)
        }
    }
}