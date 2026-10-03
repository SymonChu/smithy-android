package dev.smithy.toolkit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import dev.smithy.engine.ApkProject

/**
 * 工具 = UI 与 AI 共用的唯一能力入口。
 * 手动操作和 AI 操作走同一套 Tool，保证「AI 改了什么，用户能看到同样的 diff 并回退」。
 */

/** 副作用等级，决定门控策略。 */
enum class Effect {
    READ,        // 直接执行
    WRITE,       // 默认需确认（可在设置里开信任模式）
    DESTRUCTIVE, // 强制确认：删文件、覆盖源包、安装、shell
}

@Serializable
data class ToolSpec(
    val name: String,          // 如 "dex.search_string"（点分命名，便于模型理解分组）
    val description: String,   // 给模型看的说明；写得准不准直接决定 AI 成功率
    val params: JsonObject,    // JSON Schema
    val returns: String,       // 返回结构说明（模型据此规划下一步）
    val effect: Effect,
)

interface Tool {
    val spec: ToolSpec
    suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult
}

interface ToolContext {
    /** 当前绑定的工程，未打开时为 null。 */
    val workspace: ApkProject?
    fun requireWorkspace(): ApkProject          // 未绑定时抛 NO_WORKSPACE
    val sessionId: String
    val callId: String
    fun progress(message: String)               // 向对话流/UI 推进度
    suspend fun confirm(request: ConfirmRequest): Boolean
}

@Serializable
data class ToolResult(
    val ok: Boolean,
    val data: JsonElement? = null,     // 结构化结果（模型可继续消费）
    val text: String? = null,          // 自然语言摘要（省 token）
    val error: ToolError? = null,
    val truncated: Boolean = false,    // 超出输出预算被截断
    val artifactPath: String? = null,  // 被截断的完整内容写到了哪个文件
)

@Serializable
data class ToolError(
    val code: String,      // UNSUPPORTED_ARSC / NO_WORKSPACE / ENGINE_FAILURE / ...
    val message: String,
    /**
     * 必须给出下一步建议。
     * 例：UNSUPPORTED_ARSC → "该包资源表为非常规格式，建议改用 rootfs 的 apktool 路径重试"。
     * 这一条决定 AI 能否自救，而不是卡死在同一处反复重试。
     */
    val hint: String?,
)

interface ToolRegistry {
    fun all(): List<Tool>
    fun find(name: String): Tool?

    /** 导出为 OpenAI/Anthropic function-calling 的 tools 数组，可只导出被允许的子集（角色策略）。 */
    fun toOpenAiSchema(allowed: Set<String>? = null): JsonElement
}

data class ConfirmRequest(
    val toolName: String,
    val effect: Effect,
    val summary: String,   // AI 自己说明"要做什么、影响什么"
    val diff: String? = null,
)

/** 工具输出预算：超过即截断并落文件，避免一次塞爆上下文。 */
object ToolBudget {
    const val DEFAULT_CHARS = 8_000
    const val SEARCH_HITS = 200
}
