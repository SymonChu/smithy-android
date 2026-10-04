package dev.smithy.toolkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 工具参数 schema 的小 DSL，以及读取模型参数的辅助。
 *
 * **为什么要有这一层**：参数 schema 是「给模型看的契约」。字段名拼错、类型写反、
 * 漏了 `required`，这些在运行前都看不出来 —— 表现出来只是「模型老是调不对这个工具」，
 * 而排查时很难想到是 schema 的问题。手写 `JsonObject` 太容易出这类错，
 * 所以用一层薄 DSL 把参数声明变成一眼能读的东西，并自动带 `additionalProperties: false`
 * 让模型别乱加字段。
 */
class ToolSchemaBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, desc: String, required: Boolean = false, choices: List<String>? = null) {
        props[name] = buildJsonObject {
            put("type", "string")
            put("description", JsonPrimitive(desc))
            choices?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
        }
        if (required) this.required += name
    }

    fun integer(name: String, desc: String, required: Boolean = false, min: Int? = null, max: Int? = null) {
        props[name] = buildJsonObject {
            put("type", "integer")
            put("description", JsonPrimitive(desc))
            min?.let { put("minimum", JsonPrimitive(it)) }
            max?.let { put("maximum", JsonPrimitive(it)) }
        }
        if (required) this.required += name
    }

    fun boolean(name: String, desc: String, required: Boolean = false) {
        props[name] = buildJsonObject {
            put("type", "boolean")
            put("description", JsonPrimitive(desc))
        }
        if (required) this.required += name
    }

    fun strings(name: String, desc: String, required: Boolean = false) {
        props[name] = buildJsonObject {
            put("type", "array")
            put("description", JsonPrimitive(desc))
            put("items", buildJsonObject { put("type", "string") })
        }
        if (required) this.required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
}

fun schema(block: ToolSchemaBuilder.() -> Unit): JsonObject = ToolSchemaBuilder().apply(block).build()

/** 参数不合法。由注册表统一翻译成带 hint 的 ToolResult，工具实现里不用自己拼错误。 */
class BadArgs(message: String, val hint: String? = null) : Exception(message)

/**
 * 没有绑定工作区。
 *
 * `ToolContext.requireWorkspace()` 在未绑定时必须抛它 —— 而不是返回 null 或抛别的，
 * 否则注册表那层没法把它翻译成「先让用户选个包」这种能自救的提示。
 */
class NoWorkspaceException : Exception("当前没有打开任何 APK")

/**
 * 读模型给的参数。
 *
 * 每个取值都容忍「模型给了个意料之外的类型」这种情况 —— 比如把 `limit` 传成字符串 `"50"`。
 * 模型出错是常态，工具该做的是尽量理解，实在理解不了再报明确错误。
 */
class ArgReader(private val args: JsonObject) {

    fun str(name: String, default: String? = null): String? =
        args[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotEmpty() }
            ?: default

    fun requireStr(name: String, hint: String? = null): String =
        str(name) ?: throw BadArgs("缺少参数 $name", hint ?: "请提供 $name")

    fun int(name: String, default: Int): Int =
        args[name]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: default

    fun bool(name: String, default: Boolean): Boolean =
        args[name]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
            ?: args[name]?.let { runCatching { it.jsonPrimitive.content.lowercase() == "true" }.getOrNull() }
            ?: default

    fun strList(name: String): List<String>? {
        val el = args[name] as? JsonArray ?: return null
        return el.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }

    /** 取一个枚举参数：值不在候选里就给明确提示，而不是让模型一直猜。 */
    fun <T : Enum<T>> enum(name: String, values: Array<T>, default: T): T {
        val raw = str(name) ?: return default
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw BadArgs(
                "$name 只能是 ${values.joinToString(" / ") { it.name }}，收到「$raw」",
                "换个合法值重试",
            )
    }
}
