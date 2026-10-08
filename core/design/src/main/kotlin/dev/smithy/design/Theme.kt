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
    // 页面级标题（区块大标题、空态标题）
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        // 字号越大越要收紧字距：默认字距在大字上会散开
        letterSpacing = (-0.2).sp,
    ),
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
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // 正文默认值（14sp / 400）在列表里偏轻，主文案改用下面的 [SmithyRowTitle]
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // 次要标签（单位、分组名、按钮上的小字）
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
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
 * 列表行的主文案。
 *
 * 用 Medium 而不是 400：列表里「标题 vs 副标题」的层次靠**字重**拉开比靠字号更稳 ——
 * 13sp 和 14sp 在真机上几乎分不出来，但 400 和 500 一眼能看出。
 */
val SmithyRowTitle = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

/** 列表行的次要信息（类型、时间、"可展开" 之类）。 */
val SmithyRowMeta = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/**
 * 数字列：等宽 + 表格数字。
 *
 * `tnum`（tabular figures）是关键：等宽字体里数字本来就等宽，但回退字体或系统
 * 合成时不一定 —— 不显式打开，右对齐的一列大小会在某些字形上错开半像素。
 */
val SmithyNumeric = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = "tnum",
)

/**
 * 圆角。
 *
 * 四档差距拉得比较开（6 / 10 / 14 / 20）：小了看起来像没做，只差两三像素的圆角
 * 在真机上根本分辨不出来，白改一场。
 *
 * [extraLarge] 给整屏面板（底部弹出的选择器、确认面板）—— 它们占据整个宽度，
 * 用 20 会显得方，28 才有「从底部长出来」的观感。
 */
val SmithyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 间距。
 *
 * 之前每个页面各写各的 padding（8、10、12、14 混着用），观感上「说不出的乱」多半
 * 来自这里。统一成这几个值，改的时候也只用改一处。
 *
 * **全部落在 4dp 网格上**（上一版有 6 / 14 / 18，这三个不在网格上 —— 12 和 14 的差别
 * 肉眼看得出来，但又不是有意为之的节奏，观感上就是「手抖」）。留白从 12 提到 16 也是
 * 同一件事：16 的页边距在手机上才撑得住，12 会显得挤。
 */
object SmithySpacing {
    /** 页面左右留白，也是顶栏的左右内边距。 */
    val gutter = 16.dp

    /** 顶栏的纵向内边距。 */
    val barVertical = 8.dp

    /** 列表行内边距。文件页要密，所以纵向给得小。 */
    val rowVertical = 10.dp
    val rowHorizontal = 16.dp

    /** 卡片内边距。 */
    val cardPadding = 16.dp

    /** 区块之间。 */
    val section = 20.dp

    /** 同一组元素之间。 */
    val gap = 8.dp

    /** 行首图标与文字之间。图标自带留白，所以比 [gap] 略大才显得匀。 */
    val iconGap = 14.dp

    /** 列表行的图标尺寸。行高 44dp 左右时，22 是「看得出图标、又不抢文字」的那一档。 */
    val iconSize = 22.dp

    /** 顶栏 / 操作条上图标按钮的尺寸（同时也是最小点击区）。 */
    val barIcon = 24.dp
    val barIconBox = 40.dp

    /** 紧凑列表行的最小高度。 */
    val rowHeight = 48.dp
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
 *
 * [palette] 是**色板**，不是亮暗 —— 四套色板各自都有独立的亮/暗两版。
 *
 * 参数名用 `palette` 而不是 `theme`：那个名字被 [SmithyTheme]（枚举）占着，
 * 同名参数会在函数体里把类型遮蔽掉，写 `theme.dark` 时拿到的是形参 —— 编译能过，
 * 运行时报「枚举没有 dark」。这类错很难倒查。
 */
@Composable
fun SmithyTheme(
    dark: Boolean = isSystemInDarkTheme(),
    palette: SmithyTheme = SmithyTheme.Default,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) palette.dark else palette.light,
        typography = SmithyTypography,
        shapes = SmithyShapes,
        content = content,
    )
}
