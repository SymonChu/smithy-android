package dev.smithy.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 上下文压缩的行为。
 *
 * 这一环坏了很难发现：对话前十轮一切正常，直到某次历史变长，模型突然开始重复
 * 已经做过的事 —— 而那时没人会想到是压缩把目标丢了。
 */
class ContextCompactorTest {

    private fun tool(text: String) = ChatMessage(ChatMessage.Role.TOOL, text, toolCallId = "c1")

    private fun bigToolResult(chars: Int) = tool("结论：命中 3 处\n" + "明细行\n".repeat(chars / 8))

    @Test
    fun `没超限时原样返回`() {
        val history = listOf(
            ChatMessage.user("改个名字"),
            ChatMessage.assistant("好"),
            tool("改完了"),
        )
        assertEquals(history, ContextCompactor.compact(history))
    }

    @Test
    fun `超限时长工具结果被压短 但条数不变`() {
        val history = buildList {
            repeat(40) { add(bigToolResult(4_000)) }
            add(ChatMessage.user("最后这一句是目标"))
        }
        val compacted = ContextCompactor.compact(history)

        // 条数必须不变 —— 少一条就会把 assistant 的 tool_calls 和 tool 结果拆散，
        // 上游会直接报参数错误，而报错看不出是压缩导致的
        assertEquals(history.size, compacted.size, "压缩不能改变消息条数（会破坏配对）")
        assertTrue(
            ContextCompactor.sizeOf(compacted) < ContextCompactor.sizeOf(history),
            "压缩后应该更小",
        )
        assertTrue(
            compacted.any { it.content?.contains("此处省略") == true },
            "长的工具结果应该被压过",
        )
    }

    @Test
    fun `用户的话不会被压掉`() {
        val goal = "把应用名改成「铁匠铺」并把版本升到 2.0，然后装到手机上"
        val history = buildList {
            add(ChatMessage.user(goal))
            repeat(40) { add(bigToolResult(4_000)) }
            add(ChatMessage.user("继续"))
        }
        val compacted = ContextCompactor.compact(history)

        assertTrue(
            compacted.any { it.content == goal },
            "用户的目标必须原样保留 —— 丢了它模型会重复已做过的事",
        )
    }

    @Test
    fun `最近的消息保持原样`() {
        val history = buildList {
            repeat(40) { add(bigToolResult(4_000)) }
            repeat(20) { add(tool("最近的短结果 $it")) }
        }
        val compacted = ContextCompactor.compact(history)

        assertEquals(
            history.takeLast(5).map { it.content },
            compacted.takeLast(5).map { it.content },
            "最近的消息是模型正在处理的上下文，要原样保留",
        )
    }
}
