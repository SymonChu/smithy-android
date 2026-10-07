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
) : NativeToolchain {

    override val name: String get() = "clang"

    override fun available(): Boolean = clang.isFile && clang.canExecute()

    override fun describe(): String = buildString {
        append(name).append("：").append(clang.absolutePath)
        append(if (sysroot != null) "；sysroot：${sysroot.absolutePath}" else "；没有 sysroot（只能用自带头文件的源码）")
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
                "$root/usr/include",
                // libc++ 的头（NDK 放在 sysroot 里）
                "$root/usr/include/c++/v1",
                // 架构相关头
                "$root/usr/include/${spec.abi.clangTriple}",
            ).forEach { argv += listOf("-isystem", it) }
            // 平台库的目录：NDK 按 API 分目录，其余分发只有一个目录
            existingDirs(
                "$root/usr/lib/${spec.abi.clangTriple}/${spec.apiLevel}",
                "$root/usr/lib/${spec.abi.clangTriple}",
            ).forEach { argv += listOf("-L", it) }
        }
        argv += "-o"
        argv += spec.outFile.absolutePath
        argv += spec.flags
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
            val process = ProcessBuilder(argv)
                // 在源码目录里跑：源码用相对路径 include 同目录的头（骨架就是这样）
                .directory(spec.sourceDir)
                .redirectErrorStream(true)
                .start()
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
        "还没有 native 编译工具链：zygisk 模块要先把它编成 zygisk/<abi>.so，" +
            "这需要 clang + Android sysroot（约 300–400MB，按需下载，不进主包）。" +
            "把它解到应用的 native-toolchain 目录后重启 App 就会自动扫到；" +
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
        directClang(root)?.let { return ClangToolchain(it, sysrootOf(root)) }
        // NDK 形态：toolchains/llvm/prebuilt/<host>/
        val prebuilt = File(root, "toolchains/llvm/prebuilt")
        val hosts = prebuilt.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (host in hosts) {
            val clang = directClang(host) ?: continue
            return ClangToolchain(clang, sysrootOf(host))
        }
        return null
    }

    /** `<root>/sysroot`；没有就给 null（不是错误：有的源码不需要额外头）。 */
    fun sysrootOf(root: File): File? = File(root, "sysroot").takeIf { it.isDirectory }

    private fun directClang(root: File): File? = listOf("clang++", "clang")
        .map { File(root, "bin/$it") }
        .firstOrNull { it.isFile && it.canExecute() }
}
