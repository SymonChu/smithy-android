package dev.smithy.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * OpenAI 兼容的流式客户端。
 *
 * 走 `POST {base}/chat/completions`，`stream: true`，解析 SSE 事件流。
 * **地址怎么拼由 [Endpoints] 决定**（用户填 base、填到 /v1、填完整路径都能用）。
 *
 * ── 实现里三个容易踩的点 ──
 *
 * 1. **流式 tool_calls 必须拼片**。上游把一次工具调用拆成多个 chunk：第一个给 id 和函数名，
 *    之后只给 `arguments` 的片段，靠 `index` 区分是第几个调用。只取最后一个 chunk 拿到的
 *    是半截 JSON —— 而这个错误的表现是「模型偶尔说要调一个不存在的工具」，极难定位。
 *    所以这里按 index 累积，直到 `finish_reason` 出现才交付完整的调用列表。
 *
 * 2. **读超时不能按普通接口设**。流式响应里两个 chunk 之间可能隔很久（模型在思考或排队），
 *    按 10 秒设会在正常对话中被误判成超时。
 *
 * 3. **不是所有网关都真的会流式**。很多中转/自建反代收下 `stream: true` 却回一整段
 *    `application/json`（或者把 SSE 缓冲成一次响应），而 `okhttp-sse` 只认
 *    `Content-Type: text/event-stream`，于是直接判失败 —— 用户看到的是「这个地址我别处
 *    能用，怎么这里连不上」。所以下面有一条**降级路径**：发现响应根本不是流式，
 *    就用 `stream: false` 再要一次，把整段响应拆成同样的事件序列。
 *    流的价值只是「边收边显示」，拿不到就退回一次性响应，不该因此变成「连不上」。
 */
class OpenAiClient(
    private val config: AiConfig,
    private val client: OkHttpClient = defaultClient(config),
) : ModelClient {

    override fun stream(messages: List<ChatMessage>, tools: JsonElement?): Flow<ChatEvent> = callbackFlow {
        val request = request(payload(messages, tools, stream = true))

        // index -> 半成品调用
        val acc = mutableMapOf<Int, PartialCall>()
        var finished = false

        /** 已经改成非流式重试了。此后这条 SSE 流的回调（含我们自己取消它引发的那次）全部作废。 */
        var fellBack = false

        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                if (fellBack) return
                if (data == "[DONE]") {
                    finishIfNeeded()
                    return
                }

                val obj = runCatching { JSON.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

                // 上游会在流里塞错误对象（限流、余额、内容策略等），不能当正常 chunk 处理
                obj["error"]?.let { err ->
                    trySend(ChatEvent.Failed("UPSTREAM_ERROR", errorMessageOf(err)))
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

            override fun onClosed(eventSource: EventSource) {
                if (fellBack) return
                finishIfNeeded()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // 取消自己那条流时 OkHttp 也会回调 onFailure（"Canceled"），别再处理一遍
                if (fellBack) return

                if (!finished && looksLikeNonStreaming(response)) {
                    fellBack = true
                    eventSource.cancel()
                    launch(Dispatchers.IO) { emitWholeResponse(messages, tools) }
                    return
                }

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

    // ── 请求组装 ─────────────────────────────────────────────

    private fun payload(messages: List<ChatMessage>, tools: JsonElement?, stream: Boolean) = buildJsonObject {
        put("model", config.model)
        put("messages", buildJsonArray { messages.forEach { add(JSON.encodeToJsonElement(it)) } })
        put("stream", stream)
        put("temperature", config.temperature)
        put("max_tokens", config.maxTokens)
        if (tools != null) {
            put("tools", tools)
            // auto：让模型自己决定这轮是回话还是调工具。改包场景两种都常见 ——
            // 「这个包是干嘛的」只需要回话，「把应用名改掉」就得调工具。
            put("tool_choice", "auto")
        }
    }

    private fun request(body: JsonObject) = Request.Builder()
        .url(config.chatCompletionsUrl)
        .header("Authorization", "Bearer ${config.apiKey}")
        .post(body.toString().toRequestBody(JSON_MEDIA))
        .build()

    // ── 降级：上游不会流式时，改成一次性响应 ────────────────────

    /**
     * 这个失败是不是「上游压根没按 SSE 回」。
     *
     * 只认一种证据：**HTTP 200 + Content-Type 不是 `text/event-stream`**。
     * 连不上、TLS 失败、超时、4xx/5xx 都不重试 —— 那些再打一次也是一样的结果，
     * 只是把一次失败变成两次，还多花用户的时间和额度。
     */
    private fun looksLikeNonStreaming(response: Response?): Boolean {
        if (response == null || response.code != 200) return false
        val type = response.body?.contentType()
        val normalized = if (type == null) "" else "${type.type}/${type.subtype}"
        return normalized != "text/event-stream"
    }

    /** `stream: false` 那次请求：把整段响应拆成与流式同样的事件序列。 */
    private suspend fun ProducerScope<ChatEvent>.emitWholeResponse(
        messages: List<ChatMessage>,
        tools: JsonElement?,
    ) {
        try {
            val call = client.newCall(request(payload(messages, tools, stream = false)))
            call.execute().use { resp ->
                val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                if (!resp.isSuccessful) {
                    trySend(ChatEvent.Failed(classify(resp.code, body), explain(resp.code, body)))
                } else {
                    parseWholeResponse(body).forEach { trySend(it) }
                }
            }
        } catch (t: Throwable) {
            trySend(ChatEvent.Failed("NETWORK", explain(null, t.message ?: t.toString())))
        } finally {
            close()
        }
    }

    /** 一次性响应 → 事件序列。解析不出来时给出**看得懂**的失败，而不是空回复。 */
    private fun parseWholeResponse(body: String): List<ChatEvent> {
        val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return listOf(
                ChatEvent.Failed("BAD_BODY", "上游返回的不是 JSON：${body.take(200).ifBlank { "（空响应）" }}"),
            )
        obj["error"]?.let { return listOf(ChatEvent.Failed("UPSTREAM_ERROR", errorMessageOf(it))) }

        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return listOf(ChatEvent.Failed("BAD_BODY", "响应里没有 choices：${body.take(200)}"))

        val message = choice["message"]?.jsonObject
        val out = mutableListOf<ChatEvent>()

        message?.get("content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += ChatEvent.Text(it) }

        message?.get("tool_calls")?.jsonArray
            ?.mapNotNull { el -> wholeCall(el.jsonObject) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += ChatEvent.ToolCalls(it) }

        out += ChatEvent.Finished(choice["finish_reason"]?.jsonPrimitive?.contentOrNull)
        return out
    }

    private fun wholeCall(el: JsonObject): ToolCall? {
        val fn = el["function"]?.jsonObject ?: return null
        return ToolCall(
            id = el["id"]?.jsonPrimitive?.contentOrNull ?: "call_${System.nanoTime()}",
            function = FunctionCall(
                name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                arguments = fn["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}",
            ),
        )
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
        "BAD_ENDPOINT" -> "接口地址不对（404）。地址填 `https://host/v1` 或只填 `https://host` 都行" +
            "（缺省会补 /v1/chat/completions），别填成别的路径"
        "RATE_LIMITED" -> "触发了上游限流（429）。等一会儿再试，或者换一个网关"
        "UPSTREAM_DOWN" -> "上游服务异常（HTTP $code），不是你的配置问题。稍后再试"
        "TIMEOUT" -> "连接超时。检查网络，或者换个网关地址"
        "NETWORK" -> when {
            detail?.contains("CLEARTEXT", ignoreCase = true) == true ->
                "这个 http:// 地址被系统当成明文拦掉了。0.1.6 起已放行明文；还在报就是地址本身写错了"
            detail?.contains("Trust anchor", ignoreCase = true) == true ||
                detail?.contains("CertPathValidator", ignoreCase = true) == true ->
                "证书校验没过（自签证书 / 中间证书）。地址没写错的话，" +
                    "去设置里打开「跳过 TLS 校验」"
            detail?.contains("Failed to connect", ignoreCase = true) == true ||
                detail?.contains("Connection refused", ignoreCase = true) == true ->
                "连不上这个地址：${detail.take(200)}。确认手机能访问它、端口填对了"
            else -> "网络请求发不出去：${detail?.take(200) ?: "没有更多信息"}"
        }
        else -> "请求失败（HTTP $code）：${detail?.take(300) ?: "上游没给更多信息"}"
    }

    private fun errorMessageOf(err: JsonElement): String =
        err.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val JSON = Json {
            ignoreUnknownKeys = true      // 上游字段比我们关心的多得多
            explicitNulls = false         // 别把 content=null 发给上游
            encodeDefaults = false
            isLenient = true
        }

        fun defaultClient(config: AiConfig = AiConfig()): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // 流式响应两个 chunk 之间可能隔很久（模型在思考 / 排队），
            // 按普通接口的 10 秒设会在正常对话里被误判成超时
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply { if (config.skipTlsVerify) trustAllCertificates() }
            .build()

        /**
         * 信任一切证书 + 不做域名比对。
         *
         * 这是**用户显式打开**的开关（[AiConfig.skipTlsVerify] 默认 false）：自建网关
         * 的自签证书没法进系统信任库，而这里传的又是用户自己的 key。装配失败时静默跳过 ——
         * 结果就是「开了但没生效」，表现为原本的证书错误照旧，用户不至于更糊涂。
         */
        private fun OkHttpClient.Builder.trustAllCertificates() {
            runCatching {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                sslSocketFactory(context.socketFactory, trustAll)
                hostnameVerifier { _, _ -> true }
            }
        }
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
