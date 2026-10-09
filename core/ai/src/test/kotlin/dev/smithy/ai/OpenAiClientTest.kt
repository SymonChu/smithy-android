package dev.smithy.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 流式解析。
 *
 * 用真的 HTTP 服务端（MockWebServer）而不是 mock OkHttp 的内部行为 ——
 * 要验的恰恰是**协议细节**：分片怎么拼、`[DONE]` 怎么收尾、错误对象混在流里怎么办。
 * 把这些换成 mock，测的就只是自己的假设了。
 */
class OpenAiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = OpenAiClient(
        AiConfig(
            baseUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            model = "test-model",
        ),
    )

    /** 把若干 JSON chunk 拼成一个 SSE 响应。 */
    private fun sse(vararg chunks: String, code: Int = 200): MockResponse {
        val m = MockResponse().setResponseCode(code)
        if (code == 200) {
            m.setHeader("Content-Type", "text/event-stream")
            m.setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
        } else {
            m.setBody("""{"error":{"message":"upstream says no"}}""")
        }
        return m
    }

    private fun textChunk(text: String) =
        """{"choices":[{"delta":{"content":"$text"}}]}"""

    private fun toolChunk(payload: String) =
        """{"choices":[{"delta":$payload}]}"""

    @Test
    fun `文本增量按顺序拼起来`() = runBlocking {
        server.enqueue(sse(textChunk("改"), textChunk("好"), textChunk("了")))
        server.enqueue(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))

        val events = client().stream(listOf(ChatMessage.user("改个名"))).toList()
        val text = events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta }

        assertEquals("改好了", text)
        assertTrue(events.any { it is ChatEvent.Finished })
    }

    @Test
    fun `工具调用的参数分片会被拼成完整 JSON`() = runBlocking {
        // 上游把一次调用拆成三片：第一片给 id 与函数名，后两片只给 arguments 片段。
        // 只取最后一片会得到半截 JSON —— 这是实现里最容易错的地方，所以单独盯一条。
        server.enqueue(
            sse(
                toolChunk("""{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"arsc.set","arguments":"{\"res"}}]}"""),
                toolChunk("""{"tool_calls":[{"index":0,"function":{"arguments":"Name\":\"@string/app_name\","}}]}"""),
                toolChunk("""{"tool_calls":[{"index":0,"function":{"arguments":"\"value\":\"铁匠铺\"}"}}]}"""),
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )

        val calls = client()
            .stream(listOf(ChatMessage.user("把应用名改成铁匠铺")))
            .toList()
            .filterIsInstance<ChatEvent.ToolCalls>()
            .single()
            .calls

        assertEquals(1, calls.size, "三片应该合成一次调用")
        assertEquals("call_1", calls[0].id)
        assertEquals("arsc.set", calls[0].function.name)

        // 重点：拼出来的必须是一段能解析的完整 JSON
        val parsed = Json.parseToJsonElement(calls[0].function.arguments).jsonObject
        assertEquals("@string/app_name", parsed["resName"]!!.jsonPrimitive.content)
        assertEquals("铁匠铺", parsed["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `多个工具调用按 index 分别累积 不会串味`() = runBlocking {
        // 注意每个 chunk 都得是**单行** —— SSE 的 data 行里出现裸换行会被切成两条无效事件
        // （这个坑我自己先在测试数据上踩了一次）
        server.enqueue(
            sse(
                toolChunk(
                    """{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"entry.read","arguments":"{\"path\":"}},{"index":1,"id":"call_b","function":{"name":"entry.list","arguments":"{\"pre"}}]}""",
                ),
                toolChunk(
                    """{"tool_calls":[{"index":1,"function":{"arguments":"fix\":\"res/\"}"}},{"index":0,"function":{"arguments":"\"AndroidManifest.xml\"}"}}]}""",
                ),
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )

        val calls = client()
            .stream(listOf(ChatMessage.user("看看")), tools = buildJsonArray { })
            .toList()
            .filterIsInstance<ChatEvent.ToolCalls>()
            .single()
            .calls

        assertEquals(2, calls.size)
        // 两个调用的分片是交错到达的，按 index 归位才对得上
        val a = calls.first { it.id == "call_a" }
        val b = calls.first { it.id == "call_b" }
        assertEquals("entry.read", a.function.name)
        assertEquals("AndroidManifest.xml", Json.parseToJsonElement(a.function.arguments).jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("entry.list", b.function.name)
        assertEquals("res/", Json.parseToJsonElement(b.function.arguments).jsonObject["prefix"]!!.jsonPrimitive.content)
    }

    @Test
    fun `混在流里的错误对象会变成 Failed 而不是被忽略`() = runBlocking {
        // 上游限额用完时会在流里塞一个 error 对象，而不是给 HTTP 错误码。
        // 当成普通 chunk 处理的话，用户会看到「模型没反应」而拿不到任何原因。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"error":{"message":"insufficient quota"}}""" + "\n\n"),
        )

        val events = client().stream(listOf(ChatMessage.user("hi"))).toList()
        val fail = events.filterIsInstance<ChatEvent.Failed>().firstOrNull()
        assertNotNull(fail, "流里的 error 对象必须被识别")
        assertTrue(fail.message.contains("quota"), "要把上游的原话带出来")
    }

    @Test
    fun `401 翻译成配置问题而不是甩英文原文`() = runBlocking {
        server.enqueue(sse(code = 401))
        val fail = client().stream(listOf(ChatMessage.user("hi")))
            .toList().filterIsInstance<ChatEvent.Failed>().single()

        assertEquals("BAD_KEY", fail.code)
        assertTrue(fail.message.contains("key"), "要指明是 key 的问题：${fail.message}")
    }

    @Test
    fun `404 会给出两种可用的填法`() = runBlocking {
        server.enqueue(sse(code = 404))
        val fail = client().stream(listOf(ChatMessage.user("hi")))
            .toList().filterIsInstance<ChatEvent.Failed>().single()

        assertEquals("BAD_ENDPOINT", fail.code)
        // 地址从「必须填到 /v1」放宽成三种填法都行之后，404 的含义变了：
        // 不再是「你填法不对」，而是「这个地址本身就找不到」。所以文案要给的是可用的写法，
        // 不是替用户判断他填错了哪一档
        assertTrue(fail.message.contains("/v1"), "要给出正确的写法：${fail.message}")
    }

    @Test
    fun `请求体带上了 stream 与工具数组`() = runBlocking {
        server.enqueue(sse(textChunk("hi"), """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))

        val tools = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", "apk.meta")
                            put("description", "看包的全貌")
                            put("parameters", buildJsonObject { put("type", "object") })
                        },
                    )
                },
            )
        }
        client().stream(listOf(ChatMessage.user("hi")), tools).toList()

        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertNotNull(body["tools"], "给了工具就必须带上")
        assertEquals("/v1/chat/completions", recorded.path, "路径拼错的话所有请求都会 404")
        assertTrue(recorded.getHeader("Authorization")!!.startsWith("Bearer "), "鉴权头不能少")
    }

    @Test
    fun `没给工具时请求体里就不该有 tools 字段`() = runBlocking {
        server.enqueue(sse(textChunk("hi"), """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        client().stream(listOf(ChatMessage.user("hi")), tools = null).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue("tools" !in body, "不该传空工具数组 —— 有的网关会因此报错")
    }

    @Test
    fun `工具结果消息带上了对应的 call id`() {
        // tool 消息少了 tool_call_id 或对不上，上游直接 400。构造入口只留一个就是为了防这个
        val m = ChatMessage.tool("call_1", """{"ok":true}""")
        assertEquals(ChatMessage.Role.TOOL, m.role)
        assertEquals("call_1", m.toolCallId)

        val json = Json.encodeToJsonElement(ChatMessage.serializer(), m).jsonObject
        assertEquals("tool", json["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", json["tool_call_id"]!!.jsonPrimitive.content)
    }

    // ── 上游不按 SSE 回：降级成一次性响应 ─────────────────────────

    /** 一整段非流式响应（Content-Type 是 application/json，不是 text/event-stream）。 */
    private fun whole(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun wholeText(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}]}"""

    @Test
    fun `网关不回 SSE 时自动降级成非流式`() = runBlocking {
        // 现场：很多中转/自建反代收下 stream:true 却回一整段 application/json，
        // 而 okhttp-sse 只认 text/event-stream，于是直接判失败 ——
        // 用户看到的是「同一个地址我别处能用，怎么这里连不上」。
        // 降级是这条差异的对策，所以两次响应都要排上
        server.enqueue(whole(wholeText("改好了")))
        server.enqueue(whole(wholeText("改好了")))

        val events = client().stream(listOf(ChatMessage.user("改个名"))).toList()

        assertEquals("改好了", events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta })
        assertTrue(
            events.any { it is ChatEvent.Finished },
            "降级这条路也必须收尾，否则界面永远停在「正在输入」",
        )

        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(first["stream"]!!.jsonPrimitive.boolean, "第一次仍是流式请求")
        assertFalse(second["stream"]!!.jsonPrimitive.boolean, "降级那次必须显式要非流式")
    }

    @Test
    fun `降级那次也能解析出工具调用`() = runBlocking {
        val body = """{"choices":[{"message":{"role":"assistant","content":null,""" +
            """"tool_calls":[{"id":"call_9","type":"function","function":{"name":"manifest.set",""" +
            """"arguments":"{\"field\":\"APP_LABEL\"}"}}]},"finish_reason":"tool_calls"}]}"""
        server.enqueue(whole(body))
        server.enqueue(whole(body))

        val calls = client()
            .stream(listOf(ChatMessage.user("把应用名改掉")), tools = buildJsonArray { })
            .toList()
            .filterIsInstance<ChatEvent.ToolCalls>()
            .single()
            .calls

        assertEquals(1, calls.size)
        assertEquals("call_9", calls[0].id)
        assertEquals("manifest.set", calls[0].function.name)
        assertEquals(
            "APP_LABEL",
            Json.parseToJsonElement(calls[0].function.arguments).jsonObject["field"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `HTTP 错误不会触发降级重试`() = runBlocking {
        // 只对「200 但不是 SSE」降级。4xx/5xx 再打一次结果一样，只会把一次失败变成两次，
        // 还多花用户的时间和额度
        server.enqueue(sse(code = 500))

        val fail = client().stream(listOf(ChatMessage.user("hi")))
            .toList().filterIsInstance<ChatEvent.Failed>().single()

        assertEquals("UPSTREAM_DOWN", fail.code)
        assertEquals(1, server.requestCount, "不该有第二次请求")
    }

    @Test
    fun `不是 JSON 的响应会给出可读的失败`() = runBlocking {
        // 典型现场：网关返回一个 HTML 错误页（200）。不翻译的话用户只会看到「模型没说话」
        server.enqueue(whole("<html>502 Bad Gateway</html>"))
        server.enqueue(whole("<html>502 Bad Gateway</html>"))

        val fail = client().stream(listOf(ChatMessage.user("hi")))
            .toList().filterIsInstance<ChatEvent.Failed>().single()

        assertEquals("BAD_BODY", fail.code)
        assertTrue(fail.message.contains("html"), "要把响应原文带出来一点：${fail.message}")
    }
}
