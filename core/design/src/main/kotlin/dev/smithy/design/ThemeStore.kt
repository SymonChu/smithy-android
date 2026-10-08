package dev.smithy.design

import android.content.Context

/**
 * 记住用户选的主题。
 *
 * 用 `SharedPreferences` 而不是 DataStore/Room：这里只有一个枚举值，
 * 而 DataStore 会带来协程与依赖的整套开销（理由同 `AiConfigStore`）。
 *
 * **不存「亮/暗」**：暗色跟随系统（`isSystemInDarkTheme()`），存下来会和系统打架 ——
 * 用户白天开了「亮」但系统是暗的，那这个开关就永远生效不了、变成一个骗人的开关。
 */
class ThemeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("smithy.theme", Context.MODE_PRIVATE)

    fun current(): SmithyTheme = SmithyTheme.of(prefs.getString(KEY, null))

    fun set(theme: SmithyTheme) {
        prefs.edit().putString(KEY, theme.name).apply()
    }

    private companion object {
        const val KEY = "palette"
    }
}