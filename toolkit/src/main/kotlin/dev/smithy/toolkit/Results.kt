package dev.smithy.toolkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * 工具结果的构造与预算控制。
 *
 * 一条贯穿的原则：**失败必须带「下一步怎么办」**。模型卡在同一个错误上反复重试，
 * 十有八九是因为工具只回了「失败了」，它不知道该换什么办法。
 */
object Results {

    fun ok(text: String, data: JsonElement? = null): ToolResult =
        ToolResult(ok = true, text = text, data = data)

    fun fail(code: String, message: String, hint: String? = null): ToolResult =
        ToolResult(ok = false, error = ToolError(code, message, hint))

    /** 把任意 Kotlin 值转成 JSON。模型只认 JSON 类型，这里顺手抹平。 */
    fun any(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> buildJsonArray { v.forEach { add(any(it)) } }
        else -> JsonPrimitive(v.toString())
    }

    /** 便捷构造结构化结果。 */
    fun json(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, any(v))
    }

    fun arr(items: List<Any?>): JsonArray = buildJsonArray { items.forEach { add(any(it)) } }

    /**
     * 超预算就截断，并把完整内容落到文件。
     *
     * **为什么要落文件而不是直接丢**：一次 `smali.read_class` 可能就是几万字符，
     * 全塞进上下文会挤掉对话本身；但直接丢掉又会让模型误以为「内容就这么多」。
     * 所以截断 + 告知路径 —— 模型知道还有更多，需要时可以换更精确的工具去取。
     */
    fun truncate(text: String, sink: File?, budget: Int = ToolBudget.DEFAULT_CHARS): ToolResult {
        if (text.length <= budget) return ok(text)

        val path = sink?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(text)
                f.absolutePath
            }.getOrNull()
        }

        return ToolResult(
            ok = true,
            text = text.take(budget) +
                "\n\n…（原文 ${text.length} 字符，已截断到 $budget）" +
                if (path != null) "\n完整内容在：$path" else "",
            truncated = true,
            artifactPath = path,
        )
    }

    /**
     * 列表类结果的统一收尾：**说清「这是全部还是被截了」**。
     *
     * 不交代这一点，模型会把「返回 200 条」当成「一共就 200 条」，
     * 然后基于错误的前提给用户下结论 —— 这比报错更危险。
     */
    fun list(items: List<String>, total: Int, limit: Int, emptyHint: String? = null): ToolResult {
        if (items.isEmpty()) {
            return ok(emptyHint ?: "没有命中", json("count" to 0, "total" to total))
        }
        val body = items.joinToString("\n")
        val suffix = if (total > items.size) {
            "\n\n（共 $total 条，这里只列了前 ${items.size} 条 —— 要看得更全就缩小范围或调大 limit）"
        } else {
            ""
        }
        return ok("$body$suffix", json("count" to items.size, "total" to total, "limit" to limit))
    }
}
