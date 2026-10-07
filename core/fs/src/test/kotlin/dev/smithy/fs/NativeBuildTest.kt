package dev.smithy.fs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * native 编译链：`jni/` 源码 → `zygisk/<abi>.so` → 模块 zip。
 *
 * 这组用例拿一个**假的 clang**（一个 shell 脚本，只负责把 `-o` 指的文件写出来）当编译器，
 * 所以能在这台机器上把整条链跑完：命令行拼得对不对、产物落在哪个条目名上、
 * 失败时有没有给出下一步。
 *
 * 「真 clang 能不能编过」是另一回事（头文件、模板、flag 都要对），那条由最后一组用例
 * 覆盖：设了 `SMITHY_NDK` 指向一份 NDK 时才会跑，没设就跳过 —— 它不该成为每次构建的负担。
 */
class NativeBuildTest {

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-native-${System.nanoTime()}").apply { mkdirs() }

    /** 假 clang：把 `-o` 后面的路径当产物写出来，并且把收到的参数打一份，便于断言。 */
    private val fakeClangSource = """
        #!/bin/sh
        out=""
        args=""
        while [ ${'$'}# -gt 0 ]; do
          args="${'$'}args ${'$'}1"
          if [ "${'$'}1" = "-o" ]; then out="${'$'}2"; shift 2; continue; fi
          shift
        done
        printf '\177ELF\002\001\001\000' > "${'$'}out"
        echo "fake-clang${'$'}args" > "${'$'}out.args.txt"
        echo "fake clang: 写出了 ${'$'}out"
    """.trimIndent()

    /** 造一个「我们自己的 bundle」布局的工具链目录（bin/clang++ + sysroot/…）。 */
    private fun toolchainRoot(withSysroot: Boolean = true): File {
        val root = tmpDir()
        val bin = File(root, "bin").apply { mkdirs() }
        File(bin, "clang++").apply {
            writeText(fakeClangSource)
            setExecutable(true)
        }
        if (withSysroot) {
            File(root, "sysroot/usr/include").mkdirs()
            File(root, "sysroot/usr/lib/arm64-v8a-placeholder").mkdirs()
            File(root, "sysroot/usr/lib/aarch64-linux-android/26").mkdirs()
        }
        return root
    }

    private fun spec(dir: File, abi: NativeAbi = NativeAbi.ARM64_V8A, out: File = File(dir, "out.so")) =
        NativeBuildSpec(
            abi = abi,
            apiLevel = 26,
            sourceDir = dir,
            sources = listOf("module.cpp"),
            outFile = out,
        )

    // ── 命令行 ──────────────────────────────────────────────

    @Test
    fun `命令行带上了 target sysroot 与产物名`() {
        val root = toolchainRoot()
        val toolchain = assertNotNull(NativeToolchains.locateIn(root), "应该认出我们自己的 bundle 布局")

        val work = tmpDir()
        File(work, "module.cpp").writeText("int x() { return 1; }")
        val argv = (toolchain as ClangToolchain).commandLine(spec(work))

        assertTrue(argv.contains("--target=aarch64-linux-android26"), "缺 target：$argv")
        assertTrue(argv.any { it.startsWith("--sysroot=") }, "缺 sysroot：$argv")
        assertTrue(argv.contains("${root.path}/sysroot/usr/include"), "缺头文件路径：$argv")
        // 平台库目录按 API 分目录（NDK 就是这样），两层都要给
        assertTrue(argv.contains("${root.path}/sysroot/usr/lib/aarch64-linux-android/26"), "缺 API 库目录：$argv")
        assertTrue(argv.contains("${root.path}/sysroot/usr/lib/aarch64-linux-android"), "缺 ABI 库目录：$argv")
        assertTrue(argv.contains("-shared") && argv.contains("-fPIC"), "不会产出共享库：$argv")
        assertEquals("${work.path}/out.so", argv[argv.indexOf("-o") + 1], "产物路径不对：$argv")
        assertTrue(argv.last().endsWith("module.cpp"), "源文件要放最后：$argv")
    }

    @Test
    fun `不存在的目录不会被写进命令行`() {
        // 硬写一串不存在的 -isystem 会让 clang 直接报错退出 —— 按「存在与否」加
        val root = toolchainRoot()
        val toolchain = NativeToolchains.locateIn(root) as ClangToolchain
        val work = tmpDir()
        File(work, "module.cpp").writeText("int x();")
        val argv = toolchain.commandLine(spec(work))

        assertFalse(argv.any { it.contains("c++/v1") }, "sysroot 里没有 libc++ 头，就不该出现：$argv")
        assertFalse(argv.any { it.contains("aarch64-linux-android/27") }, "别的 API 目录不该出现：$argv")
    }

    @Test
    fun `工具链没装好时 available 为假 且 require 说清缺什么`() {
        val root = tmpDir() // 空目录
        assertNull(NativeToolchains.locateIn(root), "空目录里不该找出工具链")

        NativeToolchains.register(null)
        val e = assertFailsWith<IllegalStateException> { NativeToolchains.require() }
        assertTrue(e.message!!.contains("clang"), "要说清缺 clang：${e.message}")
        assertTrue(e.message!!.contains("sysroot"), "要说清缺 sysroot：${e.message}")

        // 文件在但没有执行位 → available() 必须是假（否则会「有实现、跑不了」）
        val bin = File(root, "bin").apply { mkdirs() }
        val fake = File(bin, "clang++").apply { writeText("#!/bin/sh\n"); setExecutable(false) }
        assertFalse(ClangToolchain(fake).available())
    }

    // ── 整条链：源码 → so → 模块 zip ────────────────────────

    @Test
    fun `zygisk 骨架能被编出 so 并写进 zip`() {
        val root = toolchainRoot()
        val toolchain = assertNotNull(NativeToolchains.locateIn(root))
        val work = tmpDir()
        val zip = File(work, "zygisk_demo.zip")
        ModuleScaffold.write(
            ModuleSkeletonSpec(id = "zygisk_demo", name = "演示", flavour = ModuleSkeletonSpec.Flavour.ZYGISK),
            zip,
        )

        val out = File(work, "zygisk_demo-arm64-v8a.zip")
        val result = ModuleNativeBuild.build(zip, NativeAbi.ARM64_V8A, out, toolchain, apiLevel = 26)

        assertTrue(result.ok, "该编过：${result.hint}\n${result.log}")
        assertEquals("zygisk/arm64-v8a.so", result.soEntry)
        // 原 zip 不动，产物另存
        assertTrue(zip.exists())

        ModuleProject.open(out).use { p ->
            val names = p.entryNames()
            assertTrue("zygisk/arm64-v8a.so" in names, "产物没写进去：$names")
            val layout = p.layout
            assertTrue(layout.isZygisk, "写进去之后该被认成 Zygisk 模块：$names")
            assertEquals(listOf("arm64-v8a"), layout.knownAbis)
            // .so 之外的条目一个都不能丢
            assertTrue("module.prop" in names && "service.sh" in names)
        }
    }

    @Test
    fun `纯脚本模块没有 jni 源码时 说的是「不需要编译」`() {
        val toolchain = assertNotNull(NativeToolchains.locateIn(toolchainRoot()))
        val work = tmpDir()
        val zip = File(work, "shell_mod.zip")
        ModuleScaffold.write(ModuleSkeletonSpec(id = "shell_mod"), zip) // SHELL 档

        val result = ModuleNativeBuild.build(zip, NativeAbi.ARM64_V8A, File(work, "out.zip"), toolchain)

        assertFalse(result.ok)
        assertTrue(result.hint!!.contains("不需要编译"), "要说清这一档不用编：${result.hint}")
    }

    @Test
    fun `用到 zygisk_hpp 但包里没有时 直接点名那个文件`() {
        val toolchain = assertNotNull(NativeToolchains.locateIn(toolchainRoot()))
        val work = tmpDir()
        val zip = File(work, "no_header.zip")
        // 造一个缺头文件的模块：有源码、有 module.prop，但没有 zygisk.hpp
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
            put(ModuleProject.ModulePropFile, "id=no_header\nname=no_header\nversion=v1\nversionCode=1\n")
            put("jni/module.cpp", "#include \"zygisk.hpp\"\nint x();\n")
        }

        val result = ModuleNativeBuild.build(zip, NativeAbi.ARM64_V8A, File(work, "out.zip"), toolchain)

        assertFalse(result.ok)
        assertTrue(result.hint!!.contains("zygisk.hpp"), "要点名缺的那个文件：${result.hint}")
        assertTrue(result.hint!!.contains("0BSD"), "要顺带说清那是什么东西：${result.hint}")
    }

    @Test
    fun `编译失败时把 clang 的输出原样带回来`() {
        val root = tmpDir()
        val bin = File(root, "bin").apply { mkdirs() }
        File(bin, "clang++").apply {
            writeText("#!/bin/sh\necho 'module.cpp:1:1: error: expected expression' >&2\nexit 1\n")
            setExecutable(true)
        }
        val toolchain = assertNotNull(NativeToolchains.locateIn(root))
        val work = tmpDir()
        val zip = File(work, "zygisk_x.zip")
        ModuleScaffold.write(
            ModuleSkeletonSpec(id = "zygisk_x", flavour = ModuleSkeletonSpec.Flavour.ZYGISK),
            zip,
        )

        val result = ModuleNativeBuild.build(zip, NativeAbi.ARM64_V8A, File(work, "out.zip"), toolchain)

        assertFalse(result.ok)
        assertTrue(result.log.contains("expected expression"), "日志要原样带回来：${result.log}")
        assertTrue(result.hint!!.contains("error"), "要指向日志里的 error：${result.hint}")
        assertTrue(result.command.isNotEmpty(), "要能看出用的是哪条命令行")
        assertFalse(File(work, "out.zip").exists(), "失败就不该产出 zip")
    }

    @Test
    fun `ABI 名与模块布局认识的名字不许分叉`() {
        // 产物名就是 ABI 名，Magisk 按文件名认 —— 两边分叉的后果是「装了但不加载」
        assertEquals(ModuleLayouts.KNOWN_ABIS, NativeAbi.entries.map { it.abiName })
    }

    // ── 真编译器（需要 NDK；没给就跳过）───────────────────────

    /**
     * 用真的 clang 把 zygisk 骨架编一次。
     *
     * 假 clang 能证明「参数拼对了、产物放对位置了」，但证明不了**这套参数真能让 clang
     * 编出 arm64 的 so** —— 而后者恰恰是设备上唯一重要的事。所以留一条用真工具链跑的用例：
     * `SMITHY_NDK=/path/to/android-ndk ./gradlew :core:fs:testDebugUnitTest`。
     */
    @Test
    fun `给一份真 NDK 时骨架能编出 arm64 的 so`() {
        val ndkPath = System.getenv("SMITHY_NDK")
        org.junit.Assume.assumeTrue("没设 SMITHY_NDK，跳过真编译", !ndkPath.isNullOrBlank())
        val ndk = File(ndkPath!!)
        val toolchain = assertNotNull(
            NativeToolchains.locateIn(ndk),
            "在 $ndk 里没找到 clang（期望 toolchains/llvm/prebuilt/<host>/bin/clang++）",
        )

        val work = tmpDir()
        val zip = File(work, "real.zip")
        ModuleScaffold.write(
            ModuleSkeletonSpec(id = "real_mod", name = "真编译", flavour = ModuleSkeletonSpec.Flavour.ZYGISK),
            zip,
        )

        val out = File(work, "real-built.zip")
        val result = ModuleNativeBuild.build(zip, NativeAbi.ARM64_V8A, out, toolchain, apiLevel = 26)
        assertTrue(result.ok, "真编译失败：${result.hint}\n${result.log}")

        val so = ModuleProject.open(out).use { it.readBytes("zygisk/arm64-v8a.so") }
        assertNotNull(so)
        // ELF 魔数 + e_machine(0xB7 = AArch64)：编出来的必须真是 arm64 的动态库，
        // 而不是「某台机器的 x86 产物被塞进了 zygisk/」
        assertTrue(so.size > 16, "产物太小，不像一个 so：${so.size} 字节")
        assertEquals(0x7f, so[0].toInt(), "不是 ELF：${so.take(4)}")
        assertEquals('E'.code, so[1].toInt())
        assertEquals('L'.code, so[2].toInt())
        assertEquals('F'.code, so[3].toInt())
        assertEquals(0xB7, so[18].toInt() and 0xFF, "e_machine 不是 AArch64")

        // 依赖表里**不能**有 libc++_shared.so：Zygisk 把模块 dlopen 进目标应用的进程，
        // 那个进程里没有这个文件 —— 有它就等于「装上了但不加载，而且不报错」。
        // （clang 给共享库默认动态链接 libc++，所以这条要靠 -static-libstdc++ 兜住。）
        val dynstr = String(so, Charsets.ISO_8859_1)
        assertFalse(dynstr.contains("libc++_shared.so"), "产物动态依赖 libc++_shared.so：目标进程里没有它")
        // 用了 __android_log_print，就得显式依赖 liblog（不能靠加载顺序碰运气）
        assertTrue(dynstr.contains("liblog.so"), "少了 liblog 依赖")
        // 入口符号要导出，否则 Zygisk 找不到它，模块根本不会被调用
        assertTrue(dynstr.contains("zygisk_module_entry"), "少了 Zygisk 入口符号")
    }
}
