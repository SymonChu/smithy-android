package dev.smithy.toolkit

import dev.smithy.engine.ApkProject
import dev.smithy.fs.ModuleProject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 模块（Magisk / Zygisk）与 ELF 工具。
 *
 * 这几条盯的是**容易默默错掉**的地方：门控没兜住会让破坏性操作直接执行，
 * 而 `zygote.restart` 会把所有正在跑的应用重启、用户没保存的东西全丢。
 */
class ModuleToolsTest {

    private class FakeContext(private val answer: Boolean = true) : ToolContext {
        override val workspace: ApkProject? = null
        override fun requireWorkspace(): ApkProject = throw NoWorkspaceException()
        override val sessionId: String = "test"
        override val callId: String = "test"
        override fun progress(message: String) = Unit
        val confirms = mutableListOf<ConfirmRequest>()
        override suspend fun confirm(request: ConfirmRequest): Boolean {
            confirms += request
            return answer
        }
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    private fun tmp(name: String, content: String): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-tool-$name").apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    // ── 门控 ────────────────────────────────────────────────

    @Test
    fun `zygote 重启在信任模式下也必须确认`() {
        val ctx = FakeContext()
        val registry = defaultRegistry(ConfirmPolicy(trustWrites = true))

        runBlocking { registry.invoke("module.zygote_restart", ctx, JsonObject(emptyMap())) }

        assertEquals(1, ctx.confirms.size, "它会重启所有应用，信任模式下也不能免确认")
        assertEquals(Effect.DESTRUCTIVE, ctx.confirms.first().effect)
    }

    @Test
    fun `刷模块在信任模式下也必须确认`() {
        val ctx = FakeContext()
        val registry = defaultRegistry(ConfirmPolicy(trustWrites = true))

        runBlocking {
            registry.invoke("module.install", ctx, args("zip" to "/tmp/whatever.zip"))
        }

        assertEquals(1, ctx.confirms.size, "刷入会改动设备，必须确认")
        assertEquals(Effect.DESTRUCTIVE, ctx.confirms.first().effect)
    }

    @Test
    fun `立即卸载在信任模式下也必须确认`() {
        val ctx = FakeContext()
        val registry = defaultRegistry(ConfirmPolicy(trustWrites = true))

        runBlocking { registry.invoke("module.uninstall", ctx, args("id" to "some_module")) }

        assertEquals(1, ctx.confirms.size)
        assertEquals(Effect.DESTRUCTIVE, ctx.confirms.first().effect)
    }

    @Test
    fun `拒绝之后不执行并提示别重试`() {
        val ctx = FakeContext(answer = false)

        val r = runBlocking {
            defaultRegistry().invoke("module.zygote_restart", ctx, JsonObject(emptyMap()))
        }

        assertEquals("USER_DENIED", r.error?.code)
        assertTrue(
            r.error?.hint?.contains("不要重试") == true,
            "要说清别重试，否则模型会反复弹同一个确认框",
        )
    }

    // ── 不需要工作区 ────────────────────────────────────────

    @Test
    fun `模块的文件层工具按路径操作 不需要先打开工作区`() {
        // 这是有意的设计：模块不是 ApkProject，硬塞进工作区会让每个工具都要判断
        // 「现在是哪种工程」。所以它按路径操作 —— 没工作区也该能跑
        val r = runBlocking {
            defaultRegistry().invoke("module.inspect", FakeContext(), args("zip" to "/tmp/does-not-exist.zip"))
        }

        assertTrue(
            r.error?.code != "NO_WORKSPACE",
            "不该要求工作区，实际错误码 ${r.error?.code}",
        )
        assertEquals("NOT_A_MODULE", r.error?.code)
    }

    @Test
    fun `缺模块 zip 参数时说清缺哪个`() {
        val r = runBlocking {
            defaultRegistry().invoke("module.inspect", FakeContext(), JsonObject(emptyMap()))
        }
        assertEquals("BAD_ARGS", r.error?.code)
        assertTrue(r.error?.message?.contains("zip") == true, "要说清缺的是 zip")
    }

    // ── 拒绝的情形 ──────────────────────────────────────────

    @Test
    fun `不让我们删 module_prop`() {
        val zip = tmp("mod-del.zip", "not a real zip")
        val r = runBlocking {
            defaultRegistry().invoke(
                "module.delete_entry",
                FakeContext(),
                args("zip" to zip.absolutePath, "path" to "module.prop"),
            )
        }

        assertEquals("REFUSED", r.error?.code)
        assertTrue(
            r.error?.hint?.contains("set_enabled") == true,
            "要指出正确做法（停用/卸载），而不是只说不行",
        )
    }

    @Test
    fun `变长替换被拒绝并指向源码重编`() {
        // 对任何文件都成立：等长是这类补丁的命根子
        val f = tmp("elf-len.bin", "Hello Smithy World")
        val r = runBlocking {
            defaultRegistry().invoke(
                "elf.patch_string",
                FakeContext(),
                args("path" to f.absolutePath, "old" to "Hello Smithy World", "new" to "Hi"),
            )
        }

        assertEquals("PATCH_REJECTED", r.error?.code)
        val msg = r.error?.message.orEmpty()
        assertTrue(msg.contains("长度不一样"), "要说清是长度问题：$msg")
        // 拒绝的理由要指向正确路径，否则用户只会换个长度再试
        assertTrue(r.error?.hint?.contains("重编") == true, "要指向源码重编：${r.error?.hint}")
    }

    @Test
    fun `等长替换在普通文件上也能做 且长度不变`() {
        val f = tmp("elf-ok.bin", "Hello Smithy World")
        val out = File(f.parentFile, "elf-ok-patched.bin")
        out.delete()

        val r = runBlocking {
            defaultRegistry().invoke(
                "elf.patch_string",
                FakeContext(),
                args(
                    "path" to f.absolutePath,
                    "old" to "Hello Smithy World",
                    "new" to "Hello Smithy Werld",
                    "out" to out.absolutePath,
                ),
            )
        }

        assertTrue(r.ok, "等长该允许：${r.error?.message}")
        assertEquals(f.readBytes().size, out.readBytes().size, "长度必须不变")
        assertTrue(out.readText().contains("Werld"))
        // 原文件不该被动
        assertTrue(f.readText().contains("World"))
    }

    @Test
    fun `偏移看不懂时给出正确写法`() {
        val f = tmp("elf-off.bin", "Hello Smithy World")
        val r = runBlocking {
            defaultRegistry().invoke(
                "elf.patch_string",
                FakeContext(),
                args(
                    "path" to f.absolutePath,
                    "old" to "Hello Smithy World",
                    "new" to "Hello Smithy Werld",
                    "offset" to "十二",
                ),
            )
        }

        assertEquals("BAD_OFFSET", r.error?.code)
        assertTrue(r.error?.hint?.contains("十六进制") == true, "要说清偏移是十六进制的：${r.error?.hint}")
    }

    // ── 从零建一个模块 ──────────────────────────────────────

    /** 每次给一个干净目录：module.create 拒绝覆盖已存在的包，复用目录会互相干扰。 */
    private fun freshDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-create-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `新建模块给出一个结构正确的骨架`() {
        val dir = freshDir()
        val r = runBlocking {
            defaultRegistry().invoke(
                "module.create",
                FakeContext(),
                args("dir" to dir.path, "id" to "demo_mod", "name" to "演示", "description" to "给某个软件用"),
            )
        }

        assertTrue(r.ok, "新建该成功：${r.error?.message}")
        val zip = File(dir, "demo_mod.zip")
        assertTrue(zip.exists(), "骨架要落在 <dir>/<id>.zip")
        ModuleProject.open(zip).use { p ->
            assertEquals("demo_mod", p.prop?.id, "module.prop 得能被模块解析认出来")
            assertTrue("service.sh" in p.entryNames(), "接下来要填的是 service.sh")
        }
        // 结果里要说清下一步改哪个文件，否则模型建完就停在这里了
        assertTrue(r.text?.contains("service.sh") == true, "要指出下一步：${r.text}")
    }

    @Test
    fun `新建模块不覆盖已有的包`() {
        val dir = freshDir()
        val first = runBlocking {
            defaultRegistry().invoke("module.create", FakeContext(), args("dir" to dir.path, "id" to "same_mod"))
        }
        assertTrue(first.ok, "第一次该成功：${first.error?.message}")

        val firstBytes = File(dir, "same_mod.zip").readBytes()
        val again = runBlocking {
            defaultRegistry().invoke("module.create", FakeContext(), args("dir" to dir.path, "id" to "same_mod"))
        }

        assertEquals("EXISTS", again.error?.code, "「新建」不该悄悄盖掉一个现有的模块")
        assertTrue(again.error?.hint?.contains("id") == true, "要指出换个 id：${again.error?.hint}")
        assertTrue(File(dir, "same_mod.zip").readBytes().contentEquals(firstBytes), "原包一个字节都不该动")
    }

    @Test
    fun `id 非法时说清允许什么字符`() {
        val r = runBlocking {
            defaultRegistry().invoke(
                "module.create",
                FakeContext(),
                args("dir" to freshDir().path, "id" to "带 空格"),
            )
        }

        assertEquals("CREATE_FAILED", r.error?.code)
        assertTrue(r.error?.hint?.contains("字母") == true, "要说清 id 的允许字符：${r.error?.hint}")
    }

    @Test
    fun `flavour 只认 shell 和 zygisk`() {
        val r = runBlocking {
            defaultRegistry().invoke(
                "module.create",
                FakeContext(),
                args("dir" to freshDir().path, "id" to "x", "flavour" to "java"),
            )
        }

        assertEquals("BAD_FLAVOUR", r.error?.code)
        assertTrue(r.error?.hint?.contains("zygisk") == true, "要说清两档各自适合什么：${r.error?.hint}")
    }
}
