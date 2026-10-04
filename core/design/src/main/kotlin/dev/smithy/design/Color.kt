package dev.smithy.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Smithy 的色板。
 *
 * 种色是一支偏冷的深青（[LightPrimary]）。选它的理由和这个工具的性质有关：
 * 它做的是**改包、刷模块、翻系统目录**这类有风险的动作，界面上会一直出现
 * 权限、警告、破坏性确认。深青在这种场景里比品牌蓝或绿更「安静」——
 * 突出内容，不和警告色抢注意力。
 *
 * **暗色不是把亮色反过来**：暗色下主色要提亮（`#0F6F6C` → `#5FC9C4`），否则
 * 在深底上对比度不够，文字会发灰。两套是分别调的。
 *
 * 这些值同时是 HTML 排版稿里用的 token，所以稿子和成品能对上。
 */
private val LightPrimary = Color(0xFF0F6F6C)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD9EEED)
private val LightOnPrimaryContainer = Color(0xFF0B5451)

private val LightSecondary = Color(0xFF4A6361)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFE4EFEE)
private val LightOnSecondaryContainer = Color(0xFF33403F)

private val LightTertiary = Color(0xFF4C5C70)
private val LightTertiaryContainer = Color(0xFFE6ECF4)
private val LightOnTertiaryContainer = Color(0xFF2C3A4A)

private val LightError = Color(0xFFB3261E)
private val LightErrorContainer = Color(0xFFFDECEB)
private val LightOnErrorContainer = Color(0xFF8C2F28)

private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF161A1D)
private val LightOnSurfaceVariant = Color(0xFF5A6169)
private val LightSurfaceVariant = Color(0xFFE9EDF1)
private val LightOutline = Color(0xFFC7CFD6)
private val LightOutlineVariant = Color(0xFFDFE4E9)

private val DarkPrimary = Color(0xFF5FC9C4)
private val DarkOnPrimary = Color(0xFF003734)
private val DarkPrimaryContainer = Color(0xFF123A38)
private val DarkOnPrimaryContainer = Color(0xFF8FDED9)

private val DarkSecondary = Color(0xFFB1CCC9)
private val DarkOnSecondary = Color(0xFF1C3533)
private val DarkSecondaryContainer = Color(0xFF2A4240)
private val DarkOnSecondaryContainer = Color(0xFFCDE9E6)

private val DarkTertiary = Color(0xFFB3C7DB)
private val DarkTertiaryContainer = Color(0xFF2B3A4A)
private val DarkOnTertiaryContainer = Color(0xFFCFE0F0)

private val DarkError = Color(0xFFF2B8B5)
private val DarkOnError = Color(0xFF601410)
private val DarkErrorContainer = Color(0xFF3A201E)
private val DarkOnErrorContainer = Color(0xFFF0A9A2)

private val DarkSurface = Color(0xFF161A1D)
private val DarkOnSurface = Color(0xFFE6EAEE)
private val DarkOnSurfaceVariant = Color(0xFF9AA3AC)
private val DarkSurfaceVariant = Color(0xFF252B30)
private val DarkOutline = Color(0xFF6F787F)
private val DarkOutlineVariant = Color(0xFF2B3238)

/** 亮色。只覆盖要用的角色，其余沿用 M3 默认 —— 少写一个就少一个拼错的机会。 */
val SmithyLightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = Color(0xFF8FDED9),

    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,

    error = LightError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,

    background = LightSurface,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,

    // 层次用这几档区分（列表底 / 卡片 / 顶部栏），而不是靠画分割线
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFF4F6F8),
    surfaceContainerHigh = Color(0xFFEDF1F4),
    surfaceContainerHighest = Color(0xFFE9EDF1),

    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = Color(0xFF2C3136),
    inverseOnSurface = Color(0xFFF1F3F5),
)

/** 暗色。主色系整体提亮，容器色反过来变深。 */
val SmithyDarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = Color(0xFF0F6F6C),

    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,

    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,

    background = Color(0xFF0F1214),
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,

    surfaceContainerLowest = Color(0xFF0B0E10),
    surfaceContainerLow = Color(0xFF141719),
    surfaceContainer = Color(0xFF1D2226),
    surfaceContainerHigh = Color(0xFF252B30),
    surfaceContainerHighest = Color(0xFF2C3339),

    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = Color(0xFFE6EAEE),
    inverseOnSurface = Color(0xFF2C3136),
)
