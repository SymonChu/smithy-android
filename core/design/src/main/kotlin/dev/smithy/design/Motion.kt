package dev.smithy.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * 动效参数。
 *
 * 之前整个界面**一处动画都没有**：切标签是硬切、进目录列表整体瞬间替换、对话框直接
 * 弹出来。「高级感」有一大半来自运动 —— 不是加得多，而是加得**一致**：同一种操作在
 * 每个页面用同一条曲线、同一个时长。所以时长和曲线集中在这里，页面里不许再写
 * `tween(200)` 这种散落的字面量。
 *
 * 时长取 M3 的三档：
 * - [Fast] 120ms：小范围状态变化（勾选框、选中色、图标旋转）。再长会显得拖沓。
 * - [Standard] 220ms：进入 / 展开 / 内容替换。这是最常用的一档。
 * - [Slow] 320ms：整屏级别的切换（切标签、进出编辑器）。它得比 Standard 长，
 *   否则大面积位移会像闪一下。
 */
object SmithyMotion {

    /** 小状态变化。 */
    const val Fast = 120

    /** 进入、展开、内容替换。 */
    const val Standard = 220

    /** 整屏切换。 */
    const val Slow = 320

    /**
     * 进入曲线（emphasized decelerate）。
     *
     * 开头快、尾巴长 —— 元素「冲进来然后稳住」，这是 M3 里所有「出现」统一的曲线。
     * 用对称的 ease-in-out 会让出现显得迟疑。
     */
    val EaseEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 退出曲线。元素出去要快、不拖泥带水，所以比进入短、且是加速离场。 */
    val EaseExit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 原地属性变化（颜色、透明度、大小）用的标准曲线，两端都平滑。 */
    val EaseStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 状态变化（勾选、开关）用 [Fast]。 */
    fun <T> state(): FiniteAnimationSpec<T> = tween(Fast, easing = EaseStandard)

    /** 出现用 [Standard] + [EaseEnter]。 */
    fun <T> enter(): FiniteAnimationSpec<T> = tween(Standard, easing = EaseEnter)

    /** 消失用 [Fast] 的时长 + [EaseExit]：走的时候不该占着舞台。 */
    fun <T> exit(): FiniteAnimationSpec<T> = tween(Fast, easing = EaseExit)

    /** 整屏切换（标签页、编辑器）。 */
    fun <T> screen(): FiniteAnimationSpec<T> = tween(Slow, easing = EaseEnter)

    /**
     * 列表增删的默认缓动。
     *
     * 这里不用 tween 而用低阻尼弹簧：列表里加一项时，那一行「推」开下面的行，弹簧的
     * 轻微回弹能让「谁动了」看得出来；tween 是匀速滑动，密集列表里几乎看不出变化。
     * 阻尼给到 0.85，是为了只留一点点回弹 —— 晃得厉害就廉价了。
     */
    fun item(): FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
}
