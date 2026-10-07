package dev.smithy.fs

import java.io.File

/**
 * 把模块 zip 里 `jni/` 下的源码编成 `zygisk/<abi>.so`，写回一个新的 zip。
 *
 * 这是「定制模块」链上最后一块拼图：`module.create` 给源码骨架 → 改源码 →
 * **这一步把源码变成 Magisk 真正会加载的东西** → `module.install` 刷入。
 *
 * 三件事刻意写死在这里，而不是留给调用方每次决定：
 *
 * 1. **产物必须落在 `zygisk/<abi>.so`**，且文件名就是 ABI 名 —— Magisk 按文件名认 ABI，
 *    `libfoo.so` 那种会装上了但不加载；
 * 2. **原 zip 不动**，产物另存（刷进去不对还能拿原包重来，和改包那条线一致）；
 * 3. **编译前先检查 `zygisk.hpp`**：它是 0BSD 的第三方头文件（骨架里随包给了一份），
 *    缺了的话 clang 会报一串 `file not found`，不如直接说清「把这个文件放进来」。
 *
 * 工具链从外面传进来（[NativeToolchain]），所以这一层不需要知道 clang 装在哪、
 * 也不需要 Android/设备 —— 纯 JVM，可单测。
 */
object ModuleNativeBuild {

    /** 模块 zip 里放 native 源码的目录。 */
    const val SourceDir = "jni"

    /**
     * 骨架模板用的编译选项，与生成的 `CMakeLists.txt` 保持一致。
     *
     * `-fno-exceptions -fno-rtti`：Zygisk 模块跑在别人的进程里，异常与 RTTI 在这里是
     * 纯开销与风险（模块抛异常只会把宿主进程带崩）；`-fvisibility=hidden` 由驱动层加。
     *
     * `-static-libstdc++` 是这一串里最要紧的一条：NDK / clang 给共享库**默认动态**链接
     * libc++，于是产物会带一条 `NEEDED libc++_shared.so` —— 而 Zygisk 是把模块 dlopen 进
     * 目标应用的进程，那个进程里没有这个文件，结果是**装上了但不加载，且不报错**
     * （正是这一层反复在防的那种故障）。静态链接的代价是 .so 从十几 KB 涨到几百 KB，
     * 换的是「它真的会被加载」。
     *
     * `-llog`：模板用了 `__android_log_print`。共享库允许留未定义符号，不显式链接也能编过，
     * 但那就把「能不能解析到」交给了运行时的加载顺序 —— 显式依赖 liblog 才确定。
     */
    val TEMPLATE_FLAGS = listOf(
        "-fno-exceptions",
        "-fno-rtti",
        "-Wall",
        "-Wextra",
        "-static-libstdc++",
        "-llog",
    )

    private val SOURCE_EXT = setOf("cpp", "cc", "cxx", "c")
    private val HEADER_EXT = setOf("hpp", "h", "hxx", "hh")

    data class Result(
        val ok: Boolean,
        val outZip: File? = null,
        /** 写进 zip 的条目名，如 `zygisk/arm64-v8a.so`。 */
        val soEntry: String? = null,
        /** 编译器原样输出 —— 失败时它是唯一线索。 */
        val log: String = "",
        /** 失败时的「下一步」。**每一条失败都必须有**（和工具层同一套要求）。 */
        val hint: String? = null,
        val command: List<String> = emptyList(),
    )

    fun build(
        zip: File,
        abi: NativeAbi,
        out: File,
        toolchain: NativeToolchain,
        apiLevel: Int = 26,
        flags: List<String> = TEMPLATE_FLAGS,
        workDir: File? = null,
    ): Result {
        if (!zip.isFile) return Result(false, hint = "找不到模块 zip：$zip")
        if (!toolchain.available()) {
            return Result(false, hint = "编译工具链不可用（${toolchain.describe()}）")
        }

        val project = runCatching { ModuleProject.open(zip) }.getOrElse { e ->
            return Result(false, hint = "打不开这个模块：${e.message}")
        }

        return project.use { p ->
            val entries = p.entryNames()
            val sources = entries.filter {
                it.startsWith("$SourceDir/") && it.substringAfterLast('.', "").lowercase() in SOURCE_EXT
            }
            if (sources.isEmpty()) {
                return Result(
                    false,
                    hint = "这个模块里没有 $SourceDir/ 下的 C++ 源码，没东西可编。" +
                        "zygisk 那一档的骨架才有源码（新建模块时选「Zygisk 源码」，或 module.create flavour=zygisk）；" +
                        "纯脚本模块不需要编译",
                )
            }
            val headers = entries.filter {
                it.startsWith("$SourceDir/") && it.substringAfterLast('.', "").lowercase() in HEADER_EXT
            }
            // 用到 zygisk.hpp 但包里没有：先说清该放哪个文件，而不是让用户去读 clang 的报错
            val needsZygiskHeader = sources.any { p.readText(it)?.contains("zygisk.hpp") == true }
            if (needsZygiskHeader && headers.none { it == "$SourceDir/zygisk.hpp" }) {
                return Result(
                    false,
                    hint = "$SourceDir/ 里缺 zygisk.hpp —— 把它和源码放一起再编。" +
                        "官方样例工程的 module/jni/zygisk.hpp 就是这一份（0BSD，文件里写着不许改动内容）",
                )
            }

            val dir = workDir ?: File(System.getProperty("java.io.tmpdir"), "smithy-native-${System.nanoTime()}")
            dir.mkdirs()
            try {
                // 保持 zip 里的相对结构拷到临时目录：源码 include 同目录的头（骨架就是这样）
                (sources + headers).forEach { entry ->
                    val target = File(dir, entry.removePrefix("$SourceDir/"))
                    target.parentFile?.mkdirs()
                    val bytes = p.readBytes(entry) ?: return Result(false, hint = "读不出 $entry")
                    target.writeBytes(bytes)
                }

                val result = toolchain.build(
                    NativeBuildSpec(
                        abi = abi,
                        apiLevel = apiLevel,
                        sourceDir = dir,
                        sources = sources.map { it.removePrefix("$SourceDir/") },
                        outFile = File(dir, "${abi.abiName}.so"),
                        flags = flags,
                    ),
                )
                val so = result.soFile
                if (!result.ok || so == null) {
                    return Result(
                        false,
                        log = result.log,
                        hint = compileHint(result.log, abi),
                        command = result.command,
                    )
                }

                val entry = "zygisk/${abi.abiName}.so"
                val bytes = so.readBytes()
                // 原 zip 不动：产物另存，刷进去不对还能拿原包重来
                ModuleProject.open(zip).use { patched ->
                    patched.writeBytes(entry, bytes)
                    patched.packageTo(out)
                }
                Result(true, outZip = out, soEntry = entry, log = result.log, command = result.command)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * 编译失败 → 下一步。
     *
     * 这几条是照着最常见的三类原因写的：头文件缺（zygisk.hpp / 平台头）、目标不认识
     * （工具链不对）、源码本身有错。**不要**在这里说「编译失败」就结束 —— 那正是
     * 用户拿不到下一步的地方。
     */
    private fun compileHint(log: String, abi: NativeAbi): String = when {
        log.contains("zygisk.hpp") && (log.contains("not found") || log.contains("No such file")) ->
            "编译器找不到 zygisk.hpp：把它放进 jni/（官方样例工程 module/jni/zygisk.hpp，0BSD）"
        log.contains("jni.h") && (log.contains("not found") || log.contains("No such file")) ->
            "编译器找不到 jni.h：说明 sysroot 不对或没带 sysroot。换个工具链，或者检查 sysroot 下的 usr/include"
        log.contains("unknown argument", ignoreCase = true) || log.contains("unrecognized") ->
            "这个编译器不认识交叉编译参数（--target / --sysroot）：它多半是宿主机的编译器，不是给 Android 用的那一支"
        log.contains("error:", ignoreCase = true) ->
            "源码本身编译不过。日志里每条 error 都指出了文件与行号，改完再编；" +
                "注意目标平台是 ${abi.abiName}，只有 Android 的 API 可用"
        else -> "把上面的日志原文给用户看。编译失败通常落在三处：头文件（zygisk.hpp / 平台头）、" +
            "工具链（是不是给 Android 用的 clang）、源码本身"
    }
}
