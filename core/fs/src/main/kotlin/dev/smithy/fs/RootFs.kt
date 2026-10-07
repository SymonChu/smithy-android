package dev.smithy.fs

import java.io.File

/**
 * 在 rootfs 里干活（目前是 Alpine）。
 *
 * 为什么非得 chroot：Alpine 的 clang 是 **musl + 原生 aarch64** 的二进制 —— 动态链接器、
 * `/lib`、`/usr` 全在 rootfs 里，脱离它自己的用户态就跑不起来。
 *
 * 三件必须做对的事（都是从失败里长出来的）：
 * 1. **PATH**：chroot 里 `/bin/sh` 不读 profile，`apk` 在 `/sbin` —— 不显式给 PATH
 *    就是一句 `apk: not found`，看不出是 PATH 的问题。
 * 2. **`/proc` 与 `/dev`**：`apk` 要它们；不挂就是各种莫名其妙的中断。
 * 3. **`/etc/resolv.conf`**：chroot 里没有它，`apk` 连不上任何东西（报错还是
 *    「Permission denied」，最容易往权限上查偏）。
 *
 * 只在 **root 通道**下能用 —— `chroot` 与 `mount` 都要 root。
 */
class RootFs(
    val dir: File,
    private val shell: ShellChannel,
    /** 设备上 `chroot` 的位置：toybox / busybox 都有，一般在 PATH 里。 */
    private val chrootBin: String = "chroot",
) {

    /** rootfs 里的工作目录（源码进去、产物出来），单向映射到宿主上的 <dir>/smithy-build。 */
    val buildDirName = "smithy-build"

    fun hostBuildDir(): File = File(dir, buildDirName)

    /** chroot 里的编译器路径。 */
    var clangPath: String = "/usr/bin/clang++"

    /** 把一条脚本包成「在 rootfs 里跑」。**这条命令的拼法是要能被测试钉住的**。 */
    fun chrootCommand(script: String): String =
        // 先给 PATH 再执行：见类注释第 1 条
        "$chrootBin ${quote(dir.absolutePath)} /bin/sh -c " +
            quote("export PATH=/sbin:/usr/sbin:/bin:/usr/bin; $script")

    /** 在 rootfs 里跑一条脚本。 */
    fun sh(script: String, timeoutSeconds: Long = 180): ShellResult =
        shell.exec(chrootCommand(script), timeoutSeconds)

    /**
     * 首次准备（幂等）：
     * - 建 `/proc`、`/dev`、`/sys` 挂载点并挂上（已经挂了就跳过 —— 重复挂会叠层）
     * - 写 `/etc/resolv.conf`（从设备上抄一份，设备上没有就用公共 DNS）
     */
    fun prepare(): ShellResult {
        val script = """
            mkdir -p /proc /dev /sys /$buildDirName
            mountpoint -q /proc || mount -t proc proc /proc
            mountpoint -q /dev  || mount -o bind /dev /dev
            mountpoint -q /sys  || mount -o bind /sys /sys
            printf ${quote(resolvConf())} > /etc/resolv.conf
            echo prepared
        """.trimIndent()
        return sh(script, timeoutSeconds = 60)
    }

    /**
     * chroot 里那份 `/etc/resolv.conf` 的内容。
     *
     * **先抄设备自己的**（有些网络只认本地 DNS，用公共 DNS 会解析失败 —— 症状是
     * `bad address`，看着像网络不通，其实是 DNS 指错了），读不到才退回公共 DNS。
     */
    internal fun resolvConf(): String {
        val fromDevice = runCatching {
            File("/etc/resolv.conf").takeIf { it.isFile }?.readLines()
                .orEmpty()
                .map { it.trim() }
                .filter { it.startsWith("nameserver") }
        }.getOrNull().orEmpty()
        val lines = if (fromDevice.isNotEmpty()) fromDevice else listOf("nameserver 1.1.1.1", "nameserver 8.8.8.8")
        return lines.joinToString("\n") + "\n"
    }

    /** rootfs 里有没有编译器（没有就该先装）。 */
    fun hasClang(): Boolean =
        shell.available() && sh("test -x $clangPath && $clangPath --version | head -1", timeoutSeconds = 60).ok

    /**
     * 装构建工具：`apk add --no-cache clang`。
     *
     * 约 100–200MB（clang + llvm 库 + musl 头），一次性的事，所以给足超时。
     */
    fun installClang(timeoutSeconds: Long = 900): ShellResult =
        sh("apk add --no-cache clang", timeoutSeconds)

    /** 有没有 root 通道 —— 没有的话这个 rootfs 什么都干不了。 */
    fun usable(): Boolean = shell.available()

    /**
     * 把 App 目录里的 rootfs **部署到可执行位置**。
     *
     * 为什么不能就地用 `filesDir/addon/rootfs`：chroot 进去的第一个东西（Alpine 的
     * `/bin/sh`、`ld-musl`）得是**可执行**的，而应用私有目录在 Android 10+ 上执行策略越来越紧；
     * 而且 rootfs 由 root 拥有时才好在里面装东西。所以拷到 `/data/local/tmp/smithy/rootfs`
     * （root 可写、确定可执行）。
     *
     * 已经部署过就不覆盖 —— 那里面可能有装好的 clang（几百 MB），覆盖等于白装。
     * 要重装就先把目标删掉。
     */
    fun deploy(sourceRootfs: File, timeoutSeconds: Long = 300): ShellResult {
        val script = buildString {
            append("test -x ${quote("${dir.absolutePath}/bin/sh")} && { echo already; exit 0; }\n")
            append("mkdir -p ${quote(dir.absolutePath)}\n")
            append("cp -a ${quote(sourceRootfs.absolutePath)}/. ${quote(dir.absolutePath)}/\n")
            append("test -x ${quote("${dir.absolutePath}/bin/sh")} && echo deployed\n")
        }
        return shell.exec(script, timeoutSeconds)
    }

    companion object {
        /** 部署位置：root 可写、确保可执行。 */
        fun deployDirFor(name: String = "rootfs"): String = "/data/local/tmp/smithy/$name"
    }
}

/**
 * 用 rootfs 里的 clang 编模块。
 *
 * 要编的东西都在 chroot **外面**（源码在应用目录、sysroot 从可选组件来、产物要落回应用目录），
 * 而 chroot 里只有 rootfs —— 所以：
 *
 * ```
 * ① 源码与 sysroot 拷进 <rootfs>/smithy-build/   （cp -a，经 root 通道）
 * ② chroot 里跑 /usr/bin/clang++ <chroot 内路径> -o /smithy-build/<abi>.so
 * ③ 产物拷回应用目录
 * ```
 *
 * 用拷贝而不是 bind mount：挂上去的东西在设备重启后不会自动卸，而模块源码一共就几 KB、
 * sysroot 也只有一份（拷过一次就不再拷）。
 */
class ChrootClangToolchain(
    private val rootFs: RootFs,
    private val shell: ShellChannel,
    /** sysroot 在宿主上的位置（可选组件装的或用户手工放的）。 */
    private val sysroot: File?,
) : NativeToolchain {

    override val name = "clang（rootfs/chroot）"

    override fun available(): Boolean = rootFs.usable() && rootFs.hasClang()

    override fun describe(): String = buildString {
        append("rootfs：${rootFs.dir.absolutePath}")
        append("；编译器：rootfs 内 ${rootFs.clangPath}")
        if (sysroot != null) append("；target sysroot：${sysroot.absolutePath}")
        append("；${if (available()) "可用（经 root 通道）" else "现在不可用（root 没授权，或 rootfs 里还没装 clang）"}")
    }

    /** chroot 里的路径：`<rootfs>/smithy-build/<rest>` → `/smithy-build/<rest>`。 */
    internal fun inChroot(rest: String): String = "/${rootFs.buildDirName}/$rest"

    /**
     * 拼 chroot 里那条 clang 命令行。
     *
     * 和 [ClangToolchain.commandLine] 的区别只有路径：这里的 `--sysroot`、`-o`、源文件
     * 都得是 **chroot 内** 的路径，否则 clang 在 rootfs 里看不见它们。
     */
    internal fun commandLine(spec: NativeBuildSpec, sourcesInChroot: List<String>): List<String> {
        val argv = mutableListOf(rootFs.clangPath)
        argv += "--target=${spec.abi.clangTriple}${spec.apiLevel}"
        argv += listOf("-shared", "-fPIC", "-O2", "-std=c++17", "-fvisibility=hidden")
        argv += "--sysroot=${inChroot("sysroot")}"
        listOf("sysroot/usr/include", "sysroot/usr/include/c++/v1", "sysroot/usr/include/${spec.abi.clangTriple}")
            .forEach { argv += listOf("-isystem", inChroot(it)) }
        argv += listOf("-L", inChroot("sysroot/usr/lib/${spec.abi.clangTriple}/${spec.apiLevel}"))
        argv += listOf("-L", inChroot("sysroot/usr/lib/${spec.abi.clangTriple}"))
        argv += "-o"; argv += inChroot("out/${spec.abi.abiName}.so")
        argv += spec.flags
        argv += sourcesInChroot
        return argv
    }

    override fun build(spec: NativeBuildSpec): NativeBuildResult {
        if (!available()) {
            return NativeBuildResult(false, "rootfs 这条路现在不可用：${describe()}", command = emptyList())
        }
        if (!spec.sourceDir.isDirectory) {
            return NativeBuildResult(false, "源码目录不存在：${spec.sourceDir}")
        }
        val missing = spec.sources.filter { !File(spec.sourceDir, it).isFile }
        if (missing.isNotEmpty()) {
            return NativeBuildResult(false, "找不到源文件：${missing.joinToString()}")
        }
        val build = rootFs.hostBuildDir()
        val q = { s: String -> quote(s) }

        // ① 清掉旧工作区，把源码与 sysroot 拷进去
        val stage = buildString {
            append("rm -rf /${rootFs.buildDirName} && mkdir -p /${rootFs.buildDirName}/src /${rootFs.buildDirName}/out\n")
            append("cp -a ${q(spec.sourceDir.absolutePath)}/. /${rootFs.buildDirName}/src/\n")
            if (sysroot != null) {
                // sysroot 只拷一次：几百个文件，每次编都拷会白等
                append("test -d /${rootFs.buildDirName}/sysroot || cp -a ${q(sysroot.absolutePath)} /${rootFs.buildDirName}/sysroot\n")
            }
            append("echo staged")
        }
        val staged = rootFs.sh(stage, timeoutSeconds = 300)
        if (!staged.ok) {
            return NativeBuildResult(false, "把源码/sysroot 拷进 rootfs 失败：\n${staged.out.tail(20)}", command = listOf(rootFs.chrootCommand(stage)))
        }

        // ② 在 chroot 里编
        val sourcesInChroot = spec.sources.map { inChroot("src/$it") }
        val argv = commandLine(spec, sourcesInChroot)
        // clang 的 resource dir：Alpine 装在 /usr/lib/clang/<版本>，让 clang 自己找；
        // 但 chroot 里没有 /usr/bin/../../ 那套约定问题，所以直接用绝对路径，写死更省事
        val runBuild = "cd /${rootFs.buildDirName}/src && ${argv.joinToString(" ") { q(it) }}"
        val compiled = rootFs.sh(runBuild, timeoutSeconds = 600)

        // ③ 产物拷回宿主（应用自己的目录，之后用普通 File 读）
        val hostOut = File(spec.outFile.absolutePath)
        hostOut.parentFile?.mkdirs()
        val copyOut = "cp /${rootFs.buildDirName}/out/${spec.abi.abiName}.so ${q(hostOut.absolutePath)} && echo copied"
        val copied = if (compiled.ok) rootFs.sh(copyOut, timeoutSeconds = 120) else ShellResult(-1, "")

        val ok = compiled.ok && copied.ok && hostOut.isFile
        return NativeBuildResult(
            ok = ok,
            log = compiled.out,
            soFile = hostOut.takeIf { ok },
            command = argv,
        )
    }
}

/** 只保留最后 [n] 行 —— 日志给用户看的那一份不该是把整个编译过程铺开。 */
internal fun String.tail(n: Int): String = lines().takeLast(n).joinToString("\n")

/** 单引号包住，内部单引号按 shell 的规矩转义。 */
internal fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
