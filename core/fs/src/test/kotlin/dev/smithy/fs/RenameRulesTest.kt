package dev.smithy.fs

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 批量改名的规则与计划。
 *
 * 这套逻辑出错的代价很高：一次改几十个文件、而且不可撤销。所以尽量把判断压到
 * 纯函数里，用干跑覆盖掉边界（隐藏文件、多重扩展名、编号、撞名），
 * 而不是靠「拿真文件试一遍看起来没问题」。
 */
class RenameRulesTest {

    private fun apply(name: String, rules: RenameRules, index: Int = 0, isDir: Boolean = false) =
        rules.apply(name, index, isDir)

    // ── 规则本身 ─────────────────────────────────────────────

    @Test
    fun `替换只作用于文件名 不碰扩展名`() {
        // 这条是防「把 .png 里的 p 也换掉」那类看起来毫无规律的错
        val rules = RenameRules(find = "a", replaceWith = "a")
        assertEquals("cat.png", apply("cat.png", rules))
    }

    @Test
    fun `前后缀与扩展名改写`() {
        val rules = RenameRules(prefix = "new_", suffix = "_v2", newExtension = "jpg")
        assertEquals("new_photo_v2.jpg", apply("photo.png", rules))
    }

    @Test
    fun `隐藏文件开头的点算名字 不算扩展名`() {
        // `.gitignore` 的「扩展名」是空，不能被当成 gitignore 这个扩展名
        val rules = RenameRules(prefix = "x_")
        assertEquals("x_.gitignore", apply(".gitignore", rules))
    }

    @Test
    fun `多重扩展名只动最后一段`() {
        val rules = RenameRules(newExtension = "gz")
        assertEquals("archive.tar.gz", apply("archive.tar.zip", rules))
    }

    @Test
    fun `编号按选择里的序号递增`() {
        val rules = RenameRules(numberFrom = 1)
        assertEquals("a_1.txt", apply("a.txt", rules, index = 0))
        assertEquals("b_2.txt", apply("b.txt", rules, index = 1))
    }

    @Test
    fun `目录不改扩展名`() {
        val rules = RenameRules(newExtension = "bak")
        assertEquals("myfolder", apply("myfolder", rules, isDir = true))
    }

    @Test
    fun `没有扩展名的文件加扩展名`() {
        assertEquals("README.md", apply("README", RenameRules(newExtension = "md")))
    }

    @Test
    fun `空规则不算生效`() {
        assertFalse(RenameRules().isEffective)
        // find 与 replaceWith 相同等于什么也没做，不该让「应用」亮起来
        assertFalse(RenameRules(find = "a", replaceWith = "a").isEffective)
        assertTrue(RenameRules(prefix = "x").isEffective)
    }

    // ── 计划与撞名 ────────────────────────────────────────────

    @Test
    fun `两个条目改到同一个名字会被挡住`() {
        // 改扩展名最容易撞：a.txt 与 a.md 一起改成 .log 就重名了。
        // 后果是后者覆盖前者，而文件系统不一定报错 —— 丢的文件要等用户
        // 下次找它才发现
        val plan = planRename(
            names = listOf("a.txt", "a.md"),
            rules = RenameRules(newExtension = "log"),
            existing = emptySet(),
        )
        assertEquals(setOf("a.log"), plan.conflicts)
        assertFalse(plan.canApply, "有撞名时不该允许执行")
    }

    @Test
    fun `与未选中的现有文件撞名也会被挡住`() {
        val plan = planRename(
            names = listOf("a.txt"),
            rules = RenameRules(find = "a", replaceWith = "keep"),
            existing = setOf("keep.txt", "other.txt"),
        )
        assertEquals(setOf("keep.txt"), plan.conflicts)
        assertFalse(plan.canApply)
    }

    @Test
    fun `选中集内部的名字不当作冲突`() {
        // a.txt → b.txt 而 b.txt 也在选中集里（它自己会让位），不算冲突
        val plan = planRename(
            names = listOf("a.txt", "b.txt"),
            rules = RenameRules(prefix = "z_"),
            existing = setOf("a.txt", "b.txt"),
        )
        assertTrue(plan.conflicts.isEmpty(), "选中集内部的名字不该算冲突，实际：${plan.conflicts}")
        assertTrue(plan.canApply)
    }

    @Test
    fun `没改动时不能执行`() {
        val plan = planRename(listOf("a.txt"), RenameRules(), emptySet())
        assertFalse(plan.hasChanges)
        assertFalse(plan.canApply, "什么都没改时不该允许点应用")
    }
}
