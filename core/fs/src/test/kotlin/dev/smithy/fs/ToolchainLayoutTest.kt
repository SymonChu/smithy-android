package dev.smithy.fs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 工具链布局与链接选项。
 *
 * 这两种布局都得认，因为它们代表两条真实的路：
 * - **NDK 形态**：`sysroot/usr/lib/<triple>/libc++_static.a` 在 → 可以 `-static-libstdc++`
 *   （宿主是电脑，交叉编）；对应「导入 NDK 解出来的那一份」。
 * - **Termux 形态（平铺）**：`include/` 与 `lib/` 直接在包根，libc++ 只有共享库 →
 *   必须退回 `-nostdlib++`；对应「导入手机上自己跑的工具链包」，也是免 root 那条路。
 *
 * 这些细节错了在设备上的表现是「一串看不懂的链接错误」，所以在这里钉住。
 */
class ToolchainLayoutTest {

    private fun dir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    /** 假 clang++（只要求存在且可执行 —— 这里测的是命令拼法，不是真编译）。 */
    private fun fakeClang(root: File): File {
        val bin = File(root, "bin").apply { mkdirs() }
        val f = File(bin, "clang++")
        f.writeText("#!/bin/sh\nexit 0\n")
        f.setExecutable(true)
        return f
    }

    /** NDK 形态：`sysroot/usr/lib/<triple>/libc++_static.a`。 */
    private fun ndkLike(): File {
        val root = dir("smithy-ndk")
        fakeClang(root)
        File(root, "sysroot/usr/include").mkdirs()
        File(root, "sysroot/usr/lib/aarch64-linux-android/26").mkdirs()
        File(root, "sysroot/usr/lib/aarch64-linux-android/libc++_static.a").writeText("假静态库")
        return root
    }

    /** Termux 形态：平铺的 include/ 与 lib/，libc++ 只有共享库。 */
    private fun termuxLike(): File {
        val root = dir("smithy-termux")
        fakeClang(root)
        File(root, "include/android").mkdirs()
        File(root, "include/android/log.h").writeText("// 假头")
        File(root, "lib").mkdirs()
        File(root, "lib/libc++_shared.so").writeText("假 .so")
        File(root, "lib/liblog.so").writeText("假桩库")
        return root
    }

    private fun spec(out: File) = NativeBuildSpec(
        abi = NativeAbi.ARM64_V8A,
        apiLevel = 26,
        sourceDir = out.parentFile,
        sources = listOf("module.cpp"),
        outFile = out,
        flags = ModuleNativeBuild.TEMPLATE_FLAGS,
    )

    @Test
    fun `NDK 形态：静态 libc++ 在 所以保留 -static-libstdc++`() {
        val root = ndkLike()
        val chain = assertNotNull(NativeToolchains.locateIn(root))
        assertTrue(chain.supportsStaticLibcxx(), "sysroot 里有 libc++_static.a，就该认为能静态链")

        val argv = (chain as ClangToolchain).commandLine(spec(File(root, "out.so")))

        assertTrue(argv.contains("-static-libstdc++"), argv.toString())
        assertFalse(argv.contains("-nostdlib++"), "不该退回 -nostdlib++：$argv")
        assertTrue(argv.contains("--sysroot=${root.absolutePath}/sysroot"), argv.toString())
    }

    @Test
    fun `Termux 平铺形态：认 include 与 lib 退回 -nostdlib++`() {
        val root = termuxLike()
        val chain = assertNotNull(NativeToolchains.locateIn(root), "平铺布局也该能认出来")
        assertFalse(chain.supportsStaticLibcxx(), "只有 libc++_shared.so，静态链不了")

        val argv = (chain as ClangToolchain).commandLine(spec(File(root, "out.so")))

        // -static-libstdc++ 换成 -nostdlib++：模板只用 C 头，产物还小了 50 倍
        assertTrue(argv.contains("-nostdlib++"), "该退回 -nostdlib++：$argv")
        assertFalse(argv.contains("-static-libstdc++"), argv.toString())
        // 平铺布局的头与库：必须在
        assertTrue(argv.contains("-isystem") && argv.contains(File(root, "include").absolutePath), argv.toString())
        assertTrue(argv.contains("-L") && argv.contains(File(root, "lib").absolutePath), argv.toString())
        // bionic 工具链靠 LD_LIBRARY_PATH 找包内 libLLVM
        assertTrue(chain.describe().contains("LD_LIBRARY_PATH"), chain.describe())
    }

    @Test
    fun `本机没有 system_lib64 就不往命令行里塞`() {
        val root = termuxLike()
        val chain = assertNotNull(NativeToolchains.locateIn(root)) as ClangToolchain
        val argv = chain.commandLine(spec(File(root, "out.so")))
        // 设备上 /system/lib64 存在（liblog 的桩库在那儿）；本机没有 → 不能瞎加
        if (!File("/system/lib64").isDirectory) {
            assertFalse(argv.any { it.contains("/system/lib") }, "不存在的平台目录不该进命令行：$argv")
        }
    }

    @Test
    fun `包内 lib 与 bin 并排时补 LD_LIBRARY_PATH 与 PATH`() {
        val root = termuxLike()
        val env = NativeToolchains.envFor(root)
        assertEquals("${root.absolutePath}/lib", env["LD_LIBRARY_PATH"]!!.split(':').first())
        assertEquals("${root.absolutePath}/bin", env["PATH"]!!.split(':').first())
        // 追加而不是覆盖：设备上原有的路径不能被我们挤掉
        assertTrue(env["PATH"]!!.split(':').size >= 1)
    }
}
