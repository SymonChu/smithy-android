package dev.smithy.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 对话消息。字段命名与 OpenAI 的 Chat Completions 对齐（用 `@SerialName` 做映射）——
 * 这样上游协议怎么改，改动都只落在序列化层，内部逻辑不用迁就它的命名习惯。
 */
@Serializable
data class ChatMessage(
    val role: Role,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    @Serializable
    enum class Role {
        @SerialName("system") SYSTEM,
        @SerialName("user") USER,
        @SerialName("assistant") ASSISTANT,
        @SerialName("tool") TOOL,
    }

    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)
        fun user(text: String) = ChatMessage(Role.USER, text)
        fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)

        /**
         * 工具执行结果。
         *
         * `toolCallId` 必须与模型发来的那个 id 对应 —— 一对多、少一个、顺序错位，
         * 上游都会直接报 400。这是最容易出错的地方，所以构造入口只留这一个。
         */
        fun tool(callId: String, text: String) = ChatMessage(Role.TOOL, text, toolCallId = callId)
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    /**
     * **这是字符串形态的 JSON，不是对象** —— 上游就是这么给的。
     * 直接当对象用会解析失败，所以取用前一律走 [Args].
     */
    val arguments: String = "{}",
)

/**
 * 流式事件。
 *
 * 把「文本」和「工具调用」分成两类事件，是因为它们在 UI 上的呈现完全不同：
 * 文本要一边收一边往气泡里追加，工具调用要先弹确认卡片、等用户点了才继续。
 * 混成一个事件，UI 还得自己拆。
 */
sealed interface ChatEvent {
    /** 文本增量。 */
    data class Text(val delta: String) : ChatEvent

    /** 模型想调用工具。**一次性给全**（流式的分片已经拼好了）。 */
    data class ToolCalls(val calls: List<ToolCall>) : ChatEvent

    /** 本轮结束。[reason] 是上游的 finish_reason（`stop` / `tool_calls` / `length`）。 */
    data class Finished(val reason: String?) : ChatEvent

    /** 出错。到这一层说明重试也没用了，直接把原因交给调用方。 */
    data class Failed(val code: String, val message: String) : ChatEvent
}

/**
 * 模型接入配置（BYOK：用户自带 key）。
 *
 * `baseUrl` 默认指向官方，但改包用户里用中转/自建网关的很多，
 * 所以它是一等配置项而不是硬编码。
 */
@Serializable
data class AiConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    /**
     * 单轮回复的 token 上限。
     *
     * 改包场景的回复通常很短（「已改好，要不要装」），不需要大预算；
     * 而 agent 循环会跑很多轮，每轮都开大预算会很快烧掉用户的额度。
     */
    val maxTokens: Int = 4096,
) {
    val normalizedBase: String get() = baseUrl.trimEnd('/')

    val isUsable: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && normalizedBase.isNotBlank()
}
