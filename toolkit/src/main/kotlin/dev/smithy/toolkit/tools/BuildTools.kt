package dev.smithy.toolkit.tools

import dev.smithy.engine.InstallVia
import dev.smithy.engine.SignConfig
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
 * 打包链路的工具。
 *
 * 这层的设计取向：**尽量让模型少走几步**。装包这件事本来要「重打包 → 签名 → 装机」三步，
 * 每多一步就多一个「路径记错 / 顺序搞反」的失败点。所以 [ApkSignTool] 和 [ApkInstallTool]
 * 都支持「不给路径就自动把前面的步骤做掉」。
 */
object ApkRebuildTool : Tool {
    override val spec = ToolSpec(
        name = "apk.rebuild",
        description = "把当前攒下的所有改动重打包成一个新的 apk（还没签名）。" +
            "只是想知道改动结果、或者要自己签名时才单独调它 —— 大多数情况直接调 apk.sign，它会先把这一步做掉。",
        params = schema {
            boolean("full", "是否全量重打包（默认增量：只换改动过的条目，快得多）")
        },
        returns = "产出的 apk 路径与大小",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val full = a.bool("full", false)
        val p = ctx.requireWorkspace()

        if (p.patches().isEmpty()) {
            return Results.fail(
                "NO_CHANGES",
                "还没有任何改动，重打包出来会和原包一模一样",
                "先做点改动（改文案 / 改清单 / 换图标），或者直接告诉我你想改什么",
            )
        }

        val t0 = System.currentTimeMillis()
        val out = p.rebuild(incremental = !full)
        val cost = System.currentTimeMillis() - t0

        return Results.ok(
            "已重打包：${out.name}（${out.length() / 1024}KB，${cost}ms，" +
                if (full) "全量" else "增量" + "）。还没签名，装不上。",
            Results.json("path" to out.absolutePath, "size" to out.length(), "costMs" to cost),
        )
    }
}

/** 签名。不给路径就自动先重打包。 */
object ApkSignTool : Tool {
    override val spec = ToolSpec(
        name = "apk.sign",
        description = "给 apk 签名（v2 + v3）。**不给 path 就自动先重打包** —— 这是最常见的一步到位用法。" +
            "签名用的密钥是工具内置生成的，第一次会现生成一个，之后固定不变（所以反复改同一个包，" +
            "签名指纹是稳定的，能覆盖安装）。",
        params = schema {
            string("path", "要签名的 apk 路径。不给就自动先重打包")
            boolean("v1", "是否也带 v1（JAR）签名。默认否 —— 这个引擎的 v1 路径有已知问题")
        },
        returns = "签名后的 apk 路径、指纹、用了哪些方案",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val p = ctx.requireWorkspace()

        val unsigned: File = a.str("path")?.let { File(it) }
            ?.takeIf { it.isFile }
            ?: run {
                if (p.patches().isEmpty()) {
                    return Results.fail(
                        "NO_CHANGES",
                        "还没有任何改动，签出来的还是原包",
                        "先说要改什么，或者自己确认只是要重签一次",
                    )
                }
                p.rebuild()
            }

        val t0 = System.currentTimeMillis()
        val signed = p.sign(SignConfig(v1Enabled = a.bool("v1", false)))
        val cost = System.currentTimeMillis() - t0

        val verify = runCatching { p.verify(signed) }.getOrNull()
        return Results.ok(
            "已签名：${signed.name}（${signed.length() / 1024}KB，${cost}ms）" +
                verify?.let { "，验签${if (it.valid) "通过" else "**失败**"}，方案 ${it.schemes}" }.orEmpty() +
                "。这个包可以装了。",
            Results.json(
                "unsigned" to unsigned.absolutePath,
                "signed" to signed.absolutePath,
                "size" to signed.length(),
                "valid" to verify?.valid,
                "schemes" to verify?.schemes,
            ),
        )
    }
}

/** 验签。 */
object ApkVerifyTool : Tool {
    override val spec = ToolSpec(
        name = "apk.verify",
        description = "验一个 apk 的签名：有没有签、用了哪些方案、签名是否有效。" +
            "只是想确认「刚签的那个包是好包」时用。",
        params = schema {
            string("path", "要验的 apk 路径。不给就验当前工程最近签出的包")
        },
        returns = "是否有效、签名方案、详细信息",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val path = ArgReader(args).str("path")
            ?: return Results.fail(
                "NEEDS_PATH",
                "请给出要验的 apk 路径",
                "先 apk.sign 签一个（它会返回路径），再把那个路径传进来",
            )
        val f = File(path)
        if (!f.isFile) {
            return Results.fail("FILE_NOT_FOUND", "找不到 $path", "确认路径。签名工具返回的 path 是绝对路径，直接用那个")
        }
        val r = ctx.requireWorkspace().verify(f)
        return Results.ok(
            "${f.name}：${if (r.valid) "签名有效" else "签名无效"}，方案 ${r.schemes}" +
                if (r.messages.isNotEmpty()) "\n${r.messages.joinToString("\n")}" else "",
            Results.json("valid" to r.valid, "schemes" to r.schemes),
        )
    }
}

/** 装机。 */
object ApkInstallTool : Tool {
    override val spec = ToolSpec(
        name = "apk.install",
        description = "把包装到设备上。**不给 path 就自动打包 + 签名再装**。会二次确认。" +
            "装机通道会逐级尝试（Shizuku → Root → 系统安装器），最终用了哪一档会如实告诉你。" +
            "注意：如果设备上已有同包名但签名不同的版本，会装不上 —— 那需要先卸载原应用。",
        params = schema {
            string("path", "要装的 apk。不给就自动先打包签名")
            string("via", "指定装机通道（不指定就自动逐级降级）", choices = listOf("SHIZUKU", "ROOT", "INTENT"))
        },
        returns = "是否成功、实际用了哪个通道、失败原因",
        effect = Effect.DESTRUCTIVE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val p = ctx.requireWorkspace()

        val apk: File = a.str("path")?.let { File(it) }?.takeIf { it.isFile }
            ?: run {
                if (p.patches().isEmpty()) {
                    return Results.fail(
                        "NO_CHANGES",
                        "没有改动也没有指定要装的包",
                        "先说要改什么；或者明确告诉我你只是想装某个已有的包（把路径给我）",
                    )
                }
                p.sign(SignConfig(v1Enabled = false))
            }

        val via = a.str("via")?.uppercase()?.let { raw ->
            InstallVia.entries.firstOrNull { it.name == raw }
                ?: return Results.fail(
                    "BAD_VIA",
                    "via 只能是 SHIZUKU / ROOT / INTENT，收到「$raw」",
                    "不指定 via 就行，工具会自动逐级降级",
                )
        } ?: InstallVia.SHIZUKU

        val r = p.install(apk, via)
        return if (r.ok) {
            Results.ok(
                "已通过 ${r.via} 通道装好。" + (r.message?.let { " $it" } ?: ""),
                Results.json("via" to r.via.name, "apk" to apk.absolutePath),
            )
        } else {
            Results.fail(
                "INSTALL_FAILED",
                "装机失败（走了 ${r.via} 通道）：${r.message ?: "没有更多信息"}",
                hintsForInstall(r.message.orEmpty()),
            )
        }
    }

    /**
     * 装机失败的翻译。
     *
     * 系统的 INSTALL_FAILED_* 是给开发者看的，模型看到一串大写英文很难判断该改什么，
     * 而它给出的下一步建议又会直接影响用户操作 —— 所以在这里翻成人话 + 明确动作。
     */
    private fun hintsForInstall(raw: String): String = when {
        raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
            "设备上已有一个同包名但签名不同的版本（很可能是原开发者的）。先卸载它再装；" +
                "或者把包名也改掉（manifest.set 的 PACKAGE_NAME），那样是并存的新应用"
        raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
            "版本码比设备上那个低。用 manifest.set 的 VERSION_CODE 提高它，或用 -d 允许降级"
        raw.contains("INSTALL_PARSE_FAILED") ->
            "包结构被改坏了。回退最近的改动（patch.list / patch.revert）逐个排除"
        raw.contains("Shizuku") || raw.contains("未授权") ->
            "Shizuku 没就绪。可以指定 via=ROOT（有 Root 的话）或 via=INTENT（弹系统安装界面对话框，需要用户点确认）"
        raw.contains("未获得 Root") ->
            "没有 Root 授权。改用 via=INTENT —— 会弹系统安装界面，用户在屏幕上点确认即可"
        else -> "把上面这段原始错误给用户看，让他确认设备情况（有没有 Root/Shizuku、是不是同签名冲突）"
    }
}
