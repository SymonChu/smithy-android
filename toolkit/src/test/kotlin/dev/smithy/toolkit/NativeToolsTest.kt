package dev.smithy.toolkit

import dev.smithy.fs.ModuleLayouts
import dev.smithy.fs.ModuleProject
import dev.smithy.fs.ModuleScaffold
import dev.smithy.fs.ModuleSkeletonSpec
import dev.smithy.fs.NativeToolchains
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * native 编译工具（`native.toolchain` / `module.build`）。
 *
 * 盯的是这条链上最容易「看起来做了其实没做」的两处：
 * 1. **没有工具链时**必须说清缺什么（否则模型会在同一个调用上反复重试）；
 * 2. **产物的条目名**必须是 `zygisk/<abi>.so` —— Magisk 按文件名认 ABI，写错了是
 *    「装上了但不加载」，在设备上极难查。
 *
 * 编译器用假 clang（脚本），所以这些用例在任何机器上都能跑；真 clang 那条在
 * `:core:fs` 的 `NativeBuildTest` 里，用 `SMITHY_NDK` 门控。
 */
class NativeToolsTest {

    @After
    fun tearDown() {
        // 工具链注册表是全局的：跑完清掉，免得干扰别的用例
        NativeToolchains.register(null)
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-native-tool-${System.nanoTime()}").apply { mkdirs() }

    /** 假 clang：把 `-o` 指的路径写出来，够让整条链跑通。 */
    private fun fakeToolchain(): File {
        val root = tmpDir()
        val bin = File(root, "bin").apply { mkdirs() }
        File(bin, "clang++").apply {
            writeText(
                """
                #!/bin/sh
                out=""
                while [ ${'$'}# -gt 0 ]; do
                  if [ "${'$'}1" = "-o" ]; then out="${'$'}2"; shift 2; continue; fi
                  shift
                done
                printf '\177ELF\002\001\001' > "${'$'}out"
                """.trimIndent(),
            )
            setExecutable(true)
        }
        File(root, "sysroot/usr/include").mkdirs()
        return root
    }

    private fun zygiskSkeleton(dir: File, id: String = "demo_native"): File {
        val zip = File(dir, "$id.zip")
        ModuleScaffold.write(
            ModuleSkeletonSpec(id = id, name = "演示", description = "给某个软件用", flavour = ModuleSkeletonSpec.Flavour.ZYGISK),
            zip,
        )
        return zip
    }

    // ── 没有工具链 ──────────────────────────────────────────

    @Test
    fun `没有工具链时 module_build 说清缺什么 并指出纯脚本那一档不需要它`() {
        NativeToolchains.register(null)
        val dir = tmpDir()
        val zip = zygiskSkeleton(dir)

        val r = defaultRegistry().call(
            "module.build",
            FakeContext(),
            args("zip" to zip.absolutePath, "abi" to "arm64-v8a"),
        )

        assertEquals("NO_TOOLCHAIN", r.error?.code)
        val hint = r.error?.hint.orEmpty()
        // 措辞要落成**能点的东西**：说按钮名、说体积，别只说「缺 clang + sysroot」
        assertTrue(hint.contains("下载工具链包") && hint.contains("导入工具链包"), "要给可操作的下一步：$hint")
        assertTrue(hint.contains("不需要编译"), "要给出退路（纯脚本模块照样能用）：$hint")
    }

    @Test
    fun `native_toolchain 报告当前这一份`() {
        NativeToolchains.register(NativeToolchains.locateIn(fakeToolchain()))

        val r = defaultRegistry().call("native.toolchain", FakeContext(), JsonObject(emptyMap()))

        assertTrue(r.ok, "有工具链就该成功：${r.error?.message}")
        assertTrue(r.text!!.contains("clang"), "要说出用的是哪个编译器：${r.text}")
    }

    @Test
    fun `native_toolchain 在没装时说清怎么装`() {
        NativeToolchains.register(null)

        val r = defaultRegistry().call("native.toolchain", FakeContext(), JsonObject(emptyMap()))

        assertEquals("NO_TOOLCHAIN", r.error?.code)
        assertTrue(r.error?.hint?.contains("154") == true, "要给下载体积这类可判断的信息：${r.error?.hint}")
    }

    // ── 编一次 ──────────────────────────────────────────────

    @Test
    fun `module_build 把源码编成 zygisk 下按 ABI 命名的 so`() {
        NativeToolchains.register(NativeToolchains.locateIn(fakeToolchain()))
        val dir = tmpDir()
        val zip = zygiskSkeleton(dir)

        val r = defaultRegistry().call(
            "module.build",
            FakeContext(),
            args("zip" to zip.absolutePath, "abi" to "arm64-v8a"),
        )

        assertTrue(r.ok, "该编过：${r.error?.message}")
        val out = File(dir, "demo_native-arm64-v8a.zip")
        assertTrue(out.exists(), "默认产物名该是 <原名>-<abi>.zip")
        ModuleProject.open(out).use { p ->
            val entries = p.entryNames()
            assertTrue("zygisk/arm64-v8a.so" in entries, "产物条目名不对：$entries")
            // 写对名字之后，模块才会被当成 Zygisk 模块 —— 这是「生效」的前提
            assertEquals(listOf("arm64-v8a"), ModuleLayouts.inspect(entries).knownAbis)
            assertTrue("module.prop" in entries, "原有条目不能丢")
        }
        // 原 zip 不动（和改包那条线一样：产物另存，不对还能重来）
        assertTrue(zip.exists())
        // 结果里要给出下一步，否则模型编完就停在这里
        assertTrue(r.text!!.contains("module.install"), "要指出下一步：${r.text}")
    }

    @Test
    fun `abi 写错时点名可选的值`() {
        NativeToolchains.register(NativeToolchains.locateIn(fakeToolchain()))
        val dir = tmpDir()

        val r = defaultRegistry().call(
            "module.build",
            FakeContext(),
            args("zip" to zygiskSkeleton(dir).absolutePath, "abi" to "mips"),
        )

        assertEquals("BAD_ABI", r.error?.code)
        assertTrue(r.error?.hint?.contains("arm64-v8a") == true, "要列出可选值：${r.error?.hint}")
    }

    @Test
    fun `纯脚本模块编译时说的是不需要编译`() {
        NativeToolchains.register(NativeToolchains.locateIn(fakeToolchain()))
        val dir = tmpDir()
        val zip = File(dir, "shell_only.zip")
        ModuleScaffold.write(ModuleSkeletonSpec(id = "shell_only"), zip)

        val r = defaultRegistry().call(
            "module.build",
            FakeContext(),
            args("zip" to zip.absolutePath),
        )

        assertEquals("BUILD_FAILED", r.error?.code)
        assertTrue(r.error?.message?.contains("不需要编译") == true, "要说清这一档不用编：${r.error?.message}")
        assertFalse(File(dir, "shell_only-arm64-v8a.zip").exists(), "失败就不该产出 zip")
    }

    @Test
    fun `module_build 要过确认门`() {
        // 它会起编译器、写新包 —— 属于会改东西的操作，默认策略下要确认
        NativeToolchains.register(NativeToolchains.locateIn(fakeToolchain()))
        val dir = tmpDir()
        val ctx = FakeContext()
        val registry = defaultRegistry()

        registry.call("module.build", ctx, args("zip" to zygiskSkeleton(dir).absolutePath))

        assertEquals(1, ctx.confirms.size, "编译会写出新包，该走一次确认")
        assertEquals(Effect.WRITE, ctx.confirms.first().effect)
    }
}
