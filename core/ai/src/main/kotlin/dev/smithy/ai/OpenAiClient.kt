package dev.smithy.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的流式客户端。
 *
 * 走 `POST {base}/chat/completions`，`stream: true`，解析 SSE 事件流。
 *
 * ── 实现里两个容易踩的点 ──
 *
 * 1. **流式 tool_calls 必须拼片**。上游把一次工具调用拆成多个 chunk：第一个给 id 和函数名，
 *    之后只给 `arguments` 的片段，靠 `index` 区分是第几个调用。只取最后一个 chunk 拿到的
 *    是半截 JSON —— 而这个错误的表现是「模型偶尔说要调一个不存在的工具」，极难定位。
 *    所以这里按 index 累积，直到 `finish_reason` 出现才交付完整的调用列表。
 *
 * 2. **读超时不能按普通接口设**。流式响应里两个 chunk 之间可能隔很久（模型在思考或排队），
 *    按 10 秒设会在正常对话中被误判成超时。
 */
class OpenAiClient(
    private val config: AiConfig,
    private val client: OkHttpClient = defaultClient(),
) : ModelClient {

    override fun stream(messages: List<ChatMessage>, tools: JsonElement?): Flow<ChatEvent> = callbackFlow {
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", buildJsonArray { messages.forEach { add(JSON.encodeToJsonElement(it)) } })
            put("stream", true)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            if (tools != null) {
                put("tools", tools)
                // auto：让模型自己决定这轮是回话还是调工具。改包场景两种都常见 ——
                // 「这个包是干嘛的」只需要回话，「把应用名改掉」就得调工具。
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url("${config.normalizedBase}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        // index -> 半成品调用
        val acc = mutableMapOf<Int, PartialCall>()
        var finished = false

        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    finishIfNeeded()
                    return
                }

                val obj = runCatching { JSON.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

                // 上游会在流里塞错误对象（限流、余额、内容策略等），不能当正常 chunk 处理
                obj["error"]?.let { err ->
                    val msg = err.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
                    trySend(ChatEvent.Failed("UPSTREAM_ERROR", msg))
                    return
                }

                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
                val delta = choice["delta"]?.jsonObject

                delta?.get("content")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { trySend(ChatEvent.Text(it)) }

                delta?.get("tool_calls")?.jsonArray?.forEach { el ->
                    mergePartial(acc, el.jsonObject)
                }

                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
                    finishIfNeeded(it)
                }
            }

            override fun onClosed(eventSource: EventSource) = finishIfNeeded()

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                val detail = response?.let { r -> runCatching { r.body?.string() }.getOrNull() } ?: t?.message
                trySend(ChatEvent.Failed(classify(code, detail), explain(code, detail)))
                finished = true
                close()
            }

            /** 交付累积的工具调用并收尾。只做一次，避免 finish_reason 与 [DONE] 重复触发。 */
            fun finishIfNeeded(reason: String? = null) {
                if (finished) return
                finished = true
                if (acc.isNotEmpty()) {
                    trySend(ChatEvent.ToolCalls(acc.toSortedMap().values.map { it.build() }))
                    acc.clear()
                }
                trySend(ChatEvent.Finished(reason))
                close()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    // ── 分片累积 ─────────────────────────────────────────────

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()

        fun build() = ToolCall(
            id = id ?: "call_${System.nanoTime()}",
            function = FunctionCall(
                name = name.orEmpty(),
                arguments = args.toString().ifBlank { "{}" },
            ),
        )
    }

    private fun mergePartial(acc: MutableMap<Int, PartialCall>, el: JsonObject) {
        val index = el["index"]?.jsonPrimitive?.intOrNull ?: 0
        val slot = acc.getOrPut(index) { PartialCall() }

        el["id"]?.jsonPrimitive?.contentOrNull?.let { slot.id = it }
        el["function"]?.jsonObject?.let { fn ->
            fn["name"]?.jsonPrimitive?.contentOrNull?.let { slot.name = it }
            fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { slot.args.append(it) }
        }
    }

    // ── 错误翻译 ─────────────────────────────────────────────
    //
    // 这段的用处：用户配错了 key 或地址，如果只把上游原文甩给他（一段英文 JSON），
    // 他无法判断是自己配错了还是服务坏了。所以按状态码给结论 + 下一步。

    private fun classify(code: Int?, detail: String?): String = when {
        code == 401 -> "BAD_KEY"
        code == 403 -> "FORBIDDEN"
        code == 404 -> "BAD_ENDPOINT"
        code == 429 -> "RATE_LIMITED"
        code != null && code >= 500 -> "UPSTREAM_DOWN"
        detail?.contains("timeout", ignoreCase = true) == true -> "TIMEOUT"
        code == null -> "NETWORK"
        else -> "HTTP_$code"
    }

    private fun explain(code: Int?, detail: String?): String = when (classify(code, detail)) {
        "BAD_KEY" -> "API key 无效或已过期（401）。去设置里检查 key"
        "FORBIDDEN" -> "这把 key 没有调用该模型的权限（403）。换模型或换 key"
        "BAD_ENDPOINT" -> "接口地址不对（404）。baseUrl 应该形如 https://host/v1，" +
            "不要带 /chat/completions —— 那一段是客户端加的"
        "RATE_LIMITED" -> "触发了上游限流（429）。等一会儿再试，或者换一个网关"
        "UPSTREAM_DOWN" -> "上游服务异常（HTTP $code），不是你的配置问题。稍后再试"
        "TIMEOUT" -> "连接超时。检查网络，或者换个网关地址"
        "NETWORK" -> "网络请求发不出去：${detail?.take(200) ?: "没有更多信息"}"
        else -> "请求失败（HTTP $code）：${detail?.take(300) ?: "上游没给更多信息"}"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val JSON = Json {
            ignoreUnknownKeys = true      // 上游字段比我们关心的多得多
            explicitNulls = false         // 别把 content=null 发给上游
            encodeDefaults = false
            isLenient = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // 流式响应两个 chunk 之间可能隔很久（模型在思考 / 排队），
            // 按普通接口的 10 秒设会在正常对话里被误判成超时
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 客户端抽象。换协议（Anthropic / 自建）时只换这一层，Agent 循环不动。 */
interface ModelClient {
    /**
     * 发起一轮对话。
     *
     * [tools] 是 OpenAI 形态的工具数组（由 `ToolRegistry.toOpenAiSchema()` 产出），
     * 传 null 表示这一轮不给工具（比如让模型纯聊天）。
     */
    fun stream(messages: List<ChatMessage>, tools: JsonElement? = null): Flow<ChatEvent>
}
