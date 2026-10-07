package dev.smithy.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════════════
// 触感
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 触感反馈。
 *
 * 之前全项目**一处触感都没有** —— 长按进多选、勾选、删除确认全是「按下去毫无动静」。
 * 触感是「这个动作生效了」的第一反馈，比那 120ms 的动画还早到。
 *
 * 走 [View.performHapticFeedback] 而不是 Compose 的 `LocalHapticFeedback`：后者的
 * [androidx.compose.ui.hapticfeedback.HapticFeedbackType] 只稳定提供 LongPress /
 * TextHandleMove 两种，表达不了「确认 / 拒绝 / 计数」的区别；而系统常量从 Android 8
 * 到 14 都在，且**遵守系统设置的触感开关**（用户关了就不会震）。
 *
 * 常量本身是编译期内联的 int，所以引用 API 30 才有的 CONFIRM / REJECT 不会在旧系统上
 * 崩 —— 但语义只有 30+ 才真正区分，旧系统退化成通用点击。
 */
@Stable
class SmithyHaptics(private val view: View) {

    /** 普通点击（按钮、行点击）。对应系统「轻触」。 */
    fun tap() = fire(HapticFeedbackConstants.VIRTUAL_KEY)

    /** 长按（进多选、菜单）。比 [tap] 重，用户能听出区别。 */
    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS)

    /** 勾选 / 取消勾选 / 开关。用系统时钟滴答，节拍感强、不吵。 */
    fun toggle() = fire(HapticFeedbackConstants.CLOCK_TICK)

    /** 破坏性操作成功（删除、覆盖）、关键确认。 */
    fun confirm() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )

    /** 操作被拒绝 / 失败或即将执行破坏性操作前的警示。 */
    fun warn() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    private fun fire(constant: Int) {
        // 返回 false 只代表「这次没震成」（视图没挂载 / 用户关了触感），不是错误
        view.performHapticFeedback(constant)
    }
}

/** 取当前界面的触感入口。用法：`val haptics = rememberSmithyHaptics()`。 */
@Composable
fun rememberSmithyHaptics(): SmithyHaptics {
    val view = LocalView.current
    return remember(view) { SmithyHaptics(view) }
}

// ═══════════════════════════════════════════════════════════════════════════
// 图标按钮
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 顶栏 / 操作条上的图标按钮。
 *
 * 统一三件事：40dp 的点击区（低于 40 在手机上会点空）、20dp 的图标（比 24 的默认视觉
 * 重量轻一档，放在密集顶栏里不抢标题）、点击带 [SmithyHaptics.tap]。
 */
@Composable
fun SmithyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    val haptics = rememberSmithyHaptics()
    IconButton(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = modifier.size(SmithySpacing.barIconBox),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 页面骨架
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 页面顶栏。
 *
 * 各页原先各写各的标题行（字号 15/16/18 混着、左右内边距 12/14 混着），换成同一个：
 * 20sp SemiBold 的标题 + 可选副标题 + 右侧动作区。**标题字号一致**是「像一个应用」
 * 最省力的办法 —— 用户说不出哪里不同，但切换时会觉得稳。
 *
 * [subtitle] 是给「当前状态下的一句话」（正在浏览哪个目录、哪个包）。它必须是
 * 次要色，而且只有一行 —— 副标题换行后标题就不再是视觉重心了。
 */
@Composable
fun SmithyTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val haptics = rememberSmithyHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null) SmithySpacing.gap else SmithySpacing.gutter,
                end = SmithySpacing.gap,
                top = SmithySpacing.barVertical,
                bottom = SmithySpacing.barVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            SmithyIconButton(
                icon = SmithyIcons.Up,
                contentDescription = "返回",
                onClick = {
                    haptics.tap()
                    onBack()
                },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        actions()
    }
}

/**
 * 区块小标题。
 *
 * 用来替代「分割线 + 加粗文字」的老做法：分割线是横着切，小标题是**分组**。
 * 分组比切分更省版面，也更接近现在工具类应用的观感。
 */
@Composable
fun SmithySectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SmithySpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 分组容器。
 *
 * 用 `surfaceContainerLow` 而不是描边或投影：暗色下描边会发灰、投影看不见，只有
 * 表面色差在两套主题里都成立。这一条也是列表里**去掉满宽分割线**的前提 —— 分组靠
 * 底色和间距，不靠线。
 */
@Composable
fun SmithyCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
    ) {
        Column(content = content)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 空态与加载态
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 空态。
 *
 * 原先空目录就一行居中的灰字（「这个目录是空的」），加载中更糟 —— 直接把状态字符串
 * 当空态文案显示，看起来像出错。空态得说清**三件事**：这是什么情况、为什么、能做什么，
 * 并且给一个可点的下一步。
 *
 * 圆形底 + 图标是刻意的：纯文字空态在大屏上会显得「页面坏了」，有一个视觉锚点就不一样。
 */
@Composable
fun SmithyEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = SmithySpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(SmithySpacing.gutter))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(Modifier.height(SmithySpacing.gap))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(SmithySpacing.section))
            action()
        }
    }
}

/**
 * 骨架行。
 *
 * 加载中显示骨架而不是「加载中…」文字：骨架提前把**布局**告诉用户，内容出现时不会
 * 整页跳一下；文字提示则是什么都没有，等完再突然刷出一屏。
 */
@Composable
fun SmithySkeletonRow(
    modifier: Modifier = Modifier,
    titleWidth: Float = 0.45f,
    showMeta: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = SmithySpacing.rowHorizontal,
                vertical = SmithySpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(Modifier.size(SmithySpacing.iconSize), corner = 6.dp)
        Spacer(Modifier.width(SmithySpacing.iconGap))
        Column(Modifier.weight(1f)) {
            SkeletonBlock(
                Modifier
                    .fillMaxWidth(titleWidth)
                    .height(12.dp),
            )
            if (showMeta) {
                Spacer(Modifier.height(6.dp))
                SkeletonBlock(
                    Modifier
                        .fillMaxWidth(titleWidth * 0.6f)
                        .height(10.dp),
                )
            }
        }
    }
}

/** 骨架列表。[rows] 给 6~8 条就够了：再多也只是把「等」的感觉拉长。 */
@Composable
fun SmithySkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 7,
) {
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            SmithySkeletonRow(
                titleWidth = if (index % 3 == 0) 0.55f else 0.4f,
                showMeta = index % 2 == 0,
            )
        }
    }
}

/**
 * 骨架里的灰块，带一个缓慢的呼吸。
 *
 * 用透明度呼吸（700ms 一来回）而不是横向扫光：扫光要单独画渐变和位移，在长列表里
 * 每行一起扫看起来像进度条，反而更吵。呼吸只表达「还在动」。
 */
@Composable
private fun SkeletonBlock(
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.22f)),
    )
}
