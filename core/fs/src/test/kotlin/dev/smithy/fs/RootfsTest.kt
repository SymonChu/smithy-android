package dev.smithy.fs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * rootfs + chroot 这条路。
 *
 * 分两层：
 * 1. **命令拼法**（不跑东西）：PATH、引号转义、挂载点、chroot 内路径 —— 这些错了在设备上
 *    只会表现为「一句看不懂的报错」，所以在这里钉死。
 * 2. **真跑一遍**（`SMITHY_REAL_CHROOT=1` 门控）：真解一份 Alpine、真 `apk add clang`、
 *    真用它编出 arm64 的 so。没有这个证据，「rootfs 这条路能编」就只是设计文档里的一句话。
 *    本地跑用 `unshare -Urm` 造一个「假 root」（见 [UnshareShellChannel]）；
 *    设备上是 libsu 给的真 root，命令一字不差。
 */
class RootfsTest {

    private class RecordingShell(private val result: ShellResult = ShellResult(0, "ok")) : ShellChannel {
        override val name = "record"
        val commands = mutableListOf<String>()
        override fun available(): Boolean = true
        override fun exec(command: String, timeoutSeconds: Long): ShellResult {
            commands += command
            return result
        }
    }

    private class NoRootShell : ShellChannel {
        override val name = "no-root"
        override fun available(): Boolean = false
        override fun exec(command: String, timeoutSeconds: Long) = ShellResult(-1, "root 没授权")
    }

    private fun rootFs(dir: String = "/data/local/tmp/smithy/rootfs", shell: ShellChannel = RecordingShell()) =
        RootFs(File(dir), shell)

    // ── 命令拼法 ────────────────────────────────────────────

    @Test
    fun `chroot 命令显式给了 PATH —— 否则 apk 就是一句 not found`() {
        val cmd = rootFs().chrootCommand("apk add clang")
        assertTrue(cmd.contains("chroot '/data/local/tmp/smithy/rootfs'"), "要 chroot 进 rootfs：$cmd")
        assertTrue(cmd.contains("/bin/sh -c"), "要在里面跑 sh：$cmd")
        assertTrue(cmd.contains("export PATH=/sbin:/usr/sbin:/bin:/usr/bin;"), "PATH 必须显式给：$cmd")
    }

    @Test
    fun `脚本里的单引号被转义 原样传给 sh`() {
        // 光看字符串容易看走眼：这里真让 sh 跑一遍那段被包住的脚本，看输出对不对
        val inner = "echo 'a b'; n=1; echo \$n"
        val cmd = rootFs().chrootCommand(inner)
        // 单引号封闭的字符串里出现单引号，必须按 '\'' 转义，否则后面的内容会被当成新命令
        assertTrue(cmd.contains("'\\''"), "单引号要转义：$cmd")

        // 把 chroot 那层换成直接跑：验证被包住的那段脚本内容一字不差地到了 sh
        val ran = ProcessShellChannel().exec("/bin/sh -c ${quote(inner)}")
        assertEquals(0, ran.code, "脚本该跑得动：${ran.out}")
        assertEquals("a b\n1", ran.out.trim(), "脚本内容该原样到达 sh：${ran.out}")
    }

    @Test
    fun `prepare 会挂 proc dev sys 并写 resolv_conf`() {
        val shell = RecordingShell()
        rootFs(shell = shell).prepare()
        val cmd = shell.commands.single()
        assertTrue(cmd.contains("mountpoint -q /proc || mount -t proc proc /proc"), "要挂 /proc：$cmd")
        assertTrue(cmd.contains("mountpoint -q /dev"), "要挂 /dev：$cmd")
        assertTrue(cmd.contains("/etc/resolv.conf"), "要写 DNS，不然 apk 连不上：$cmd")
        assertTrue(cmd.contains("nameserver"), "resolv.conf 要有 nameserver：$cmd")
    }

    @Test
    fun `resolv_conf 优先抄设备的 DNS —— 有些网络只认本地 DNS`() {
        val fs = rootFs()
        val content = fs.resolvConf()
        assertTrue(content.contains("nameserver"), content)
        val device = runCatching {
            File("/etc/resolv.conf").readLines().map { it.trim() }.filter { it.startsWith("nameserver") }
        }.getOrDefault(emptyList())
        if (device.isNotEmpty()) {
            // 设备/本机有 DNS 配置时必须抄它：写死公共 DNS 在只认本地 DNS 的网络里会解析失败
            assertEquals(device.joinToString("\n") + "\n", content)
        }
    }

    @Test
    fun `deploy 已经部署过就不覆盖 —— 里面可能有装好的 clang`() {
        val shell = RecordingShell()
        rootFs(shell = shell).deploy(File("/tmp/src"))
        val cmd = shell.commands.single()
        assertTrue(cmd.contains("test -x '/data/local/tmp/smithy/rootfs/bin/sh'"), "要先判断部署过没：$cmd")
        assertTrue(cmd.contains("exit 0"), "部署过就直接退出：$cmd")
        assertTrue(cmd.contains("cp -a '/tmp/src'/. "), "没部署过才拷：$cmd")
    }

    @Test
    fun `没有 root 通道时 hasClang 为假 且 build 说清原因`() {
        val fs = rootFs(shell = NoRootShell())
        assertFalse(fs.hasClang(), "root 都没授权，不可能有 clang")
        val result = ChrootClangToolchain(fs, NoRootShell(), sysroot = null).build(
            NativeBuildSpec(
                abi = NativeAbi.ARM64_V8A,
                apiLevel = 26,
                sourceDir = File("/tmp"),
                sources = listOf("module.cpp"),
                outFile = File("/tmp/out.so"),
            ),
        )
        assertFalse(result.ok)
        assertTrue(result.log.contains("不可用"), "要说清是工具链不可用：${result.log}")
    }

    @Test
    fun `chroot 里的命令行用的是 chroot 内路径`() {
        val chain = ChrootClangToolchain(
            rootFs(),
            RecordingShell(),
            sysroot = File("/app/addon/native-toolchain/sysroot"),
        )
        val spec = NativeBuildSpec(
            abi = NativeAbi.ARM64_V8A,
            apiLevel = 26,
            sourceDir = File("/app/src"),
            sources = listOf("module.cpp"),
            outFile = File("/app/out.so"),
            flags = listOf("-static-libstdc++"),
        )
        val argv = chain.commandLine(spec, listOf("/smithy-build/src/module.cpp"))
        // sysroot / 输出 / 源文件都必须是 chroot 内的路径 —— 拿宿主路径进去，clang 在 rootfs 里看不见
        assertTrue(argv.contains("--sysroot=/smithy-build/sysroot"), argv.toString())
        assertTrue(argv.contains("-o") && argv.contains("/smithy-build/out/arm64-v8a.so"), argv.toString())
        assertTrue(argv.contains("/smithy-build/src/module.cpp"), argv.toString())
        assertTrue(argv.contains("--target=aarch64-linux-android26"), argv.toString())
        assertFalse(argv.any { it.startsWith("/app/") }, "不该出现宿主路径：$argv")
    }

    // ── 真跑一遍（门控）──────────────────────────────────────

    /**
     * 把命令包进一个用户命名空间里当「假 root」跑 —— 本地验证用，设备上换成 libsu 的真 root。
     *
     * 每条命令都是**独立**的 mount namespace（设备上不是：mount 会留着），所以这里在每条
     * 命令前把 `/proc`、`/dev` 重新挂一遍 —— 模拟设备上「挂一次一直有效」的状态。
     * 这也正说明 `RootFs.prepare()` 必须是幂等的。
     */
    private class UnshareShellChannel(private val rootfsDir: File) : ShellChannel {
        override val name = "unshare(userns=root)"
        override fun available(): Boolean = true
        override fun exec(command: String, timeoutSeconds: Long): ShellResult {
            val setup = buildString {
                append("mkdir -p ${quote(rootfsDir.absolutePath)}/proc ${quote(rootfsDir.absolutePath)}/dev\n")
                append("mountpoint -q ${quote(rootfsDir.absolutePath)}/proc || mount -t proc proc ${quote(rootfsDir.absolutePath)}/proc\n")
                append("mountpoint -q ${quote(rootfsDir.absolutePath)}/dev || mount -o bind /dev ${quote(rootfsDir.absolutePath)}/dev\n")
            }
            return ProcessShellChannel().exec(
                "unshare -Urm /bin/sh -c ${quote(setup + command)}",
                timeoutSeconds,
            )
        }
    }

    /**
     * 真在 Alpine 里装 clang，再用它编出 arm64 的 so。
     *
     * 需要三样东西（都给了才跑，否则跳过）：
     * - `SMITHY_REAL_CHROOT=1`
     * - `SMITHY_ALPINE_TAR`：一份 Alpine minirootfs 的 tar.gz（**本机架构**的，因为这里是在
     *   本机 chroot 里验证流程；设备上是 aarch64 那份）
     * - `SMITHY_NDK`：NDK 目录（拿它的 sysroot 当 target sysroot）
     */
    @Test
    fun `真在 Alpine rootfs 里装上 clang 并编出 arm64 的 so`() {
        Assume.assumeTrue("没设 SMITHY_REAL_CHROOT，跳过真 chroot", System.getenv("SMITHY_REAL_CHROOT") == "1")
        val tar = File(System.getenv("SMITHY_ALPINE_TAR") ?: "/tmp/alpine-x86.tar.gz")
        val ndk = File(System.getenv("SMITHY_NDK") ?: "/tmp/ndk/android-ndk-r26d")
        Assume.assumeTrue("找不到 Alpine 包：$tar", tar.isFile)
        val sysroot = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/sysroot")
        Assume.assumeTrue("找不到 NDK sysroot：$sysroot", sysroot.isDirectory)

        // rootfs 解到一个固定位置：apk 装一次就够，重复跑别再下一遍 200MB
        val rootfsDir = File("/tmp/smithy-rootfs-verify")
        if (!File(rootfsDir, "bin/sh").isFile) {
            rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()
            // 用 AddOn 那份解包器：它会把 Alpine 的软链接和可执行位还原（extractAll 不会），
            // 顺带也就把「解包器能不能吃下一份真 Alpine」验了
            ArchiveExtract.extract(tar, AddOnArchive.TAR_GZ, rootfsDir, strip = 0, onlyPaths = emptyList()) { _, _ -> }
        }
        assertTrue(File(rootfsDir, "bin/sh").isFile, "rootfs 该解出来了")

        val shell = UnshareShellChannel(rootfsDir)
        val rootFs = RootFs(rootfsDir, shell)

        val prep = rootFs.prepare()
        assertTrue(prep.ok, "准备 chroot 环境失败：${prep.out}")

        if (!rootFs.hasClang()) {
            val apk = rootFs.installClang(timeoutSeconds = 900)
            assertTrue(apk.ok, "apk add clang 失败：\n${apk.out}")
        }
        assertTrue(rootFs.hasClang(), "rootfs 里该有 clang 了")

        // 用 rootfs 里的 clang 编 zygisk 骨架（target 还是 arm64 —— 交叉编，和设备上一样）
        val work = File(System.getProperty("java.io.tmpdir"), "smithy-rootfs-build-${System.nanoTime()}").apply { mkdirs() }
        val zip = File(work, "m.zip")
        ModuleScaffold.write(
            ModuleSkeletonSpec(id = "chroot_mod", flavour = ModuleSkeletonSpec.Flavour.ZYGISK),
            zip,
        )
        // 源码解到工作目录（ChrootClangToolchain 会把它拷进 rootfs）
        val src = File(work, "src").apply { mkdirs() }
        ModuleProject.open(zip).use { p ->
            p.entryNames().filter { it.startsWith("jni/") }.forEach { name ->
                val f = File(src, name.removePrefix("jni/"))
                f.parentFile?.mkdirs()
                f.writeBytes(p.readBytes(name)!!)
            }
        }
        val out = File(work, "out/arm64-v8a.so")
        val chain = ChrootClangToolchain(rootFs, shell, sysroot)
        val result = chain.build(
            NativeBuildSpec(
                abi = NativeAbi.ARM64_V8A,
                apiLevel = 26,
                sourceDir = src,
                sources = listOf("module.cpp"),
                outFile = out,
                flags = ModuleNativeBuild.TEMPLATE_FLAGS,
            ),
        )

        assertTrue(result.ok, "在 rootfs 里编译失败：\n${result.log}")
        val so = out.readBytes()
        assertTrue(so.size > 16, "产物太小：${so.size}")
        assertEquals(0x7f, so[0].toInt(), "不是 ELF")
        assertEquals(0xB7, so[18].toInt() and 0xFF, "e_machine 不是 AArch64 —— 交叉编没生效")
        val dyn = String(so, Charsets.ISO_8859_1)
        assertFalse(dyn.contains("libc++_shared.so"), "产物不该依赖 libc++_shared.so")
        assertTrue(dyn.contains("zygisk_module_entry"), "入口符号该在")
    }
}
