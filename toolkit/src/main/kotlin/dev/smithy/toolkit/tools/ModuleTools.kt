package dev.smithy.toolkit.tools

import kotlinx.serialization.json.JsonObject
import dev.smithy.engine.ModuleChannels
import dev.smithy.fs.ModuleProject
import dev.smithy.fs.ModuleProp
import dev.smithy.fs.ModuleScaffold
import dev.smithy.fs.ModuleSkeletonSpec
import dev.smithy.toolkit.ArgReader
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.Results
import dev.smithy.toolkit.Tool
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.ToolResult
import dev.smithy.toolkit.ToolSpec
import dev.smithy.toolkit.schema
import java.io.File

/**
 * Magisk / Zygisk 模块的工具。
 *
 * **为什么按路径操作，而不是像 APK 那样先 open 一个工作区**：
 * `ToolContext.workspace` 的类型是 `ApkProject`（改包用的，带资源表、签名那些）。
 * 模块是另一种东西 —— 就是一个 zip 加几个纯文本文件。硬塞进同一个工作区会把它搞乱，
 * 而专门再加一个工作区概念，会让每个工具都要判断「现在是哪种工程」。
 *
 * 所以改成：**每个改动的工具都收一个 `zip` 收一个 `out`**，做完一步写出一个新包。
 * 链条是显式的（改 prop → out1；改脚本 → out2），模型能看见每一步的产物，
 * 出问题也知道是哪一步。代价是文件多几个，但比一个隐式的工作区状态好查。
 */
private fun defaultOut(zip: File, suffix: String = "-edited"): File =
    File(zip.parentFile, zip.nameWithoutExtension + suffix + ".zip")

/** 打开模块时统一的失败措辞。 */
private fun notAModule(zip: File): ToolResult = Results.fail(
    "NOT_A_MODULE",
    "${zip.name} 不是一个模块（根目录没有 module.prop，或者 prop 里缺 id / versionCode）",
    "模块 zip 的条目要直接在根上（module.prop、zygisk/、service.sh），不能套一层目录。" +
        "如果这是个改包用的 apk，那该走改包那条线",
)

// ── 新建 ──────────────────────────────────────────────────────

/**
 * 从零建一个模块骨架。
 *
 * 这是「用 AI 给某个软件写一个模块」那条链的**第一步**：之前所有模块工具都要求
 * 「先有一个模块 zip」，也就是说用户得在别处把 zip 结构拼对才能进来。骨架由
 * [ModuleScaffold] 生成（结构约束那几条它已经钉死），模型拿到路径后接着用
 * `module.write_text` / `module.set_prop` 往里填内容，最后 `module.install` 刷入。
 *
 * 两档的差别要说透，否则模型会给用户一个「刷进去没用」的东西：shell 档刷入即生效；
 * zygisk 档只是 native 源码，`.so` 还得编（手机上要等构建模块）。
 */
object ModuleCreateTool : Tool {
    override val spec = ToolSpec(
        name = "module.create",
        description = "新建一个 Magisk 模块骨架（zip）。**要给某个软件做模块就从这一步开始** —— " +
            "module.prop、开机脚本、system.prop 都按 Magisk 的规范摆好了，位置也对（条目直接在根上），" +
            "接着用 module.write_text 往里写真正要干的事。" +
            "flavour=shell（默认）：纯脚本模块，刷入即生效，不需要任何编译链；" +
            "flavour=zygisk：额外给 jni/ 下的 native 源码骨架，**源码不是能生效的模块**，" +
            "要先把 arm64-v8a.so 编出来（手机上需要构建模块），骨架里不会放占位的 so。",
        params = schema {
            string("dir", "骨架 zip 放在哪个目录（一般就是当前工作区目录）", required = true)
            string("id", "模块 id：只允许字母数字和 . _ -；它也是刷入后 /data/adb/modules/ 下的目录名", required = true)
            string("name", "显示名称，不给就用 id")
            string("version", "版本名，如 v1.0")
            integer("versionCode", "版本号（整数，Magisk 用它比大小）")
            string("author", "作者")
            string("description", "描述：Magisk 模块列表里显示这一行，写清这个模块给谁用、做什么")
            string("flavour", "shell（默认，纯脚本）或 zygisk（额外给 native 源码）")
            string("out", "输出 zip 路径。不给就写成 <dir>/<id>.zip")
        },
        returns = "输出 zip 路径、条目清单、接下来该改哪个文件",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val dir = File(a.requireStr("dir", "给我一个目录，骨架 zip 放在那里"))
        val id = a.requireStr("id", "给我模块 id（字母数字和 . _ -）")

        val flavour = when (val raw = a.str("flavour")?.trim()?.lowercase()) {
            null, "" , "shell" -> ModuleSkeletonSpec.Flavour.SHELL
            "zygisk" -> ModuleSkeletonSpec.Flavour.ZYGISK
            else -> return Results.fail(
                "BAD_FLAVOUR",
                "flavour 只认 shell 和 zygisk，收到「$raw」",
                "纯脚本（改属性、开机跑命令、按包名动数据）用 shell；要往应用进程里注入代码才用 zygisk",
            )
        }

        val out = a.str("out")?.let { File(it) } ?: File(dir, "$id.zip")
        if (out.exists()) {
            return Results.fail(
                "EXISTS",
                "${out.name} 已经存在了",
                "换一个 id，或者给 out 指定别的路径 ——「新建」不该悄悄盖掉一个现有的模块",
            )
        }

        val spec = ModuleSkeletonSpec(
            id = id,
            name = a.str("name") ?: id,
            version = a.str("version") ?: "v1.0",
            versionCode = a.int("versionCode", 1),
            author = a.str("author").orEmpty(),
            description = a.str("description").orEmpty(),
            flavour = flavour,
        )

        return runCatching { ModuleScaffold.write(spec, out) }.fold(
            onSuccess = { names ->
                val next = if (flavour == ModuleSkeletonSpec.Flavour.ZYGISK) {
                    "接着改 jni/module.cpp 里的 kTargetProcess（目标软件的进程名，一般就是包名），" +
                        "然后把 zygisk.hpp 放进 jni/ 编出 zygisk/<abi>.so —— **在编出来之前这个包刷了也不会生效**"
                } else {
                    "接着用 module.write_text 改 service.sh —— 那里是模块真正干活的地方（每次开机执行一次）"
                }
                Results.ok(
                    "已生成模块骨架：${out.absolutePath}\n" +
                        "条目：${names.joinToString(", ")}\n$next",
                    Results.json(
                        "out" to out.absolutePath,
                        "id" to id,
                        "flavour" to flavour.name.lowercase(),
                        "entries" to names,
                    ),
                )
            },
            onFailure = { e ->
                Results.fail(
                    "CREATE_FAILED",
                    "建不了模块骨架：${e.message}",
                    "id 只允许字母、数字和 . _ -（不能有空格、斜杠、中文）；versionCode 要是非负整数",
                )
            },
        )
    }
}

// ── 读取 ──────────────────────────────────────────────────────

object ModuleInspectTool : Tool {
    override val spec = ToolSpec(
        name = "module.inspect",
        description = "看一个 Magisk 模块 zip 的元数据与结构：id / 名称 / 版本 / 作者 / 描述、" +
            "zygisk 下有哪些 ABI、有哪些脚本、有没有停用或卸载标记。**改之前先看这个**。" +
            "会直接说出结构上的问题（比如缺当前设备用的 ABI —— 那种能装上但不生效）。",
        params = schema {
            string("zip", "模块 zip 的路径", required = true)
        },
        returns = "元数据、条目表、ABI 覆盖、脚本、问题清单",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val zip = File(a.requireStr("zip", "给我模块 zip 的路径"))

        // 先解析成可空结果，再决定是「不是模块」还是正常输出。
        // 不能在 runCatching 里直接返回 ToolResult —— 那个 lambda 会同时有
        // ToolResult 和 Triple 两种返回类型，退化成 Any，后面就没法解构了
        val parsed = runCatching {
            ModuleProject.open(zip).use { p ->
                val prop = p.prop ?: return@use null
                Triple(prop, p.layout, p.entryNames())
            }
        }.getOrElse { e ->
            return Results.fail(
                "NOT_A_MODULE",
                "打不开 ${zip.name}：${e.message}",
                "确认路径对不对；模块 zip 的条目要直接在根上（module.prop、zygisk/、service.sh）",
            )
        }

        if (parsed == null) return notAModule(zip)

        val (prop, layout, names) = parsed
        return Results.ok(
            buildString {
                append("模块「${prop.name}」(${prop.id}) 版本 ${prop.version} / ${prop.versionCode}\n")
                if (prop.author.isNotBlank()) append("作者：${prop.author}\n")
                if (prop.description.isNotBlank()) append("描述：${prop.description}\n")
                append("条目 ${names.size} 个")
                if (layout.isZygisk) {
                    append("；zygisk ABI：${layout.knownAbis.joinToString("/").ifEmpty { "（没有有效的）" }}")
                }
                if (layout.scripts.isNotEmpty()) append("；脚本：${layout.scripts.joinToString(", ")}")
                if (layout.hasSystemOverlay) append("；有 system/ overlay")
                layout.warnings.forEach { append("\n⚠ ").append(it) }
            },
            Results.json(
                "id" to prop.id,
                "version" to prop.version,
                "versionCode" to prop.versionCode,
                "abis" to layout.knownAbis,
                "warnings" to layout.warnings,
                "entries" to names,
            ),
        )
    }
}

object ModuleReadTextTool : Tool {
    override val spec = ToolSpec(
        name = "module.read_text",
        description = "读模块里一个文本条目（module.prop、service.sh、system.prop 这些）。" +
            "二进制条目（.so、.dex）读不出有意义的内容，会返回失败。",
        params = schema {
            string("zip", "模块 zip 的路径", required = true)
            string("path", "条目在包里的路径，如 service.sh", required = true)
        },
        returns = "文本内容",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val zip = File(a.requireStr("zip", "给我模块 zip 的路径"))
        val path = a.requireStr("path", "给我条目路径，如 service.sh")

        return runCatching {
            ModuleProject.open(zip).use { p ->
                p.readText(path) ?: throw IllegalStateException("包里没有「$path」")
            }
        }.fold(
            onSuccess = { text -> Results.ok(text, Results.json("path" to path, "text" to text)) },
            onFailure = { e ->
                Results.fail("READ_FAILED", "读不了 $path：${e.message}", "用 module.inspect 看这个包里到底有哪些条目")
            },
        )
    }
}

// ── 改动 ──────────────────────────────────────────────────────

object ModuleWriteTextTool : Tool {
    override val spec = ToolSpec(
        name = "module.write_text",
        description = "把模块里一个文本条目换成新内容（不存在就新建），结果写到 out。" +
            "改 service.sh 这类脚本就用它。**原始 zip 不会被改**。",
        params = schema {
            string("zip", "原始模块 zip 的路径", required = true)
            string("path", "要写的条目路径，如 service.sh", required = true)
            string("text", "新内容（整段替换）", required = true)
            string("out", "输出 zip 的路径。不给就写成同目录的 <原名>-edited.zip")
        },
        returns = "输出文件路径",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val zip = File(a.requireStr("zip", "给我模块 zip 的路径"))
        val path = a.requireStr("path", "给我要写的条目路径")
        val text = a.requireStr("text", "给我新的内容")
        val out = a.str("out")?.let { File(it) } ?: defaultOut(zip)

        return runCatching {
            ModuleProject.open(zip).use { p ->
                p.writeText(path, text)
                p.packageTo(out)
            }
            out
        }.fold(
            onSuccess = { f -> Results.ok("已写入 $path，结果在 ${f.absolutePath}", Results.json("out" to f.absolutePath)) },
            onFailure = { e -> Results.fail("WRITE_FAILED", "写不了 $path：${e.message}") },
        )
    }
}

object ModuleSetPropTool : Tool {
    override val spec = ToolSpec(
        name = "module.set_prop",
        description = "改 module.prop 里的字段（版本、版本号、名称、作者、描述），结果写到 out。" +
            "**不给的字段保持原样**；不认识的自定义键也会原样保留。" +
            "改 id 会被拒绝 —— Magisk 用目录名当模块标识，两者必须一致。",
        params = schema {
            string("zip", "原始模块 zip 的路径", required = true)
            string("version", "新的 version，如 v2.0")
            integer("versionCode", "新的 versionCode（整数，Magisk 用它比大小）")
            string("name", "新的名称")
            string("author", "新的作者")
            string("description", "新的描述")
            string("out", "输出 zip 的路径。不给就写成同目录的 <原名>-edited.zip")
        },
        returns = "输出文件路径、改后的字段",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val zip = File(a.requireStr("zip", "给我模块 zip 的路径"))
        val out = a.str("out")?.let { File(it) } ?: defaultOut(zip)

        return runCatching {
            ModuleProject.open(zip).use { p ->
                val old = p.prop ?: throw IllegalStateException("这个包里没有可解析的 module.prop")
                val next = old.copy(
                    version = a.str("version") ?: old.version,
                    versionCode = if (a.str("versionCode") != null) a.int("versionCode", old.versionCode) else old.versionCode,
                    name = a.str("name") ?: old.name,
                    author = a.str("author") ?: old.author,
                    description = a.str("description") ?: old.description,
                )
                if (next == old) throw IllegalStateException("没有给任何要改的字段")
                // 从 zip 打开，不知道安装时的目录名，所以传 null 不做那条检查
                p.updateProp(next, dirNameOrNull = null)
                p.packageTo(out)
                next
            } to out
        }.fold(
            onSuccess = { (prop, f) ->
                Results.ok(
                    "已改成 ${prop.version} / ${prop.versionCode}，结果在 ${f.absolutePath}",
                    Results.json("out" to f.absolutePath, "version" to prop.version, "versionCode" to prop.versionCode),
                )
            },
            onFailure = { e -> Results.fail("PROP_FAILED", "改不了 module.prop：${e.message}") },
        )
    }
}

object ModuleDeleteEntryTool : Tool {
    override val spec = ToolSpec(
        name = "module.delete_entry",
        description = "从模块里删掉一个条目，结果写到 out。删脚本、删 system.prop 这类用。" +
            "删 module.prop 会被拒绝（那就不是模块了）。",
        params = schema {
            string("zip", "原始模块 zip 的路径", required = true)
            string("path", "要删的条目路径", required = true)
            string("out", "输出 zip 的路径")
        },
        returns = "输出文件路径",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val zip = File(a.requireStr("zip", "给我模块 zip 的路径"))
        val path = a.requireStr("path", "给我要删的条目路径")
        val out = a.str("out")?.let { File(it) } ?: defaultOut(zip)

        if (path == ModuleProject.ModulePropFile) {
            return Results.fail(
                "REFUSED",
                "不能删 module.prop —— 删了这就不是模块了，刷进去 Magisk 会忽略它",
                "要停用模块请用 module.set_enabled，要卸载请用 module.remove",
            )
        }

        return runCatching {
            ModuleProject.open(zip).use { p ->
                p.deleteEntry(path)
                p.packageTo(out)
            }
            out
        }.fold(
            onSuccess = { f -> Results.ok("已删 $path，结果在 ${f.absolutePath}", Results.json("out" to f.absolutePath)) },
            onFailure = { e -> Results.fail("DELETE_FAILED", "删不了 $path：${e.message}") },
        )
    }
}

// ── 设备操作（只有 Root 一档）────────────────────────────────

object ModuleListInstalledTool : Tool {
    override val spec = ToolSpec(
        name = "module.list_installed",
        description = "列出设备上已安装的 Magisk 模块 id。装模块前后各看一次就知道成没成。",
        params = schema { },
        returns = "模块 id 列表；通道不可用时给出原因",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val ch = ModuleChannels.current()
            ?: return Results.fail("NO_CHANNEL", "引擎层没注册模块通道（App 层未初始化）")
        if (!ch.available()) {
            return Results.fail(
                "CHANNEL_UNAVAILABLE",
                "模块通道「${ch.name}」现在不可用",
                "这些操作需要 root：Shizuku 给的是 shell 身份，改不了 /data/adb/modules，" +
                    "也调不了 Magisk 的 CLI。先在文件页授权 root",
            )
        }
        val list = ch.listInstalled()
        return Results.ok(
            if (list.isEmpty()) "设备上还没有已安装的模块" else "已安装 ${list.size} 个：${list.joinToString(", ")}",
            Results.json("modules" to list),
        )
    }
}

object ModuleInstallTool : Tool {
    override val spec = ToolSpec(
        name = "module.install",
        description = "把模块 zip 刷进设备（走 `magisk --install-module`，模块的 customize.sh " +
            "会在 Magisk 环境里执行）。**会改动设备**。大多数模块要重启或软重启 zygote 才生效。",
        params = schema {
            string("zip", "要刷的模块 zip 路径", required = true)
        },
        returns = "成功与否、Magisk 的输出",
        effect = Effect.DESTRUCTIVE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val zip = File(a.requireStr("zip", "给我要刷的模块 zip 路径"))
        if (!zip.isFile) return Results.fail("BAD_PATH", "找不到文件：${zip.absolutePath}")

        val ch = runCatching { ModuleChannels.require() }.getOrElse { e ->
            return Results.fail("CHANNEL_UNAVAILABLE", e.message ?: "模块通道不可用")
        }

        ctx.progress("刷入模块 ${zip.name}…")
        val r = ch.install(zip)
        return if (r.ok) {
            Results.ok(r.message, Results.json("zip" to zip.absolutePath))
        } else {
            Results.fail("INSTALL_FAILED", r.message, "用 module.inspect 再确认一次这个包的结构")
        }
    }
}

object ModuleSetEnabledTool : Tool {
    override val spec = ToolSpec(
        name = "module.set_enabled",
        description = "停用 / 启用一个已安装的模块（增删 disable 标记）。**下次重启后生效**。" +
            "停用不删文件，随时能改回来。",
        params = schema {
            string("id", "模块 id", required = true)
            boolean("enabled", "true = 启用，false = 停用", required = true)
        },
        returns = "结果说明",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val id = a.requireStr("id", "给我模块 id（用 module.list_installed 看）")
        val enabled = a.bool("enabled", true)

        val ch = runCatching { ModuleChannels.require() }.getOrElse { e ->
            return Results.fail("CHANNEL_UNAVAILABLE", e.message ?: "模块通道不可用")
        }
        val r = ch.setEnabled(id, enabled)
        return if (r.ok) Results.ok(r.message) else Results.fail("SET_ENABLED_FAILED", r.message)
    }
}

object ModuleRemoveTool : Tool {
    override val spec = ToolSpec(
        name = "module.remove",
        description = "把模块标记为**下次重启时卸载**。比直接删目录安全 —— 模块的 so 可能正被" +
            "映射进进程，直接删会留下半残状态。想反悔就在重启前删掉 /data/adb/modules/<id>/remove。",
        params = schema {
            string("id", "模块 id", required = true)
        },
        returns = "结果说明",
        effect = Effect.DESTRUCTIVE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val id = a.requireStr("id", "给我模块 id")
        val ch = runCatching { ModuleChannels.require() }.getOrElse { e ->
            return Results.fail("CHANNEL_UNAVAILABLE", e.message ?: "模块通道不可用")
        }
        val r = ch.scheduleRemove(id)
        return if (r.ok) Results.ok(r.message) else Results.fail("REMOVE_FAILED", r.message)
    }
}

object ModuleUninstallTool : Tool {
    override val spec = ToolSpec(
        name = "module.uninstall",
        description = "立即删除模块目录。**只对已停用的模块允许** —— 还在启用时它的 so 可能正被" +
            "映射进进程，删掉会让进程行为不可预期。正常情况下该用 module.remove（重启后自动清理）。",
        params = schema {
            string("id", "模块 id", required = true)
        },
        returns = "结果说明",
        effect = Effect.DESTRUCTIVE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val id = a.requireStr("id", "给我模块 id")
        val ch = runCatching { ModuleChannels.require() }.getOrElse { e ->
            return Results.fail("CHANNEL_UNAVAILABLE", e.message ?: "模块通道不可用")
        }
        val r = ch.uninstallNow(id)
        return if (r.ok) Results.ok(r.message) else Results.fail("UNINSTALL_FAILED", r.message)
    }
}

object ModuleRestartZygoteTool : Tool {
    override val spec = ToolSpec(
        name = "module.zygote_restart",
        description = "软重启 zygote，让新装的 Zygisk 模块生效（不用整机重启）。" +
            "**会影响所有正在运行的应用**：进程全部重建，没保存的东西会丢。" +
            "只有在模块确实需要它、且用户明确同意时才调。",
        params = schema { },
        returns = "结果说明",
        effect = Effect.DESTRUCTIVE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val ch = runCatching { ModuleChannels.require() }.getOrElse { e ->
            return Results.fail("CHANNEL_UNAVAILABLE", e.message ?: "模块通道不可用")
        }
        val r = ch.restartZygote()
        return if (r.ok) Results.ok(r.message) else Results.fail("RESTART_FAILED", r.message)
    }
}
