package dev.smithy.toolkit

import dev.smithy.engine.ApkProject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 注册表与工具集的行为。
 *
 * 这些用例全部不依赖样本 APK（用假上下文）—— 门控、schema、错误翻译都是纯逻辑，
 * 而它们恰恰是最不该出错的部分：门控错了会让破坏性操作静默执行，
 * 错误翻译错了会让模型卡在同一处反复重试。
 */
class ToolRegistryTest {

    /** 假上下文：记录确认请求，工作区可以为空。 */
    private class FakeContext(
        override val workspace: ApkProject? = null,
        var confirmAnswer: Boolean = true,
    ) : ToolContext {
        override fun requireWorkspace(): ApkProject = workspace ?: throw NoWorkspaceException()
        override val sessionId: String = "test-session"
        override val callId: String = "test-call"
        val progressLog = mutableListOf<String>()
        override fun progress(message: String) = progressLog.add(message).let { }
        val confirmRequests = mutableListOf<ConfirmRequest>()
        override suspend fun confirm(request: ConfirmRequest): Boolean {
            confirmRequests += request
            return confirmAnswer
        }
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    // ── schema 导出 ──────────────────────────────────────────

    @Test
    fun `导出的 schema 每个工具都有名字 说明 参数`() {
        val registry = defaultRegistry()
        val tools = registry.toOpenAiSchema().jsonArray

        assertEquals(defaultTools().size, tools.size, "导出的工具数应与注册的一致")
        assertTrue(tools.size >= 25, "M3 应覆盖 25 个以上工具，实际 ${tools.size}")

        tools.forEach { el ->
            val fn = el.jsonObject["function"]?.jsonObject ?: error("缺少 function 字段：$el")
            val name = fn["name"]?.jsonPrimitive?.content
            val desc = fn["description"]?.jsonPrimitive?.content
            val params = fn["parameters"]?.jsonObject

            assertNotNull(name, "工具有没名字的")
            assertTrue(name.contains('.'), "$name 应该用点分命名，便于模型理解分组")
            assertNotNull(desc, "$name 缺少说明")
            assertTrue(desc.length >= 20, "$name 的说明太短（${desc.length} 字符），模型会不知道什么时候用它")
            assertNotNull(params, "$name 缺少参数 schema")
            assertEquals("object", params["type"]?.jsonPrimitive?.content, "$name 的参数 schema 应该是 object")
            assertTrue("properties" in params, "$name 缺少 properties")
        }
    }

    @Test
    fun `按角色筛选取工具子集`() {
        val registry = defaultRegistry()
        val allowed = setOf("apk.meta", "dex.search")
        val exported = registry.toOpenAiSchema(allowed).jsonArray

        assertEquals(2, exported.size)
        val names = exported.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("apk.meta", "dex.search"), names.toSet())
    }

    // ── 门控 ────────────────────────────────────────────────

    @Test
    fun `READ 级工具不问确认`() {
        val ctx = FakeContext()
        val r = runBlocking { defaultRegistry().invoke("apk.meta", ctx, JsonObject(emptyMap())) }

        assertTrue(ctx.confirmRequests.isEmpty(), "READ 不该弹确认")
        assertFalse(r.ok, "没打开包时应该失败")
        assertEquals("NO_WORKSPACE", r.error?.code)
    }

    @Test
    fun `WRITE 级工具默认要确认 用户拒绝时不执行`() {
        val ctx = FakeContext(confirmAnswer = false)
        val r = runBlocking {
            defaultRegistry().invoke("arsc.set", ctx, args("resName" to "@string/app_name", "value" to "x"))
        }

        assertEquals(1, ctx.confirmRequests.size, "WRITE 应该问一次确认")
        assertEquals(Effect.WRITE, ctx.confirmRequests.first().effect)
        assertTrue(ctx.confirmRequests.first().summary.contains("arsc.set"), "摘要里要写清是哪个工具在动")
        assertEquals("USER_DENIED", r.error?.code)
        assertTrue(
            r.error?.hint?.contains("不要重试") == true,
            "拒绝后要明确告诉模型别重试 —— 否则它会反复弹同一个确认框骚扰用户",
        )
    }

    @Test
    fun `DESTRUCTIVE 在信任模式下仍然要确认`() {
        val ctx = FakeContext(confirmAnswer = true)
        // 信任模式只放开写入；装机、删条目这类回退不了的必须每次都问
        val registry = defaultRegistry(ConfirmPolicy(trustWrites = true))

        val r = runBlocking {
            registry.invoke("apk.install", ctx, JsonObject(emptyMap()))
        }
        assertEquals(1, ctx.confirmRequests.size, "信任模式下 DESTRUCTIVE 也该问")
        assertEquals(Effect.DESTRUCTIVE, ctx.confirmRequests.first().effect)
        assertFalse(r.ok)

        // 而同样处于信任模式的普通写入不该问
        val ctx2 = FakeContext(confirmAnswer = true)
        runBlocking {
            registry.invoke("entry.write", ctx2, args("path" to "a.txt", "text" to "hi"))
        }
        assertTrue(ctx2.confirmRequests.isEmpty(), "信任模式下普通写入不该问确认")
    }

    // ── 错误翻译 ─────────────────────────────────────────────

    @Test
    fun `调用不存在的工具会列出可用工具`() {
        val r = runBlocking { defaultRegistry().invoke("no.such.tool", FakeContext(), JsonObject(emptyMap())) }

        assertEquals("NO_SUCH_TOOL", r.error?.code)
        val hint = r.error?.hint.orEmpty()
        assertTrue(hint.contains("apk.meta"), "要告诉模型有哪些工具可用，否则它只能瞎试")
    }

    @Test
    fun `缺参数时说清缺哪个`() {
        // dex.search 先读参数再要工作区，所以这里能测到参数检查
        val r = runBlocking { defaultRegistry().invoke("dex.search", FakeContext(), JsonObject(emptyMap())) }

        assertEquals("BAD_ARGS", r.error?.code)
        assertTrue(r.error?.message?.contains("text") == true, "要说清缺的是 text 参数")
    }

    @Test
    fun `枚举参数给错值会列出合法取值`() {
        val r = runBlocking {
            defaultRegistry().invoke(
                "manifest.set",
                FakeContext(),
                args("field" to "NOT_A_FIELD", "value" to "x"),
            )
        }

        assertEquals("BAD_ARGS", r.error?.code)
        val msg = r.error?.message.orEmpty()
        assertTrue(msg.contains("APP_LABEL"), "要列出合法取值，模型才知道能传什么")
        assertTrue(msg.contains("NOT_A_FIELD"), "要回显它实际传了什么，便于它对照修正")
    }

    @Test
    fun `没有工作区时的提示是模型能自救的`() {
        val r = runBlocking { defaultRegistry().invoke("entry.list", FakeContext(), JsonObject(emptyMap())) }

        assertEquals("NO_WORKSPACE", r.error?.code)
        assertTrue(
            r.error?.hint?.contains("工作台") == true,
            "要指明「先让用户选个包」，而不是只说一句没有工作区",
        )
    }

    @Test
    fun `工具目录只给名字与一行说明`() {
        val catalog = defaultRegistry().catalog()
        assertTrue(catalog.contains("apk.meta"))
        assertTrue(catalog.contains("arsc.replace_string"))
        // 目录是塞进系统提示用的，每行不该太长
        catalog.lines().forEach { line ->
            assertTrue(line.length < 200, "目录行太长会挤占上下文：$line")
        }
    }
}
