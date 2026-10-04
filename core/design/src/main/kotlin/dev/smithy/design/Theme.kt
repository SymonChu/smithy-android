package dev.smithy.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 字阶。
 *
 * 只覆盖几个角色，其余沿用 M3 默认 —— 全量重定义字阶是「设计师有很多时间」时做的事，
 * 而这里真正需要的是**层次**：标题要够重、副信息要够轻。改太多反而会让
 * 各页面原先「用默认值碰巧对齐」的地方开始互相对不齐。
 */
val SmithyTypography = Typography(
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    // 标签类（计数、状态、单位）统一压小并轻微加宽字距：它们在界面上是「注解」，
    // 不该和正文抢注意力
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * 圆角。
 *
 * 三档差距拉得比较开（10 / 14 / 28）：小了看起来像没做，只差两三像素的圆角
 * 在真机上根本分辨不出来，白改一场。
 */
val SmithyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(28.dp),
)

/**
 * 间距。
 *
 * 之前每个页面各写各的 padding（8、10、12、14 混着用），观感上「说不出的乱」多半
 * 来自这里。统一成这几个值，改的时候也只用改一处。
 */
object SmithySpacing {
    /** 页面左右留白。 */
    val gutter = 12.dp

    /** 列表行内边距。文件页要密，所以纵向给得小。 */
    val rowVertical = 8.dp
    val rowHorizontal = 12.dp

    /** 卡片内边距。 */
    val cardPadding = 14.dp

    /** 区块之间。 */
    val section = 18.dp

    /** 同一组元素之间。 */
    val gap = 8.dp
}

/** 等宽字体：路径、大小、偏移、哈希都用它 —— 对齐的数字比好看的字重更重要。 */
val SmithyMono = FontFamily.Monospace

/**
 * 应用主题。
 *
 * **暗色跟随系统**，不做手动开关：文件管理器天天开着，跟着系统走最省事。
 * （原先的 `themes.xml` 把窗口底色写死成浅色，系统切暗色时整个窗口还是白的 ——
 * 那种「半暗」比全亮更刺眼。）
 *
 * **不启用动态取色**（Android 12+ 从壁纸取色）：这个界面上权重色是有含义的
 * （error 表示破坏性、primary 表示当前所处位置），跟着壁纸变会让同一张截图
 * 在不同手机上表达不同的意思。品牌一致性在这里比个性化重要。
 */
@Composable
fun SmithyTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) SmithyDarkColors else SmithyLightColors,
        typography = SmithyTypography,
        shapes = SmithyShapes,
        content = content,
    )
}
