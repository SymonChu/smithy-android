package dev.smithy.toolkit

import dev.smithy.fs.AddOnHost
import dev.smithy.fs.AddOnKind
import dev.smithy.fs.AddOnManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 可选组件的两个工具。
 *
 * 盯的是「模型会不会在同一个地方反复撞墙」：没有组件位置时说清是 NO_HOST（不是笼统失败）、
 * 装本地包时**真的按 kind 落到了约定目录**、装完之后 `component.list` 能看到它。
 */
class ComponentToolsTest {

    @After
    fun tearDown() {
        AddOnHost.install(null)
    }

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-component-${System.nanoTime()}").apply { mkdirs() }

    private fun zip(vararg entries: Pair<String, String>): File {
        val f = File(tmpDir(), "bundle-${System.nanoTime()}.zip")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            entries.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        f.writeBytes(bytes.toByteArray())
        return f
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    @Test
    fun `没登记安装位置时说清是 NO_HOST 并给出下一步`() {
        AddOnHost.install(null)

        val r = defaultRegistry().call("component.list", FakeContext(), JsonObject(emptyMap()))

        assertEquals("NO_HOST", r.error?.code)
        assertTrue(r.error?.hint?.contains("SmithyApp") == true, "要指出该在哪儿登记：${r.error?.hint}")
    }

    @Test
    fun `component_list 报出装没装 有多大 许可是什么`() {
        AddOnHost.install(AddOnManager(tmpDir()))

        val r = defaultRegistry().call("component.list", FakeContext(), JsonObject(emptyMap()))

        assertTrue(r.ok, r.error?.message ?: "")
        val text = r.text.orEmpty()
        assertTrue(text.contains("rootfs-alpine"), "要列出可选组件：$text")
        assertTrue(text.contains("未装"), "现在没装就该说未装：$text")
        assertTrue(text.contains("许可"), "许可要一起给出来（下之前用户有权知道）：$text")
        assertTrue(text.contains("native 编译工具链：不可用"), "工具链状态要一起报：$text")
    }

    @Test
    fun `装本地归档时会按 kind 落到约定目录 并且装完就查得到`() {
        val root = tmpDir()
        AddOnHost.install(AddOnManager(root))
        // 一个典型的工具链包：bin/clang++ + sysroot/
        val bundle = zip(
            "pkg/bin/clang++" to "#!/bin/sh\nexit 0\n",
            "pkg/sysroot/usr/include/jni.h" to "jni",
        )

        val r = defaultRegistry().call(
            "component.install",
            FakeContext(),
            args("file" to bundle.absolutePath, "kind" to "toolchain", "strip" to "1"),
        )

        assertTrue(r.ok, "该装成功：${r.error?.message} / ${r.error?.hint}")
        // strip=1 去掉包外那层 pkg/，落到 native-toolchain/ 下（NativeToolchains.locateIn 认的布局）
        val dir = File(root, AddOnKind.TOOLCHAIN.dirName)
        assertTrue(File(dir, "bin/clang++").isFile, "布局要正好是 locateIn 认的那种")
        assertTrue(File(dir, "sysroot/usr/include/jni.h").isFile)
        assertTrue(File(dir, "bin/clang++").canExecute(), "bin/ 下要给可执行位（不然 clang 起不来）")

        val list = defaultRegistry().call("component.list", FakeContext(), JsonObject(emptyMap()))
        assertTrue(list.text.orEmpty().contains("local-"), "装过的本地包也要出现在清单里")
    }

    @Test
    fun `文件不存在时点出路径问题 而不是笼统失败`() {
        AddOnHost.install(AddOnManager(tmpDir()))

        val r = defaultRegistry().call(
            "component.install",
            FakeContext(),
            args("file" to "/nonexistent/toolchain.zip"),
        )

        assertEquals("NO_FILE", r.error?.code)
        assertTrue(r.error?.hint?.contains("Download") == true, "要提醒手机上文件通常在哪儿：${r.error?.hint}")
    }

    @Test
    fun `两个参数都不给时说清该怎么用`() {
        AddOnHost.install(AddOnManager(tmpDir()))

        val r = defaultRegistry().call("component.install", FakeContext(), JsonObject(emptyMap()))

        assertEquals("BAD_ARGS", r.error?.code)
        assertTrue(r.error?.hint?.contains("component.list") == true, "要先让它去看清单：${r.error?.hint}")
    }

    @Test
    fun `装不存在的组件时列出可选值`() {
        AddOnHost.install(AddOnManager(tmpDir()))

        val r = defaultRegistry().call("component.install", FakeContext(), args("id" to "不存在的"))

        assertEquals("UNKNOWN_COMPONENT", r.error?.code)
        assertTrue(r.error?.hint?.contains("rootfs-alpine") == true)
    }
}
