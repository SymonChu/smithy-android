package dev.smithy.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置菜单的结构。
 *
 * 这些页名与顺序是**产品决定**，但「四页都有名字、名字不重复、图标都有」这类是能被钉死的 ——
 * 加页时忘了给图标、或不小心和另一页重名，界面上会表现为两个一模一样的入口，
 * 而那种错没有编译错误、也没有日志。
 */
class SettingsMenuTest {

    @Test
    fun `四页都在 顺序是 外观 AI 权限 扩展`() {
        assertEquals(
            listOf("外观", "AI 接口", "权限", "扩展"),
            SettingsPage.entries.map { it.label },
            "设置页的集合或顺序变了 —— 确认这是有意的（新增页请同时想好插在哪一位）",
        )
    }

    @Test
    fun `页名不重复`() {
        val labels = SettingsPage.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "有两个页重名：$labels")
    }

    @Test
    fun `每页都有图标`() {
        // ImageVector 是懒加载的，拿不到「是否为空」；这里能查的是名字非空
        SettingsPage.entries.forEach {
            assertTrue(it.icon.name.isNotBlank(), "${it.label} 没有图标")
        }
    }

    @Test
    fun `菜单第一项是外观`() {
        // 0.1.6 起设置是「竖列菜单 → 整页」两级，进设置先看菜单，不再默认落在某一页
        // （默认进一页的话，另外三页的入口就藏在返回里）。这里钉的是菜单里的排位
        assertEquals("外观", SettingsPage.entries.first().label)
    }
}