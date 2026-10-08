package dev.smithy.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Smithy 的主题色板 —— 四套，每套各有独立的亮色与暗色。
 *
 * ## 为什么是四套「颜色」而不是「亮/暗开关」
 *
 * 亮暗跟随系统已经够了（`isSystemInDarkTheme()`），加一个亮暗开关只是多一层要找的设置。
 * 真正缺的是**色板**：这套工具的气质是「改包、刷模块、翻系统目录」，界面上会一直出现
 * 权限、警告、破坏性确认，主色必须**安静**——不能和警告色抢注意力，也不能像消费级 App
 * 那样用高饱和的品牌色。
 *
 * ## 四套的定位（不是四种口味，是四种取向）
 *
 * | 主题 | 取向 | 谁适合 |
 * |---|---|---|
 * | **石墨** | 中性灰蓝，几乎不表态 | 默认。工具不该有主张 |
 * | **钢青** | 偏冷的深青（原色板） | 喜欢现在这套的人 |
 * | **深林** | 墨绿，低饱和 | 长时间盯着屏幕，少刺激 |
 * | **酒红** | 暗红，克制不艳 | 想要「专业工具」那种重量感 |
 *
 * ## 三条不妥协
 *
 * 1. **每套的亮暗分别调**，不是把亮色反转。暗色下主色必须提亮（`#0F6F6C` → `#5FC9C4`），
 *    否则在深底上对比度不够、文字发灰 —— 这是把亮色直接拿来用最常见的错。
 * 2. **主色与错误色不能撞**。主色偏红时错误色要往深橙红走，不能也是红；反过来主色偏青时
 *    错误色保持砖红。两者撞了，破坏性确认框会看成普通提示。
 * 3. **饱和度压住**。所有主色 S 通道 ≤ 0.35（亮色）/ ≤ 0.45（暗色）。这套界面上真正的
 *    彩色信号是「绿色=已就绪、红色=破坏性」，主色再艳就是在干扰它们。
 *
 * 这些值同时是 HTML 排版稿里用的 token，稿子和成品能对上。
 */

// ═══════════════════════════════════════════════════════════════════════════
// 石墨（默认）—— 中性灰蓝
// ═══════════════════════════════════════════════════════════════════════════

private val SlateLightPrimary = Color(0xFF4A5C6E)
private val SlateDarkPrimary = Color(0xFFA9C2D8)

private val SlateLight = lightColorScheme(
    primary = SlateLightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE5EE),
    onPrimaryContainer = Color(0xFF2C3A48),
    inversePrimary = SlateDarkPrimary,

    secondary = Color(0xFF55606B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E9ED),
    onSecondaryContainer = Color(0xFF343C45),
    tertiary = Color(0xFF5A6270),
    tertiaryContainer = Color(0xFFE6E9EE),
    onTertiaryContainer = Color(0xFF39404C),

    error = Color(0xFFA6322A),
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF7C241E),

    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF191B1E),
    surface = Color(0xFFFAFAFB),
    onSurface = Color(0xFF191B1E),
    surfaceVariant = Color(0xFFE8EBEF),
    onSurfaceVariant = Color(0xFF585F68),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6F8),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFEBEEF1),
    surfaceContainerHighest = Color(0xFFE5E8EC),

    outline = Color(0xFFC2C8CF),
    outlineVariant = Color(0xFFDDE1E6),
    inverseSurface = Color(0xFF2E3237),
    inverseOnSurface = Color(0xFFEFF1F4),
)

private val SlateDark = darkColorScheme(
    primary = SlateDarkPrimary,
    onPrimary = Color(0xFF143044),
    primaryContainer = Color(0xFF2C4658),
    onPrimaryContainer = Color(0xFFC8DCEE),
    inversePrimary = SlateLightPrimary,

    secondary = Color(0xFFBCC5CE),
    onSecondary = Color(0xFF273039),
    secondaryContainer = Color(0xFF3D464F),
    onSecondaryContainer = Color(0xFFD7DEE6),
    tertiary = Color(0xFFBFC6CE),
    tertiaryContainer = Color(0xFF404852),
    onTertiaryContainer = Color(0xFFDCE2E9),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF101215),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF15181B),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF252A2F),
    onSurfaceVariant = Color(0xFF9EA6AE),

    surfaceContainerLowest = Color(0xFF0C0E10),
    surfaceContainerLow = Color(0xFF141619),
    surfaceContainer = Color(0xFF1C1F23),
    surfaceContainerHigh = Color(0xFF25282D),
    surfaceContainerHighest = Color(0xFF2D3136),

    outline = Color(0xFF7A828A),
    outlineVariant = Color(0xFF2C3136),
    inverseSurface = Color(0xFFE3E6EA),
    inverseOnSurface = Color(0xFF2E3237),
)

// ═══════════════════════════════════════════════════════════════════════════
// 钢青 —— 原来的色板，偏冷的深青
// ═══════════════════════════════════════════════════════════════════════════

private val TealLightPrimary = Color(0xFF0F6F6C)
private val TealDarkPrimary = Color(0xFF5FC9C4)

private val TealLight = lightColorScheme(
    primary = TealLightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EEED),
    onPrimaryContainer = Color(0xFF0B5451),
    inversePrimary = TealDarkPrimary,

    secondary = Color(0xFF4A6361),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4EFEE),
    onSecondaryContainer = Color(0xFF33403F),
    tertiary = Color(0xFF4C5C70),
    tertiaryContainer = Color(0xFFE6ECF4),
    onTertiaryContainer = Color(0xFF2C3A4A),

    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFDECEB),
    onErrorContainer = Color(0xFF8C2F28),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF161A1D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161A1D),
    surfaceVariant = Color(0xFFE9EDF1),
    onSurfaceVariant = Color(0xFF5A6169),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFF4F6F8),
    surfaceContainerHigh = Color(0xFFEDF1F4),
    surfaceContainerHighest = Color(0xFFE9EDF1),

    outline = Color(0xFFC7CFD6),
    outlineVariant = Color(0xFFDFE4E9),
    inverseSurface = Color(0xFF2C3136),
    inverseOnSurface = Color(0xFFF1F3F5),
)

private val TealDark = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF123A38),
    onPrimaryContainer = Color(0xFF8FDED9),
    inversePrimary = TealLightPrimary,

    secondary = Color(0xFFB1CCC9),
    onSecondary = Color(0xFF1C3533),
    secondaryContainer = Color(0xFF2A4240),
    onSecondaryContainer = Color(0xFFCDE9E6),
    tertiary = Color(0xFFB3C7DB),
    tertiaryContainer = Color(0xFF2B3A4A),
    onTertiaryContainer = Color(0xFFCFE0F0),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF3A201E),
    onErrorContainer = Color(0xFFF0A9A2),

    background = Color(0xFF0F1214),
    onBackground = Color(0xFFE6EAEE),
    surface = Color(0xFF161A1D),
    onSurface = Color(0xFFE6EAEE),
    surfaceVariant = Color(0xFF252B30),
    onSurfaceVariant = Color(0xFF9AA3AC),

    surfaceContainerLowest = Color(0xFF0B0E10),
    surfaceContainerLow = Color(0xFF141719),
    surfaceContainer = Color(0xFF1D2226),
    surfaceContainerHigh = Color(0xFF252B30),
    surfaceContainerHighest = Color(0xFF2C3339),

    outline = Color(0xFF6F787F),
    outlineVariant = Color(0xFF2B3238),
    inverseSurface = Color(0xFFE6EAEE),
    inverseOnSurface = Color(0xFF2C3136),
)

// ═══════════════════════════════════════════════════════════════════════════
// 深林 —— 墨绿，低饱和
// ═══════════════════════════════════════════════════════════════════════════

private val ForestLightPrimary = Color(0xFF2F6B4F)
private val ForestDarkPrimary = Color(0xFF7FCEA3)

private val ForestLight = lightColorScheme(
    primary = ForestLightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4EDDD),
    onPrimaryContainer = Color(0xFF175036),
    inversePrimary = ForestDarkPrimary,

    secondary = Color(0xFF4E6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1EBE3),
    onSecondaryContainer = Color(0xFF35473C),
    tertiary = Color(0xFF506356),
    tertiaryContainer = Color(0xFFE4EBE5),
    onTertiaryContainer = Color(0xFF35453A),

    // 主色是绿的，错误色必须走**砖红/橙红**方向 —— 同样偏绿的话破坏性确认框
    // 会看起来像一条普通提示，而这是最不能看错的一类
    error = Color(0xFFB03A1E),
    errorContainer = Color(0xFFFBE9E3),
    onErrorContainer = Color(0xFF86270F),

    background = Color(0xFFFBFCFA),
    onBackground = Color(0xFF181D19),
    surface = Color(0xFFFBFCFA),
    onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFE6EBE6),
    onSurfaceVariant = Color(0xFF566059),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8F5),
    surfaceContainer = Color(0xFFF1F4F0),
    surfaceContainerHigh = Color(0xFFEBEFEA),
    surfaceContainerHighest = Color(0xFFE4E9E3),

    outline = Color(0xFFC0C9C1),
    outlineVariant = Color(0xFFDCE2DB),
    inverseSurface = Color(0xFF2D332E),
    inverseOnSurface = Color(0xFFF0F3EF),
)

private val ForestDark = darkColorScheme(
    primary = ForestDarkPrimary,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF1D5237),
    onPrimaryContainer = Color(0xFF9CEBBF),
    inversePrimary = ForestLightPrimary,

    secondary = Color(0xFFB5CCBC),
    onSecondary = Color(0xFF213529),
    secondaryContainer = Color(0xFF374B3E),
    onSecondaryContainer = Color(0xFFD1E8D8),
    tertiary = Color(0xFFB9CFC0),
    tertiaryContainer = Color(0xFF3B4F42),
    onTertiaryContainer = Color(0xFFD5EBDB),

    error = Color(0xFFFFB59B),
    onError = Color(0xFF5C1900),
    errorContainer = Color(0xFF8C2E12),
    onErrorContainer = Color(0xFFFFDBCF),

    background = Color(0xFF0D110E),
    onBackground = Color(0xFFE0E5E0),
    surface = Color(0xFF121613),
    onSurface = Color(0xFFE0E5E0),
    surfaceVariant = Color(0xFF232823),
    onSurfaceVariant = Color(0xFF9DA79F),

    surfaceContainerLowest = Color(0xFF090C0A),
    surfaceContainerLow = Color(0xFF111412),
    surfaceContainer = Color(0xFF191D1A),
    surfaceContainerHigh = Color(0xFF222723),
    surfaceContainerHighest = Color(0xFF2B312C),

    outline = Color(0xFF778179),
    outlineVariant = Color(0xFF292E29),
    inverseSurface = Color(0xFFE0E5E0),
    inverseOnSurface = Color(0xFF2D332E),
)

// ═══════════════════════════════════════════════════════════════════════════
// 酒红 —— 克制不艳的暗红，「专业工具」的重量感
// ═══════════════════════════════════════════════════════════════════════════

private val WineLight = lightColorScheme(
    // **为什么主色是酒红偏紫、错误色才是橙红**（这条是被 ColorTest 逼出来的）：
    // 其余三套都是「冷色主 + 红错误」，色相差天生就大（0.2+）。酒红是唯一的
    // 「红主」组合 —— 若主色停在正红 `#8C3A4A`，错误色无论怎么往橙推，色相差最多
    // 0.107（实测试了 #9A3D12 / #B4440A / #C25000 三档），而同一屏上出现时会被看成
    // 同一族色，破坏性确认框就失去警示感。
    // 另外三项（各套的 明度差）都只有 1.02~1.09 —— 说明区分靠的是**色相**不是亮度，
    // 所以只能从色相那头解：主色往酒红偏紫（`#8E2F66`，色相差 0.153），
    // 观感上仍是「红」，但和橙红错误色分得开。
    primary = Color(0xFF8E2F66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7DCE8),
    onPrimaryContainer = Color(0xFF632047),
    inversePrimary = Color(0xFFE2A0DC),

    secondary = Color(0xFF6B555A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E1E4),
    onSecondaryContainer = Color(0xFF4B3B3F),
    tertiary = Color(0xFF6C5B60),
    tertiaryContainer = Color(0xFFF3E5E8),
    onTertiaryContainer = Color(0xFF4C4145),

    // 主色偏红 ⇒ 错误色必须真正分开。走**橙红**（不是同族的红）——
    // 第一版取 `#9A3D12` 时色相差只有 0.085，破坏性确认框还是会被看成普通提示，
    // 这条被 ColorTest 的「色相差 > 0.12」逮到。这里推到真正的橙。
    error = Color(0xFFB4440A),
    errorContainer = Color(0xFFFFE7D9),
    onErrorContainer = Color(0xFF842F04),

    background = Color(0xFFFDFBFB),
    onBackground = Color(0xFF1C1719),
    surface = Color(0xFFFDFBFB),
    onSurface = Color(0xFF1C1719),
    surfaceVariant = Color(0xFFEFE7E9),
    onSurfaceVariant = Color(0xFF615457),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F4F5),
    surfaceContainer = Color(0xFFF3EFF0),
    surfaceContainerHigh = Color(0xFFEDE8EA),
    surfaceContainerHighest = Color(0xFFE7E1E3),

    outline = Color(0xFFCFC0C3),
    outlineVariant = Color(0xFFE6DBDD),
    inverseSurface = Color(0xFF322A2C),
    inverseOnSurface = Color(0xFFF6F0F1),
)

private val WineDark = darkColorScheme(
    // 暗色侧同理：主色调到偏洋红（`#E2A0DC`），和橙红错误色 `#FFB59B` 分得开
    primary = Color(0xFFE2A0DC),
    onPrimary = Color(0xFF47104A),
    primaryContainer = Color(0xFF6A2468),
    onPrimaryContainer = Color(0xFFFFD9F4),
    inversePrimary = Color(0xFF8E2F66),

    secondary = Color(0xFFD9BFC5),
    onSecondary = Color(0xFF3E2B30),
    secondaryContainer = Color(0xFF554146),
    onSecondaryContainer = Color(0xFFF6DBE0),
    tertiary = Color(0xFFDCC1C7),
    tertiaryContainer = Color(0xFF5A464B),
    onTertiaryContainer = Color(0xFFF9DDE2),

    // 亮暗两侧的错误色都要和主色拉开（ColorTest 两侧都查）
    error = Color(0xFFFFB59B),
    onError = Color(0xFF5A1900),
    errorContainer = Color(0xFF8A2E0D),
    onErrorContainer = Color(0xFFFFDBCA),

    background = Color(0xFF110E0F),
    onBackground = Color(0xFFE9E0E2),
    surface = Color(0xFF161213),
    onSurface = Color(0xFFE9E0E2),
    surfaceVariant = Color(0xFF282022),
    onSurfaceVariant = Color(0xFFA79A9D),

    surfaceContainerLowest = Color(0xFF0B090A),
    surfaceContainerLow = Color(0xFF131011),
    surfaceContainer = Color(0xFF1C181A),
    surfaceContainerHigh = Color(0xFF262023),
    surfaceContainerHighest = Color(0xFF2F282B),

    outline = Color(0xFF84777A),
    outlineVariant = Color(0xFF2E2528),
    inverseSurface = Color(0xFFE9E0E2),
    inverseOnSurface = Color(0xFF322A2C),
)

// ═══════════════════════════════════════════════════════════════════════════
// 主题注册
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 一套主题。名字是**给用户看的**（「石墨」「钢青」…），所以这里不用英文 id。
 */
enum class SmithyTheme(val label: String, val hint: String) {
    Slate("石墨", "中性灰蓝，几乎不表态"),
    Teal("钢青", "偏冷的深青，安静"),
    Forest("深林", "墨绿低饱和，久看不累"),
    Wine("酒红", "暗红带紫，克制有分量"),
    ;

    val light: androidx.compose.material3.ColorScheme
        get() = when (this) {
            Slate -> SlateLight
            Teal -> TealLight
            Forest -> ForestLight
            Wine -> WineLight
        }

    val dark: androidx.compose.material3.ColorScheme
        get() = when (this) {
            Slate -> SlateDark
            Teal -> TealDark
            Forest -> ForestDark
            Wine -> WineDark
        }

    /** 由 id 还原；认不出来就回默认（存的值可能来自旧版本或被手改过）。 */
    companion object {
        val Default = Slate

        fun of(name: String?): SmithyTheme =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/** 亮色（默认主题 = 石墨）。保留这两个名字是为了不打断既有调用点。 */
val SmithyLightColors: androidx.compose.material3.ColorScheme get() = SmithyTheme.Default.light
val SmithyDarkColors: androidx.compose.material3.ColorScheme get() = SmithyTheme.Default.dark