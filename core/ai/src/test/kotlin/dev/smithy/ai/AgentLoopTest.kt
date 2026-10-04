package dev.smithy.ai

import dev.smithy.engine.ApkProject
import dev.smithy.toolkit.ArgReader
import dev.smithy.toolkit.ConfirmRequest
import dev.smithy.toolkit.DefaultToolRegistry
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.NoWorkspaceException
import dev.smithy.toolkit.Results
import dev.smithy.toolkit.Tool
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.ToolResult
import dev.smithy.toolkit.ToolSpec
import dev.smithy.toolkit.schema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent 循环。
 *
 * 用脚本化的假模型（按轮次预置事件）而不是真模型 —— 要验的是循环本身的行为：
 * 消息配对、失败怎么回给模型、轮数上限。这些用真模型测是碰运气，用脚本测才有确定性。
 */
class AgentLoopTest {

    // ── 假模型：按轮次返回预置事件，并记录每轮收到的消息 ──

    private class ScriptedClient(private val rounds: List<List<ChatEvent>>) : ModelClient {
        val requests = mutableListOf<List<ChatMessage>>()
        private var index = 0

        override fun stream(messages: List<ChatMessage>, tools: JsonElement?): Flow<ChatEvent> = flow {
            requests += messages.toList()
            val events = rounds.getOrElse(index) { listOf(ChatEvent.Finished("stop")) }
            index++
            events.forEach { emit(it) }
        }
    }

    private class FakeContext(
        override val workspace: ApkProject? = null,
        var confirmAnswer: Boolean = true,
    ) : ToolContext {
        override fun requireWorkspace(): ApkProject = workspace ?: throw NoWorkspaceException()
        override val sessionId = "test"
        override val callId = "call"
        val progressLog = mutableListOf<String>()
        override fun progress(message: String) = progressLog.add(message).let { }
        val confirmRequests = mutableListOf<ConfirmRequest>()
        override suspend fun confirm(request: ConfirmRequest): Boolean {
            confirmRequests += request
            return confirmAnswer
        }
    }

    // ── 测试用工具 ──

    private object EchoTool : Tool {
        override val spec = ToolSpec(
            name = "test.echo",
            description = "回显传入的文本（测试用）",
            params = schema { string("text", "要回显的内容", required = true) },
            returns = "echo: <文本>",
            effect = Effect.READ,
        )

        override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult =
            Results.ok("echo: ${ArgReader(args).str("text")}")
    }

    private object FailTool : Tool {
        override val spec = ToolSpec(
            name = "test.fail",
            description = "总是失败并带一条自救提示（测试用）",
            params = schema { },
            returns = "失败",
            effect = Effect.READ,
        )

        override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult =
            Results.fail("BOOM", "这个工具就是会失败", "换个办法：用 test.echo 试试")
    }

    private object WriteTool : Tool {
        override val spec = ToolSpec(
            name = "test.write",
            description = "一个写入类工具（测试门控用）",
            params = schema { },
            returns = "写了",
            effect = Effect.WRITE,
        )

        override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult = Results.ok("写了")
    }

    private fun registry(vararg tools: Tool) = DefaultToolRegistry(tools.toList())

    private fun textRound(text: String, reason: String = "stop") = listOf(
        ChatEvent.Text(text),
        ChatEvent.Finished(reason),
    )

    private fun callRound(vararg calls: ToolCall) = listOf(
        ChatEvent.ToolCalls(calls.toList()),
        ChatEvent.Finished("tool_calls"),
    )

    private fun call(name: String, args: String, id: String = "call_1") =
        ToolCall(id = id, function = FunctionCall(name, args))

    // ── 用例 ──

    @Test
    fun `模型直接回话时一次请求就结束`() = runBlocking {
        val client = ScriptedClient(listOf(textRound("这个包是 Smithy，一个改包工具。")))
        val events = AgentLoop(client, registry(EchoTool), maxRounds = 5)
            .run(listOf(ChatMessage.user("这是什么包")), FakeContext())
            .toList()

        assertEquals("这个包是 Smithy，一个改包工具。", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta })
        assertEquals(1, client.requests.size, "没有工具调用就不该再请求一轮")
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `一轮工具调用执行完把结果回给模型 再拿最终回复`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                callRound(call("test.echo", """{"text":"工作台"}""")),
                textRound("搜到了。"),
            ),
        )
        val events = AgentLoop(client, registry(EchoTool), maxRounds = 5)
            .run(listOf(ChatMessage.user("搜一下")), FakeContext())
            .toList()

        val started = events.filterIsInstance<AgentEvent.ToolStarted>()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(1, started.size)
        assertEquals("test.echo", started[0].name)
        assertTrue(finished[0].ok)
        assertTrue(finished[0].summary.contains("echo: 工作台"), "摘要里要能看到工具真返回了什么")

        // 第二轮请求里必须带上工具结果，否则模型看不见自己刚查到的东西
        val secondRequest = client.requests[1]
        assertTrue(secondRequest.last().content!!.contains("echo: 工作台"))
    }

    @Test
    fun `工具失败的 hint 会原文传给模型`() = runBlocking {
        val client = ScriptedClient(
            listOf(callRound(call("test.fail", "{}")), textRound("那我换一个办法。")),
        )
        AgentLoop(client, registry(FailTool), maxRounds = 5)
            .run(listOf(ChatMessage.user("试试看")), FakeContext())
            .toList()

        val toolMsg = client.requests[1].last { it.role == ChatMessage.Role.TOOL }.content!!
        assertTrue(toolMsg.contains("BOOM"), "要带上错误码")
        assertTrue(toolMsg.contains("换个办法"), "hint 是模型自救的唯一线索，漏掉它模型只能瞎试")
    }

    @Test
    fun `参数 JSON 坏掉时回一条可自救的错误 而不是中断整个循环`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                callRound(call("test.echo", """{"text":"被截断了""")),  // 故意不闭合
                textRound("抱歉，我重新调用一次。"),
            ),
        )
        val events = AgentLoop(client, registry(EchoTool), maxRounds = 5)
            .run(listOf(ChatMessage.user("搜一下")), FakeContext())
            .toList()

        // 循环没有中断：模型收到了错误，并且得到了继续对话的机会
        assertEquals(2, client.requests.size, "参数坏了也不该中断循环")
        val toolMsg = client.requests[1].last { it.role == ChatMessage.Role.TOOL }.content!!
        assertTrue(toolMsg.contains("BAD_ARGS_JSON"), "要明确告诉模型是参数格式问题：$toolMsg")
        assertTrue(events.any { it is AgentEvent.Text }, "模型应该还能继续说话")
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `模型反复要工具时会在轮数上限停下`() = runBlocking {
        // 每轮都调工具，永不回话 —— 真实场景里这就是"原地打转"
        val client = ScriptedClient(List(20) { callRound(call("test.echo", """{"text":"再来"}""")) })
        val events = AgentLoop(client, registry(EchoTool), maxRounds = 3)
            .run(listOf(ChatMessage.user("一直改")), FakeContext())
            .toList()

        val fail = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals("MAX_ROUNDS", fail.code)
        assertTrue(fail.message.contains("打转"), "要说清是它自己在打转，而不是含糊的「超出限制」")
        assertEquals(3, client.requests.size, "跑满上限就该停，不能多烧额度")
    }

    @Test
    fun `用户拒绝确认后 模型看到的是拒绝原因而不是成功`() = runBlocking {
        val client = ScriptedClient(
            listOf(callRound(call("test.write", "{}")), textRound("好的，我不改了。")),
        )
        val ctx = FakeContext(confirmAnswer = false)
        AgentLoop(client, registry(WriteTool), maxRounds = 5)
            .run(listOf(ChatMessage.user("改一下")), ctx)
            .toList()

        assertEquals(1, ctx.confirmRequests.size, "写入类工具必须先确认")
        val toolMsg = client.requests[1].last { it.role == ChatMessage.Role.TOOL }.content!!
        assertTrue(toolMsg.contains("USER_DENIED"), "模型得知道是被用户拒了，而不是自己搞错了")
        assertTrue(toolMsg.contains("不要重试"), "还要知道别再来一遍 —— 否则会反复弹确认框骚扰用户")
    }

    @Test
    fun `每轮都带上系统提示 且 assistant 与 tool 消息配对完整`() = runBlocking {
        val client = ScriptedClient(
            listOf(callRound(call("test.echo", """{"text":"x"}""", id = "call_abc")), textRound("好了")),
        )
        AgentLoop(client, registry(EchoTool), maxRounds = 5)
            .run(listOf(ChatMessage.user("hi")), FakeContext())
            .toList()

        // 系统提示每轮都在最前面
        client.requests.forEach { req ->
            assertEquals(ChatMessage.Role.SYSTEM, req.first().role, "系统提示必须在最前")
            assertTrue(req.first().content!!.contains("Smithy"), "系统提示内容要对")
        }

        // 上游要求：带 tool_calls 的 assistant 消息后面必须紧跟对应的 tool 结果。
        // 配对不齐会直接 400，而报错原文看不出是"消息配对"的问题 —— 所以这里盯死。
        val second = client.requests[1]
        val assistantWithCalls = second.last { it.role == ChatMessage.Role.ASSISTANT && it.toolCalls != null }
        val ids = assistantWithCalls.toolCalls!!.map { it.id }
        assertEquals(listOf("call_abc"), ids)

        val toolMsg = second.last { it.role == ChatMessage.Role.TOOL }
        assertEquals("call_abc", toolMsg.toolCallId, "tool 结果必须带上对应的 call id")
        assertTrue(
            second.indexOf(toolMsg) > second.indexOf(assistantWithCalls),
            "tool 结果要在 assistant 消息之后",
        )
    }

    @Test
    fun `模型报错时循环带着原始错误停下`() = runBlocking {
        val client = ScriptedClient(listOf(listOf(ChatEvent.Failed("BAD_KEY", "API key 无效（401）"))))
        val events = AgentLoop(client, registry(EchoTool), maxRounds = 5)
            .run(listOf(ChatMessage.user("hi")), FakeContext())
            .toList()

        val fail = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals("BAD_KEY", fail.code)
        assertEquals(1, client.requests.size, "模型侧就失败了，不该再重试")
    }
}
