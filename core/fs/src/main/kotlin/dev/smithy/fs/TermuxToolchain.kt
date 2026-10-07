package dev.smithy.fs

import java.io.File

/**
 * 直接用手机上**已经装好的 Termux** 里的 clang —— 零下载。
 *
 * 为什么值单独一条：Zygisk 用户里装了 Termux 的人不少，而工具链包要 154MB。
 *
 * 但 Termux 的目录是它自己的私有目录（`/data/data/com.termux/files/usr`），
 * 别的 App 读不到，所以**探测和编译都要走 root 通道**（[ShellChannel]）：
 * 应用自己既看不见那个路径，也 exec 不了它。
 *
 * ⚠️ **本路线尚未在真机验证**（和 chroot 那条一样，属于「要设备」的活）：
 * 命令行拼法在 `ToolchainLayoutTest` 里钉住了，但「Termux 的 clang 在 root 下、
 * 以应用目录为工作目录，真的能编出可被 Zygisk 加载的 so」还没试过。
 * [describe] 里如实写出来 —— 别让人以为验过了。
 */
class TermuxClangToolchain(
    private val prefix: File,
    private val shell: ShellChannel,
) : NativeToolchain {

    /** 命令拼法复用同一套：Termux 是**平铺布局**，[ClangToolchain] 自己会认 `include/` 与 `lib/`。 */
    private val inner = ClangToolchain(File(prefix, "bin/clang++"), prefix)

    override val name: String get() = "clang（Termux）"

    override fun available(): Boolean =
        shell.available() && shell.exec("test -x ${quote(inner.clangBinary.absolutePath)}", 20L).ok

    override fun describe(): String =
        "$name：${inner.clangBinary.absolutePath}（用手机上已装的 Termux，走 root 运行；" +
            "本路线未在真机验证）"

    /** Termux 的 libc++ 只有共享库 → 得走 `-nostdlib++`（同 [ClangToolchain.supportsStaticLibcxx] 的道理）。 */
    override fun supportsStaticLibcxx(): Boolean = false

    override fun build(spec: NativeBuildSpec): NativeBuildResult {
        val argv = inner.commandLine(spec)
        // 源码与产物都在应用目录里（root 读得到、也写得进），编译器本体在 Termux 里。
        // 产物由 root 创建，但默认 0644 → 应用读得回来。
        val cmd = "cd ${quote(spec.sourceDir.absolutePath)} && " +
            "LD_LIBRARY_PATH=${quote("${prefix.absolutePath}/lib")} " +
            argv.joinToString(" ") { quote(it) }
        val r = shell.exec(cmd, 600L)
        val so = spec.outFile.takeIf { it.isFile && it.length() > 0L }
        val log = r.out.ifBlank { "（编译器没有输出）" } +
            if (so == null && r.ok) "\n编译报告成功，但没有产物文件：${spec.outFile}" else ""
        return NativeBuildResult(ok = r.ok && so != null, log = log, soFile = so, command = argv)
    }
}
