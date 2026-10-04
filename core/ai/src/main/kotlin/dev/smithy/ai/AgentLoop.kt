package dev.smithy.ai

import dev.smithy.toolkit.DefaultToolRegistry
import dev.smithy.toolkit.Results
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Agent 循环：模型 → 工具 → 模型 …… 直到模型不再要求调工具。
 *
 * ── 循环里的三处关键设计 ──
 *
 * 1. **轮数上限**。模型陷入「改一点、看一下、再改一点」的循环是常事，没有上限就会
 *    一直烧用户的额度。到顶后明确停下并说明「在原地打转」，而不是静默继续。
 *
 * 2. **参数解析失败不中断循环**。模型偶尔给出截断或语法错的 JSON。这时它自己就能修，
 *    所以把错误当一次「工具失败」回给它；要是抛异常中断整个循环，用户只会看到「出错了」，
 *    模型连解释的机会都没有。
 *
 * 3. **每轮都把 assistant 消息（含 tool_calls）与工具结果都记进历史**。少任何一条，
 *    下一次请求上游直接 400 —— 而错误信息通常看不出是「消息配对不齐」。
 */
class AgentLoop(
    private val client: ModelClient,
    private val registry: DefaultToolRegistry,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxRounds: Int = 12,
) {

    fun run(history: List<ChatMessage>, ctx: ToolContext): Flow<AgentEvent> = flow {
        // 系统提示每轮都放在最前，且不进入调用方传入的历史（历史是"对话"，提示是"配置"）
        val messages = mutableListOf(ChatMessage.system(systemPrompt))
        messages += history.filter { it.role != ChatMessage.Role.SYSTEM }

        var round = 0
        while (round < maxRounds) {
            round++
            emit(AgentEvent.Round(round))

            val text = StringBuilder()
            var calls = emptyList<ToolCall>()
            var failure: ChatEvent.Failed? = null

            // 长对话会撞上下文上限。压缩只截短长的工具结果、不改消息条数，
            // 所以 assistant 的 tool_calls 与 tool 结果的配对不会被打散
            val outgoing = ContextCompactor.compact(messages)
            client.stream(outgoing, registry.toOpenAiSchema()).collect { ev ->
                when (ev) {
                    is ChatEvent.Text -> {
                        text.append(ev.delta)
                        emit(AgentEvent.Text(ev.delta))
                    }

                    is ChatEvent.ToolCalls -> calls = ev.calls
                    is ChatEvent.Failed -> failure = ev
                    is ChatEvent.Finished -> Unit
                }
            }

            failure?.let {
                emit(AgentEvent.Failed(it.code, it.message))
                return@flow
            }

            // 记下这一轮的 assistant 消息。可能只有 tool_calls 没有文本 —— 那也是合法的，
            // 但 content 得给 null 而不是空字符串（空串会被上游当成"说了句空话"）
            messages += ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = text.toString().ifBlank { null },
                toolCalls = calls.ifEmpty { null },
            )

            if (calls.isEmpty()) {
                emit(AgentEvent.Done(messages))
                return@flow
            }

            for (call in calls) {
                emit(AgentEvent.ToolStarted(call.function.name, preview(call.function.arguments)))
                val result = execute(call, ctx)
                emit(
                    AgentEvent.ToolFinished(
                        name = call.function.name,
                        ok = result.ok,
                        summary = brief(result),
                        diff = result.diff,
                    ),
                )
                messages += ChatMessage.tool(call.id, encodeForModel(result))
            }
        }

        emit(
            AgentEvent.Failed(
                "MAX_ROUNDS",
                "连续 $maxRounds 轮工具调用还没收敛 —— 大概率在原地打转，已停下。" +
                    "把当前进展告诉用户，让他决定下一步，不要自动重试。",
            ),
        )
    }

    /**
     * 执行一次工具调用。
     *
     * 参数 JSON 解析失败时**回一条工具失败**而不是抛异常：模型自己就能修（重发一次），
     * 而抛异常会中断整个循环，用户只看到「出错了」、模型连解释的机会都没有。
     */
    private suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val args = runCatching { Json.parseToJsonElement(call.function.arguments).jsonObject }
            .getOrElse {
                return Results.fail(
                    "BAD_ARGS_JSON",
                    "参数不是合法 JSON：" + call.function.arguments.take(200),
                    "重新给一次这个工具的参数，确保是完整的 JSON 对象",
                )
            }
        return registry.invoke(call.function.name, ctx, args)
    }

    /**
     * 工具结果 → 给模型的文本。
     *
     * 成功时只给 text（省 token）；失败时把 code / message / **hint** 全带上 ——
     * hint 是模型自救的唯一线索，漏掉它模型只能瞎试。
     */
    private fun encodeForModel(r: ToolResult): String {
        if (r.ok) return r.text ?: r.data?.toString() ?: "(成功，没有输出)"

        val e = r.error ?: return "失败（没有更多信息）"
        return buildString {
            append("失败[").append(e.code).append("]：").append(e.message)
            e.hint?.let { append("\n下一步：").append(it) }
        }
    }

    /** 给 UI 看的一行摘要。 */
    private fun brief(r: ToolResult): String {
        if (r.ok) return (r.text ?: "成功").lineSequence().first().take(120)
        return "失败：" + (r.error?.message ?: "未知原因").take(120)
    }

    /** 参数预览（可能很长，截断）。 */
    private fun preview(args: String): String =
        if (args.length > 120) args.take(120) + "…" else args
}

/**
 * 循环里发生的事。UI 靠它画「正在调什么工具 / 成功还是失败」。
 */
sealed interface AgentEvent {
    /** 第几轮开始。UI 可以据此显示「第 N 轮」。 */
    data class Round(val index: Int) : AgentEvent

    /** 模型输出的文本增量。 */
    data class Text(val delta: String) : AgentEvent

    data class ToolStarted(val name: String, val argsPreview: String) : AgentEvent

    /**
     * 一次工具调用结束。
     *
     * [diff] 是这次调用**实际改动**了什么（来自改动记录，不是工具自报）。
     * 只用于界面展示，不给模型看 —— 模型那边有工具返回的 text 就够，
     * 多塞一份反而占上下文。
     */
    data class ToolFinished(
        val name: String,
        val ok: Boolean,
        val summary: String,
        val diff: String? = null,
    ) : AgentEvent

    /**
     * 一轮对话结束。
     *
     * 带上完整 [messages] —— 调用方要把它存进会话历史，下一轮才有上下文。
     * 只回一个「结束了」的话，调用方还得自己重放一遍整个流程才能拼出历史。
     */
    data class Done(val messages: List<ChatMessage>) : AgentEvent

    data class Failed(val code: String, val message: String) : AgentEvent
}

/**
 * 系统提示。
 *
 * 这份文本直接决定 AI 用得对不对，所以几条要求都是踩过才知道要写的：
 *
 * - **「先看再动」**：模型最爱干的事是凭猜测直接改。猜错了要来回好几轮，先确认反而快。
 * - **「改文案优先走资源表」**：不写这条，它会默认用 dex 那条路，然后每次等 27 秒
 *   （两个工具的代价差 87 倍，而描述里各说各的，模型未必会去比）。
 * - **「失败要读 hint」**：工具的 hint 是我们精心写的自救线索，不提醒它就会重复原调用。
 * - **边界**：这个项目的定位是「分析自己有权分析的包」。把这条写进系统提示，
 *   而不是等出事再事后追责 —— 模型的默认行为是尽量满足用户，得明确告诉它哪些不做。
 */
const val DEFAULT_SYSTEM_PROMPT = """
你是 Smithy 的改包助手，帮用户在手机上分析、修改 Android 应用包。

## 你在操作什么
你通过工具操作一个「工作区」，它是用户打开的一个 APK。所有改动先落在覆盖层里，
重打包之前原包不会被碰。用户能随时看到你改了什么，也能逐条撤销。

## 怎么做
1. **先看再动**。改动之前先确认目标真的存在（搜一下、列一下）。凭猜测直接改，
   猜错了要来回好几轮，先确认反而更快。不确定就搜。
2. **改文案优先走资源表**。arsc.replace_string 一次能改很多条（200 条约 300 毫秒）；
   dex.replace_string 每次都要重建 dex 的对象树（同样的量要 27 秒）。只在确认
   文案硬编码在代码里时才用后者。
3. **做完说清结果**：改了什么、影响多少处、下一步能做什么（打包？装机？）。
   别只说「完成了」。
4. **失败要读提示**。工具失败时会带一条「下一步」说明，照着做，不要重复原来的调用。
5. **用户没说清时先问**，不要在意图不明时大改一通。

## 边界（重要）
只处理用户自己有权分析的包。如果用户要求破解他人的商业软件、去除授权校验、
绕过付费、或者做批量分发，直接说明你不做这些并说明原因，然后问他要不要做别的。
不要因为「用户让做」就去做。

## 风格
中文，简短直接。用户是开发者，不用解释什么是 APK 或 dex。
"""
