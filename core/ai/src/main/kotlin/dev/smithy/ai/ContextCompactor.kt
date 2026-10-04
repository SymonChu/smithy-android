package dev.smithy.ai

import dev.smithy.ai.store.SessionEntity
import dev.smithy.ai.store.MessageEntity

/**
 * 上下文压缩。
 *
 * 长对话迟早撞上模型的上下文上限。这里**不是**「超了就丢最早的消息」——
 * 那会把任务目标一起丢掉，模型接下来就会重复已经做过的事，或者改了不该改的地方。
 *
 * 采用**结构化压缩**：
 * - 系统提示、用户的话（目标都在里面）**原样保留**
 * - 中间轮次的工具结果**压成摘要**（它们最长，而且结论通常一两行就够）
 * - 最近的若干条**原样保留**（模型正在处理的上下文）
 *
 * 不调模型做摘要：那要多花一次调用，而且摘要质量不可控 —— 规则化的做法
 * 至少保证「确定的不会被丢掉」。
 */
object ContextCompactor {

    /** 字符数达到这个量就压。粗略对应几万 token，留出余量给回复。 */
    private const val SOFT_LIMIT = 60_000

    /** 最近这么多条消息保持原样。 */
    private const val KEEP_RECENT = 24

    /** 工具结果超过这个长度才压。 */
    private const val TOOL_KEEP_CHARS = 400

    fun sizeOf(history: List<ChatMessage>): Int = history.sumOf { m ->
        (m.content?.length ?: 0) + (m.toolCalls?.sumOf { it.function.arguments.length } ?: 0)
    }

    /**
     * 需要的话返回压缩后的历史，否则原样返回。
     *
     * 注意返回的列表**必须保持消息配对**（带 tool_calls 的 assistant 后面紧跟对应 tool 结果）：
     * 拆散了上游会直接报参数错误，而且那个报错看不出是压缩导致的。
     * 这里只改 `content`（把长的截短），不删消息，所以配对天然不会坏。
     */
    fun compact(history: List<ChatMessage>): List<ChatMessage> {
        if (sizeOf(history) <= SOFT_LIMIT) return history

        val cut = (history.size - KEEP_RECENT).coerceAtLeast(0)
        if (cut == 0) return history

        return history.mapIndexed { index, message ->
            if (index >= cut) return@mapIndexed message
            val text = message.content ?: return@mapIndexed message
            if (message.role != ChatMessage.Role.TOOL || text.length <= TOOL_KEEP_CHARS) {
                return@mapIndexed message
            }
            // 工具结果的头部通常就是结论（「命中 3 处」「已改 5 个条目」），
            // 尾巴多半是清单明细 —— 所以留头留尾，中间那句注明省了多少
            val head = text.take(TOOL_KEEP_CHARS / 2)
            val tail = text.takeLast(TOOL_KEEP_CHARS / 2)
            message.copy(content = "$head\n…（此处省略 ${text.length - TOOL_KEEP_CHARS} 字符）\n$tail")
        }
    }
}
