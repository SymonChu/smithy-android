package dev.smithy.fs

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 目标 ABI。
 *
 * 名字必须与 `zygisk/<abi>.so` 的文件名一致 —— Magisk 是**按文件名**认 ABI 的，
 * 所以这里的值既用来拼 clang 的 target triple，也用来定产物名。唯一的事实来源是
 * [ModuleLayouts.KNOWN_ABIS]（有一条测试钉着两者不许分叉）。
 */
enum class NativeAbi(val abiName: String, val clangTriple: String) {
    ARM64_V8A("arm64-v8a", "aarch64-linux-android"),
    ARMEABI_V7A("armeabi-v7a", "armv7a-linux-androideabi"),
    X86("x86", "i686-linux-android"),
    X86_64("x86_64", "x86_64-linux-android"),
    RISCV64("riscv64", "riscv64-linux-android"),
    ;

    companion object {
        fun of(name: String): NativeAbi? = entries.firstOrNull { it.abiName == name.trim().lowercase() }
    }
}

/** 一次编译要什么。 */
data class NativeBuildSpec(
    val abi: NativeAbi,
    /** Android API 等级（决定 target triple 的后缀与链接哪个平台的库）。 */
    val apiLevel: Int,
    /** 源码所在目录（相对路径在 [sources] 里）。 */
    val sourceDir: File,
    val sources: List<String>,
    val outFile: File,
    /** 额外的编译选项。模板那一套（`-fno-exceptions` 等）由调用方给 —— 驱动层不该
     *  替别人的源码定风格。 */
    val flags: List<String> = emptyList(),
)

data class NativeBuildResult(
    val ok: Boolean,
    /** 编译器输出（原样）。失败时它就是唯一线索，所以不截断、不加工。 */
    val log: String,
    val soFile: File? = null,
    /** 真正跑的那条命令行。出问题时要能一眼看出「用的是哪个编译器、带没带 sysroot」。 */
    val command: List<String> = emptyList(),
)

/**
 * native 编译工具链 —— 「源码 → `.so`」这一步的接缝。
 *
 * 和 [ModuleChannel] 一样是**引擎层定义的接口、App 层注册实现**：这样工具层（`:toolkit`）
 * 不依赖任何具体的编译器分发方式，也不必知道 clang 装在哪。换一个工具链（NDK 的 wrapper、
 * 自己打包的 bundle、甚至远端编译）只换实现。
 *
 * 为什么默认实现是「直接起进程」而不是内嵌一个编译器：clang + sysroot + libc++ 是
 * 300–400MB 的二进制，它们不该进主包（见 docs/06 的 M6-B / M5 下载项）。所以接口先立好，
 * **工具链从哪来**是分发问题，代码这边只负责「找到了就用对，找不到就说清缺什么」。
 */
interface NativeToolchain {

    val name: String

    /** 现在能不能用。要如实反映（比如文件在但没有执行位），界面与模型据此提前判断。 */
    fun available(): Boolean

    /** 给人看的一句话：用的哪个编译器、sysroot 在哪、可用与否。 */
    fun describe(): String

    fun build(spec: NativeBuildSpec): NativeBuildResult

    /**
     * 这份工具链能不能**静态**链 libc++。
     *
     * NDK 的 sysroot 里有 `libc++_static.a` ✔；Termux 的 `libc++` 包**只给共享库**
     * （`libc++_shared.so`），静态链不了 ✘ —— 那就退回 `-nostdlib++`：模板只用 C 头，
     * 不需要 C++ 运行时，产物从 442KB 掉到 8.5KB，而且不再依赖 `libc++_shared.so`
     * （这条才是关键：目标进程里没有它，带了就是「装上了但不加载」）。
     */
    fun supportsStaticLibcxx(): Boolean = true

    /** 平台桩库（`liblog`、`libc` 这些）的目录。设备上是 `/system/lib64`（或 32 位的 `/system/lib`）。 */
    fun platformLibDirs(spec: NativeBuildSpec): List<File> = emptyList()
}

/**
 * clang / clang++ 驱动。
 *
 * 走的命令形状（这四组缺一个在设备上都表现为编译失败，所以单独有测试钉住）：
 *
 * 1. `--target=<triple><api>` —— 交叉编译到具体 ABI 与 API 等级；
 * 2. `--sysroot=<root>` + `-isystem <root>/usr/include[/…]` —— 头文件；
 * 3. `-L <root>/usr/lib/<triple>[/<api>]` —— 库搜索路径；
 * 4. `-shared -fPIC` + `-o <abi>.so` —— Zygisk 要的是一个能被 dlopen 的共享库，
 *    名字还得正好是 ABI 名。
 *
 * sysroot 下的路径**按存在与否加**（NDK 各版本布局略有差别，我们自己的 bundle 也一样）：
 * 硬写死一串不存在的 `-isystem` 会让 clang 直接报错退出，而不是继续找。
 */
class ClangToolchain(
    private val clang: File,
    private val sysroot: File? = null,
    private val timeoutSeconds: Long = 600,
    /**
     * 跑编译器时补的环境变量。
     *
     * **bionic 工具链（Termux 那一套）必须有它**：`clang` 依赖包内的 `lib/libLLVM.so` 等，
     * 而它的 RUNPATH 指的是 Termux 自己的前缀（手机上不存在）—— 只能靠 `LD_LIBRARY_PATH`
     * 指到本包的 `lib/`；`PATH` 里也得有本包的 `bin/`，不然它找不到 `ld.lld` 去链接。
     */
    private val extraEnv: Map<String, String> = emptyMap(),
) : NativeToolchain {

    override val name: String get() = "clang"

    /** 编译器本体。包装类（比如走 root 通道跑的 [TermuxClangToolchain]）要用这个路径。 */
    val clangBinary: File get() = clang

    override fun available(): Boolean = clang.isFile && clang.canExecute()

    /**
     * 静态 libc++ 在不在 —— **按文件判断，不猜**。
     *
     * 猜错的表现是链接期一串 undefined，那时离原因已经很远了。NDK 的 sysroot 里
     * `usr/lib/<triple>/libc++_static.a` 一定在；Termux 的 libc++ 包只有 `libc++_shared.so`。
     */
    override fun supportsStaticLibcxx(): Boolean {
        val root = sysroot ?: return false
        return NativeAbi.entries.any { File(root, "usr/lib/${it.clangTriple}/libc++_static.a").isFile } ||
            File(root, "lib/libc++.a").isFile
    }

    /** 设备上的平台桩库目录。本机没有就返回空（不往命令行里塞不存在的路径）。 */
    override fun platformLibDirs(spec: NativeBuildSpec): List<File> {
        val dir = when (spec.abi) {
            NativeAbi.ARM64_V8A, NativeAbi.X86_64, NativeAbi.RISCV64 -> "/system/lib64"
            else -> "/system/lib"
        }
        return listOf(File(dir)).filter { it.isDirectory }
    }

    override fun describe(): String = buildString {
        append(name).append("：").append(clang.absolutePath)
        append(if (sysroot != null) "；sysroot：${sysroot.absolutePath}" else "；没有 sysroot（只能用自带头文件的源码）")
        if (extraEnv.isNotEmpty()) append("；环境：").append(extraEnv.keys.joinToString("/"))
        append("；").append(if (available()) "可用" else "不可用（文件不存在或没有执行位）")
    }

    /**
     * 拼命令行。单独暴露出来是为了能在测试里逐字比对 —— 这段错了只会在设备上表现为
     * 「编译失败」，而设备上排一条编译命令行很贵。
     */
    fun commandLine(spec: NativeBuildSpec): List<String> {
        val argv = mutableListOf(clang.absolutePath)
        argv += "--target=${spec.abi.clangTriple}${spec.apiLevel}"
        argv += listOf("-shared", "-fPIC", "-std=c++17")
        sysroot?.let { root ->
            argv += "--sysroot=$root"
            existingDirs(
                // Termux 那套是**平铺**布局：include/ 与 lib/ 直接在最外层
                "$root/include",
                "$root/usr/include",
                // libc++ 的头（NDK 放在 sysroot 里）
                "$root/usr/include/c++/v1",
                // 架构相关头
                "$root/usr/include/${spec.abi.clangTriple}",
            ).forEach { argv += listOf("-isystem", it) }
            // 平台库的目录：NDK 按 API 分目录，其余分发只有一个目录
            existingDirs(
                "$root/lib",
                // Termux 的**链接期桩库**放在按 ABI 命名的子目录里（aarch64-linux-android/lib）
                "$root/${spec.abi.clangTriple}/lib",
                "$root/usr/lib/${spec.abi.clangTriple}/${spec.apiLevel}",
                "$root/usr/lib/${spec.abi.clangTriple}",
            ).forEach { argv += listOf("-L", it) }
        }
        // 设备上的平台桩库：`-llog` 只在 /system/lib64 里（Termux 的 sysroot 不含它）
        platformLibDirs(spec).forEach { argv += listOf("-L", it.absolutePath) }
        argv += "-o"
        argv += spec.outFile.absolutePath
        // 静态 libc++ 拿不到就换成 -nostdlib++（见 supportsStaticLibcxx）
        argv += if (supportsStaticLibcxx()) {
            spec.flags
        } else {
            spec.flags.flatMap { f -> if (f == "-static-libstdc++") listOf("-nostdlib++") else listOf(f) }
        }
        argv += spec.sources.map { File(spec.sourceDir, it).absolutePath }
        return argv
    }

    override fun build(spec: NativeBuildSpec): NativeBuildResult {
        if (!available()) {
            return NativeBuildResult(false, "编译器不可用：${clang.absolutePath}（文件不存在或没有执行位）")
        }
        if (!spec.sourceDir.isDirectory) {
            return NativeBuildResult(false, "源码目录不存在：${spec.sourceDir}")
        }
        val missing = spec.sources.filter { !File(spec.sourceDir, it).isFile }
        if (missing.isNotEmpty()) {
            return NativeBuildResult(false, "找不到源文件：${missing.joinToString(", ")}")
        }
        spec.outFile.parentFile?.mkdirs()
        val argv = commandLine(spec)

        return runCatching {
            val pb = ProcessBuilder(argv)
                // 在源码目录里跑：源码用相对路径 include 同目录的头（骨架就是这样）
                .directory(spec.sourceDir)
                .redirectErrorStream(true)
            // bionic 工具链靠这个找到包内的 libLLVM / 自己的 ld.lld（见 extraEnv 注释）
            if (extraEnv.isNotEmpty()) pb.environment().putAll(extraEnv)
            val process = pb.start()
            // 先读完输出再等退出：编译日志多了以后，写满管道会把进程卡死
            val log = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return NativeBuildResult(
                    false,
                    log + "\n（编译超过 ${timeoutSeconds}s，已终止）",
                    command = argv,
                )
            }
            val ok = process.exitValue() == 0 && spec.outFile.isFile
            NativeBuildResult(ok, log, spec.outFile.takeIf { ok }, argv)
        }.getOrElse { e ->
            NativeBuildResult(false, "起不了编译器进程：${e.message}", command = argv)
        }
    }

    private fun existingDirs(vararg paths: String): List<String> = paths.filter { File(it).isDirectory }
}

/**
 * 工具链注册表。
 *
 * 与 [ModuleChannels] 同一个套路：引擎/工具层只认 [NativeToolchain]，App 层负责
 * 在哪里找、找不找得到。放在 core:fs 是因为模块工程（[ModuleScaffold] /
 * [ModuleNativeBuild]）都在这一层，`:toolkit` 能直接用到，不需要反向依赖某个 feature。
 */
object NativeToolchains {

    @Volatile
    private var chain: NativeToolchain? = null

    /** 注册（传 null 等于清掉 —— 重新扫描时用）。 */
    fun register(toolchain: NativeToolchain?) {
        chain = toolchain
    }

    fun current(): NativeToolchain? = chain

    /**
     * 「还没有工具链」的标准措辞。
     *
     * 界面（模块页的编译卡）、工具层（`module.build` / `native.toolchain`）与异常都取这一份 ——
     * 这件事说三遍就会有三份说法，而用户看到的应该始终是同一句。
     *
     * 措辞面向**用户**：说清缺什么、放哪儿、以及退路（纯脚本模块不需要它）；
     * 仓库里的路径（docs/06 的 M6-B）不进这句话。
     */
    fun missingHint(): String =
        "还没有编译工具链。zygisk 模块要先把它编成 zygisk/<abi>.so —— " +
            "在「模块」页的编译卡里点「下载工具链包」（约 154MB，免 root），" +
            "或者点「导入工具链包…」选一个手机上已有的包。装完这里会自动变成可点。" +
            "纯脚本模块不需要编译，照样能刷能生效"

    /** 拿一个「一定可用」的工具链，不可用时抛带原因的异常。 */
    fun require(): NativeToolchain {
        val t = chain ?: throw IllegalStateException(missingHint())
        if (!t.available()) {
            throw IllegalStateException("编译工具链「${t.name}」现在不可用：${t.describe()}")
        }
        return t
    }

    /** 工具链可能在哪几个地方 —— App 起来时、以及刚装完一个可选组件之后都扫这几个。 */
    fun standardRoots(filesDir: File): List<File> = listOf(
        // 先看可选组件装过去的（AddOnManager 的根 + 它的工具链子目录）
        File(File(filesDir, "addon"), AddOnKind.TOOLCHAIN.dirName),
        // 再看用户手工解进去的
        File(filesDir, AddOnKind.TOOLCHAIN.dirName),
        File(filesDir, "ndk"),
    )

    /**
     * 扫一批目录，把第一份能用的登记上去。返回登记的那份（都没有就是 null）。
     *
     * 装完可选组件要**立刻重扫**：装之前说「缺工具链」，装之后同一句话就不能再出现 ——
     * 否则用户会以为装了个没用的东西。
     */
    fun scan(vararg roots: File): NativeToolchain? {
        val found = roots.filter { it.isDirectory }
            .firstNotNullOfOrNull { root -> locateIn(root)?.takeIf { it.available() } }
        register(found)
        return found
    }

    /**
     * 检查「rootfs + chroot」这条路通不通，通了就登记成当前工具链。
     *
     * 和 [scan] 分开是有意的：这一条要**跑命令**（要 root、要几百毫秒到几秒），
     * 不能塞进 App 启动的同步流程里。由模块页在 IO 线程上按需调用。
     *
     * 找到 rootfs 里的 clang 就登记 —— 优先级低于普通工具链（能直接跑的更省事），
     * 所以调用方应该**先** [scan]，扫描没结果再调这个。
     */
    fun scanChroot(shell: ShellChannel?, rootfsMount: String, sysroot: File?): NativeToolchain? {
        if (shell == null || !shell.available()) return null
        val rootFs = RootFs(File(rootfsMount), shell)
        if (!rootFs.hasClang()) return null
        val chain = ChrootClangToolchain(rootFs, shell, sysroot)
        register(chain)
        return chain
    }

    /**
     * 手机上装了 Termux 的话，直接用它的 clang（**零下载**）。
     *
     * 探测要 root：Termux 的目录是它自己的私有目录，应用看不见。没 root、没装 Termux
     * 就返回 null —— **这不是错误**，只是这条路走不通；还有「下载工具链包」与
     * 「导入工具链包」两条。
     */
    fun scanTermux(
        shell: ShellChannel?,
        prefixes: List<String> = listOf("/data/data/com.termux/files/usr"),
    ): NativeToolchain? {
        if (shell == null || !shell.available()) return null
        val prefix = prefixes.firstOrNull { shell.exec("test -x $it/bin/clang++", 20L).ok } ?: return null
        val chain = TermuxClangToolchain(File(prefix), shell)
        register(chain)
        return chain
    }

    /**
     * chroot 那条路用的 target sysroot 在哪儿。
     *
     * 两个来源：单独下的那一片（`addon/sysroot`，只含 arm64 的 bionic 头与桩库），
     * 或 bundle 自带的（`addon/native-toolchain/sysroot`）。前者优先。
     */
    fun chrootSysroot(filesDir: File): File? {
        val addon = File(filesDir, "addon")
        return listOf(
            File(addon, AddOnKind.SYSROOT.dirName),
            File(addon, "${AddOnKind.TOOLCHAIN.dirName}/sysroot"),
        ).firstOrNull { it.isDirectory }
    }

    /**
     * 在一个目录里找工具链。
     *
     * 认两种布局：
     * - **我们自己的 bundle**：`<root>/bin/clang++`（或 `clang`）+ 可选 `<root>/sysroot`；
     * - **NDK**：`<root>/toolchains/llvm/prebuilt/<host>/bin/clang++` + 同级的 `sysroot`。
     *
     * 找不到就返回 null —— 由调用方决定怎么措辞（不在这里胡乱猜一个路径出来）。
     */
    fun locateIn(root: File): NativeToolchain? {
        if (!root.isDirectory) return null
        directClang(root)?.let {
            return ClangToolchain(it, sysrootOf(root) ?: flatSysrootOf(root), extraEnv = envFor(root))
        }
        // NDK 形态：toolchains/llvm/prebuilt/<host>/
        val prebuilt = File(root, "toolchains/llvm/prebuilt")
        val hosts = prebuilt.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (host in hosts) {
            val clang = directClang(host) ?: continue
            return ClangToolchain(clang, sysrootOf(host) ?: flatSysrootOf(host), extraEnv = envFor(host))
        }
        return null
    }

    /** `<root>/sysroot`；没有就给 null（不是错误：有的源码不需要额外头）。 */
    fun sysrootOf(root: File): File? = File(root, "sysroot").takeIf { it.isDirectory }

    /**
     * 平铺布局（Termux 那一套）的 sysroot 就是包根：`include/` 与 `lib/` 直接在最外层。
     *
     * 认这个是为了让「导入一份 Termux 工具链包」直接可用，不用在设备上再摆一次目录。
     */
    internal fun flatSysrootOf(root: File): File? =
        root.takeIf { File(it, "include").isDirectory && File(it, "lib").isDirectory }

    /**
     * 包内布局（`bin/` 与 `lib/` 并排）时要补的环境变量。
     *
     * bionic 工具链（Termux 那一套）的 `clang` 依赖包内的 `lib/libLLVM.so`、要用包内的
     * `ld.lld` 链接，而它们的 RUNPATH / 查找路径指的是 Termux 自己的前缀（手机上不存在）。
     * 所以：`LD_LIBRARY_PATH` 指到本包 `lib/`，`PATH` 里加上本包 `bin/`。
     * **不覆盖已有的值，而是追加** —— 设备上原来就有的路径不该被我们挤掉。
     */
    internal fun envFor(root: File): Map<String, String> {
        val lib = File(root, "lib")
        val bin = File(root, "bin")
        if (!lib.isDirectory) return emptyMap()
        val curLib = System.getenv("LD_LIBRARY_PATH").orEmpty()
        val curPath = System.getenv("PATH").orEmpty()
        return mapOf(
            "LD_LIBRARY_PATH" to (listOf(lib.absolutePath) + curLib.split(':').filter { it.isNotBlank() })
                .joinToString(":"),
            "PATH" to (listOf(bin.absolutePath) + curPath.split(':').filter { it.isNotBlank() })
                .joinToString(":"),
        )
    }

    private fun directClang(root: File): File? = listOf("clang++", "clang")
        .map { File(root, "bin/$it") }
        .firstOrNull { it.isFile && it.canExecute() }
}
