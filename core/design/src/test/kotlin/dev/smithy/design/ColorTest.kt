package dev.smithy.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 色板的结构性检查。
 *
 * 色板是**手调的十六进制** —— 编译通过、界面能画，什么问题都发现不了。最容易出的三类错：
 * 1. **主色和错误色撞了**：破坏性确认框会看成普通提示，而这是最不能看错的一类；
 * 2. **暗色下文字对比度不够**：文字发灰 —— 这也是暗色不能直接反转亮色的原因；
 * 3. **某一套漏填了某个角色**：没填会静默落到 M3 默认的紫，与另外几套观感不一致。
 *
 * 这三条都能纯算出来，所以放到单测里钉死，不必靠肉眼。
 */
class ColorTest {

    // ── 1. 主色与错误色必须能区分 ────────────────────────────────

    /**
     * 色相差要大到「一眼分得开」。
     *
     * 0.12 是从肉眼定的：比它小的两块色摆在一起会被当成同一个色相家族，
     * 而这个界面里 error 与 primary 会同时出现在破坏性确认框上（标题图标 + 按钮）。
     */
    @Test
    fun `主色与错误色的色相要拉得开`() {
        SmithyTheme.entries.forEach { t ->
            listOf("亮" to t.light, "暗" to t.dark).forEach { (which, scheme) ->
                val d = hueDistance(scheme.primary, scheme.error)
                assertTrue(
                    d > 0.12,
                    "${t.label}·$which：主色与错误色色相差只有 $d —— " +
                        "破坏性确认框会看成普通提示。primary=${scheme.primary} error=${scheme.error}",
                )
            }
        }
    }

    /** 色相环上的最短距离（0..0.5）。用 RGB 反推色相，够用且不引第三方色彩库。 */
    private fun hueDistance(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
        val ha = hueOf(a)
        val hb = hueOf(b)
        val d = abs(ha - hb)
        return if (d > 0.5f) 1f - d else d
    }

    private fun hueOf(c: androidx.compose.ui.graphics.Color): Float {
        val r = c.red
        val g = c.green
        val b = c.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return 0f // 灰色没有色相
        val h = when (max) {
            r -> ((g - b) / delta) / 6f
            g -> ((b - r) / delta + 2f) / 6f
            else -> ((r - g) / delta + 4f) / 6f
        }
        return (h + 1f) % 1f
    }

    // ── 2. 暗色下的对比度 ────────────────────────────────────────

    /**
     * 暗色下正文与底色、以及主色与主色上的文字，都要够读。
     *
     * 4.5:1 是正文的无障碍下限；这里 4.0 起，因为这套是纯色块列表（没有长段正文），
     * 但**低于 3.5 一律不合格** —— 那个程度已经明显发灰。
     */
    @Test
    fun `暗色下文字要读得清`() {
        SmithyTheme.entries.forEach { t ->
            val s = t.dark
            assertTrue(
                contrast(s.onSurface, s.background) >= 4.0,
                "${t.label}：正文对比度只有 ${contrast(s.onSurface, s.background)} —— 暗底上会发灰",
            )
            assertTrue(
                contrast(s.onSurfaceVariant, s.background) >= 4.0,
                "${t.label}：次要文字对比度 ${contrast(s.onSurfaceVariant, s.background)} 偏低",
            )
            // 主色上的文字：按钮与选中态都用它
            assertTrue(
                contrast(s.onPrimary, s.primary) >= 4.0,
                "${t.label}：主色上的文字对比度只有 ${contrast(s.onPrimary, s.primary)}",
            )
        }
    }

    /** WCAG 相对亮度对比度。 */
    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun luminance(c: androidx.compose.ui.graphics.Color): Float {
        fun ch(v: Float): Float = if (v <= 0.03928f) v / 12.92f else Math.pow(
            ((v + 0.055f) / 1.055f).toDouble(), 2.4,
        ).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    // ── 3. 每套都必须自己调过（不能有 M3 默认紫漏进来）───────────────

    /**
     * 四套色板的表面色必须两两不同。
     *
     * 漏填某个角色时 M3 会给一个默认紫 —— 那在界面上是「某一块突然变紫」，很难倒查。
     * 这里查「四套之间不重复」，比逐个断言具体色值更耐改（调色时不必回来改测试）。
     */
    @Test
    fun `四套色板互不相同`() {
        val lightBg = SmithyTheme.entries.map { it.light.background }
        val darkBg = SmithyTheme.entries.map { it.dark.background }
        assertEquals(
            SmithyTheme.entries.size,
            lightBg.toSet().size,
            "亮色底色有重复：$lightBg（可能有套没覆盖到，漏成了 M3 默认色）",
        )
        assertEquals(
            SmithyTheme.entries.size,
            darkBg.toSet().size,
            "暗色底色有重复：$darkBg",
        )
    }

    /** 每套的亮/暗两版必须不同 —— 否则「跟随系统」这个行为就没了。 */
    @Test
    fun `每套都有独立的亮色与暗色`() {
        SmithyTheme.entries.forEach {
            assertNotEquals(it.light.background, it.dark.background, "${it.label} 亮暗底色一样")
            assertNotEquals(it.light.primary, it.dark.primary, "${it.label} 亮暗主色一样")
        }
    }

    // ── 4. 表面色的层次顺序 ──────────────────────────────────────

    /**
     * `surfaceContainer*` 五档必须**从低到高单调递增**（暗色下）。
     *
     * 界面里的层次全靠这个顺序（列表底 → 卡片 → 顶部栏），顺序一乱就会出现
     * 「卡片比底色还深」这种一眼别扭但说不清哪里错的观感。
     */
    @Test
    fun `暗色表面色五档递增`() {
        SmithyTheme.entries.forEach { t ->
            val s = t.dark
            val steps = listOf(
                s.surfaceContainerLowest,
                s.surfaceContainerLow,
                s.surfaceContainer,
                s.surfaceContainerHigh,
                s.surfaceContainerHighest,
            )
            steps.zipWithNext().forEachIndexed { i, (a, b) ->
                assertTrue(
                    luminance(b) > luminance(a),
                    "${t.label}：第 ${i + 1}→${i + 2} 档没有变亮（$a → $b）—— 层次顺序乱了",
                )
            }
        }
    }

    /** 亮色那一侧同理，但要从「白」往下走 —— 亮色是底越白越上层越灰。 */
    @Test
    fun `亮色表面色五档递减`() {
        SmithyTheme.entries.forEach { t ->
            val s = t.light
            val steps = listOf(
                s.surfaceContainerLowest,
                s.surfaceContainerLow,
                s.surfaceContainer,
                s.surfaceContainerHigh,
                s.surfaceContainerHighest,
            )
            steps.zipWithNext().forEachIndexed { i, (a, b) ->
                assertTrue(
                    luminance(b) < luminance(a),
                    "${t.label}：第 ${i + 1}→${i + 2} 档没有变暗（$a → $b）",
                )
            }
        }
    }

    // ── 5. 存取 ──────────────────────────────────────────────────

    @Test
    fun `认不出来的名字回默认而不是崩`() {
        // 存的值可能来自旧版本、被手改过、或被清数据搞坏 —— 那一律回默认，
        // 不能抛异常让 App 起不来
        assertEquals(SmithyTheme.Default, SmithyTheme.of("不存在的名字"))
        assertEquals(SmithyTheme.Default, SmithyTheme.of(null))
        assertEquals(SmithyTheme.Default, SmithyTheme.of(""))
        SmithyTheme.entries.forEach {
            assertEquals(it, SmithyTheme.of(it.name), "按名字还原：${it.name}")
        }
    }

    @Test
    fun `默认主题是石墨`() {
        assertEquals(SmithyTheme.Slate, SmithyTheme.Default, "默认色板变了 —— 说明有套没测到")
    }

    /** 每套都要有名字与说明 —— 空字符串的 hint 在界面上就是一行莫名其妙的话。 */
    @Test
    fun `每套都有名字与说明`() {
        SmithyTheme.entries.forEach {
            assertTrue(it.label.isNotBlank(), "${it.name} 没有中文名")
            assertTrue(it.hint.isNotBlank(), "${it.name} 没有说明")
        }
        assertEquals(
            SmithyTheme.entries.size,
            SmithyTheme.entries.map { it.label }.toSet().size,
            "中文名重复了",
        )
    }
}