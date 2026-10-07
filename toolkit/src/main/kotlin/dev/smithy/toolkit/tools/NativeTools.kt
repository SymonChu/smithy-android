package dev.smithy.toolkit.tools

import dev.smithy.fs.ModuleNativeBuild
import dev.smithy.fs.NativeAbi
import dev.smithy.fs.NativeToolchains

import dev.smithy.toolkit.ArgReader
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.Results
import dev.smithy.toolkit.Tool
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.ToolResult
import dev.smithy.toolkit.ToolSpec
import dev.smithy.toolkit.schema
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 模块的 native 编译（M6-B）。
 *
 * 「定制模块」这条链是：`module.create`（源码骨架）→ 改 `jni/module.cpp` →
 * **编出 `zygisk/<abi>.so`** → `module.install` → `module.zygote_restart`。
 * 缺的就是中间那一步 —— 没有它，zygisk 那一档骨架只是源码，刷进去什么都不会发生。
 *
 * 编译器不随主包分发（clang + sysroot + libc++ 约 300–400MB），所以这里做的事是：
 * 拿到 App 层注册的工具链（[NativeToolchains]）→ 用 [ModuleNativeBuild] 编译并写回 zip。
 * **没有工具链时要说清缺什么**，而不是回一句「失败了」。
 */

/** 当前有没有可用的 native 编译工具链。模型据此决定「现在能不能编」，而不是编了才知道。 */
object NativeToolchainTool : Tool {
    override val spec = ToolSpec(
        name = "native.toolchain",
        description = "看 native 编译工具链（clang + Android sysroot）在不在、是哪一份。" +
            "**要给 zygisk 模块编 .so 之前先问一下** —— 不在的话先把源码准备好，" +
            "别等到编译失败才发现设备上根本没有工具链。",
        params = schema { },
        returns = "可用与否、编译器与 sysroot 的路径",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val toolchain = NativeToolchains.current()
            ?: return Results.fail(
                "NO_TOOLCHAIN",
                "还没有 native 编译工具链",
                toolchainHint(),
            )
        return Results.ok(
            toolchain.describe(),
            Results.json(
                "name" to toolchain.name,
                "available" to toolchain.available(),
            ),
        )
    }
}

/** 从模块里的 `jni/` 源码编出 `zygisk/<abi>.so`，写回一个新的模块 zip。 */
object ModuleBuildTool : Tool {
    override val spec = ToolSpec(
        name = "module.build",
        description = "把模块里 jni/ 下的 C++ 源码编成 zygisk/<abi>.so，结果写到 out。" +
            "**这是「改了模块逻辑」之后让它真的生效的那一步**：zygisk 模块的源码本身刷进去没用，" +
            "Magisk 只认 zygisk/<abi>.so（而且必须是这个文件名，它按文件名认 ABI）。" +
            "原 zip 不会被改。编完用 module.install 刷入、module.zygote_restart 让它生效。",
        params = schema {
            string("zip", "带 jni/ 源码的模块 zip（module.create flavour=zygisk 建的骨架就有）", required = true)
            string("abi", "目标 ABI：arm64-v8a（默认）/ armeabi-v7a / x86 / x86_64 / riscv64")
            integer("api", "最低 Android API 等级，默认 26（和设备上模块的 minSdk 是一回事）")
            string("out", "输出 zip 的路径。不给就写成同目录的 <原名>-<abi>.zip")
            string("flags", "额外编译选项，空格分隔（会原样追加到最后）")
        },
        returns = "输出 zip 路径、写进去的 so 条目、编译器日志（失败时是主要线索）",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val zip = File(a.requireStr("zip", "给我模块 zip 的路径"))
        val abiName = a.str("abi") ?: "arm64-v8a"
        val abi = NativeAbi.of(abiName)
            ?: return Results.fail(
                "BAD_ABI",
                "不认的 ABI「$abiName」",
                "只能是 ${NativeAbi.entries.joinToString(" / ") { it.abiName }}；" +
                    "目标设备的 ABI 可以用 module.list_installed 之外的设备信息确认，拿不准就用 arm64-v8a（绝大多数手机）",
            )

        val toolchain = NativeToolchains.current()
            ?: return Results.fail("NO_TOOLCHAIN", "还没有 native 编译工具链，编不了 .so", toolchainHint())
        if (!toolchain.available()) {
            return Results.fail(
                "TOOLCHAIN_UNAVAILABLE",
                "编译工具链「${toolchain.name}」现在不可用：${toolchain.describe()}",
                toolchainHint(),
            )
        }

        val out = a.str("out")?.let { File(it) } ?: File(zip.parentFile, "${zip.nameWithoutExtension}-${abi.abiName}.zip")
        val api = a.int("api", 26)
        val flags = a.str("flags")?.split(' ', '\t')?.filter { it.isNotBlank() }

        val result = ModuleNativeBuild.build(
            zip = zip,
            abi = abi,
            out = out,
            toolchain = toolchain,
            apiLevel = api,
            flags = ModuleNativeBuild.TEMPLATE_FLAGS + flags.orEmpty(),
        )

        if (!result.ok) {
            // 编译器日志是唯一线索：短的直接给，长的落盘再给路径（不塞满上下文）
            val log = result.log
            val logNote = when {
                log.isBlank() -> ""
                log.length <= LOG_INLINE_LIMIT -> "\n\n编译器输出：\n$log"
                else -> {
                    val file = File(zip.parentFile, "${zip.nameWithoutExtension}-${abi.abiName}.build.log")
                    runCatching { file.writeText(log) }
                    "\n\n编译器输出 ${log.length} 字符，已写到 ${file.absolutePath}\n最后几行：\n" +
                        log.lines().takeLast(15).joinToString("\n")
                }
            }
            return Results.fail(
                "BUILD_FAILED",
                "编译失败：${result.hint.orEmpty()}$logNote",
                "${toolchainHint()} 工具链已经有了的话，先看日志里的第一条 error —— 它指的文件与行号就是源头",
            )
        }

        return Results.ok(
            "已编出 ${result.soEntry}，新模块在 ${result.outZip?.absolutePath}\n" +
                "接着 module.install 刷入（需要 root），再用 module.zygote_restart 让它生效",
            Results.json(
                "out" to result.outZip?.absolutePath,
                "so" to result.soEntry,
                "abi" to abi.abiName,
                "bytes" to result.outZip?.let { it.length() },
            ),
        )
    }

    private const val LOG_INLINE_LIMIT = 4000
}

/**
 * 「没有工具链」的措辞：正文取自 [NativeToolchains.missingHint]（界面说的是同一句），
 * 只在给模型看的地方补一句仓库内的出处 —— 模型能去看 M6-B，用户不需要知道 doc 编号。
 */
private fun toolchainHint(): String =
    NativeToolchains.missingHint() +
        "（工具链也可以直接用一份 NDK，放到 filesDir/ndk 下即可；开发者细节见 docs/06 的 M6-B）"
