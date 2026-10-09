package dev.smithy.toolkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 技能表的结构。
 *
 * 这里钉的是**「技能必须可执行」**这条原则：一条技能的步骤写得再顺，只要它引用的工具名
 * 不存在（或引用了写操作却标成只读），模型跑起来就会卡在半路 —— 而那种错没有编译错误、
 * 界面上也看不出来，只有跑一遍才发现。
 */
class SkillTest {

    private val tools = defaultTools().associateBy { it.spec.name }

    @Test
    fun `id 不重复`() {
        val ids = Skills.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "有两条技能用了同一个 id：$ids")
    }

    @Test
    fun `技能引用的工具都真实存在`() {
        Skills.all.forEach { skill ->
            skill.tools.forEach { name ->
                assertTrue(
                    tools.containsKey(name),
                    "技能「${skill.title}」引用了不存在的工具 $name —— 模型会看到一个调不动的名字",
                )
            }
        }
    }

    @Test
    fun `只读技能不得挂写操作工具`() {
        Skills.all.filter { it.readOnly }.forEach { skill ->
            skill.tools.forEach { name ->
                val effect = tools[name]?.spec?.effect
                assertEquals(
                    Effect.READ,
                    effect,
                    "技能「${skill.title}」标成只读，却挂了写操作工具 $name（$effect）",
                )
            }
        }
    }

    @Test
    fun `要么有流程 要么有引导 不能两头空`() {
        Skills.all.forEach { skill ->
            val hasFlow = skill.prompt.isNotBlank() && skill.tools.isNotEmpty()
            val hasGuide = !skill.guide.isNullOrBlank()
            assertTrue(hasFlow || hasGuide, "技能「${skill.title}」既没有流程也没有引导 —— 点了会没反应")
            assertTrue(
                !(hasFlow && hasGuide),
                "技能「${skill.title}」两边都有：引导型不该再挂工具和流程，否则点了不知道走哪条路",
            )
        }
    }

    @Test
    fun `流程型技能都要有摘要与工具`() {
        Skills.all.filter { it.prompt.isNotBlank() }.forEach { skill ->
            assertTrue(skill.summary.isNotBlank(), "技能「${skill.title}」缺一句话说明")
            assertTrue(skill.tools.isNotEmpty(), "技能「${skill.title}」有流程却没挂工具")
        }
    }

    @Test
    fun `快捷入口都能对到技能`() {
        assertTrue(Skills.quickPicks.size >= 4, "对话页的快捷入口太少")
        Skills.quickPicks.forEach { assertTrue(it in Skills.all, "${it.id} 不在技能表里") }
        // 体检必须能一键点到：它是「先看清再动手」的入口
        assertTrue(Skills.quickPicks.any { it.id == "inspect" }, "快捷入口里没有体检")
    }

    @Test
    fun `每条技能的流程都写清了先看再动`() {
        // 写操作类技能的流程里必须有「先确认/先看」这一步，
        // 否则模型会凭猜测直接改 —— 这是系统提示里最重要的一条，技能不该比它松
        Skills.all.filter { !it.readOnly && it.prompt.isNotBlank() }.forEach { skill ->
            val mentions = listOf("先", "确认", "看到", "搜").any { it in skill.prompt }
            assertTrue(mentions, "技能「${skill.title}」的流程里没有「先确认再动手」这一步")
        }
    }
}
