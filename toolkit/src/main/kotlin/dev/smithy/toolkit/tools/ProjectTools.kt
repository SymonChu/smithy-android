package dev.smithy.toolkit.tools

import dev.smithy.engine.ApkReport
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

/** 工具的临时落盘目录（超长输出写到这里，把路径告回模型）。 */
internal fun toolSink(name: String): File =
    File(File(System.getProperty("java.io.tmpdir"), "smithy-tool-out").apply { mkdirs() }, name.replace('/', '_'))

/** 当前工作区状态：打开了哪个包、攒了多少改动。 */
object WorkspaceStateTool : Tool {
    override val spec = ToolSpec(
        name = "workspace.state",
        description = "看当前工作区状态：打开的哪个包、攒了多少条未落盘的改动。" +
            "改动都在覆盖层里，重打包之前原包不会被改。开始任何改动前先看一眼，避免重复改。",
        params = schema { },
        returns = "workspaceId / 包名 / 应用名 / 改动条数",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val p = ctx.requireWorkspace()
        val patches = p.patches()
        return Results.ok(
            "当前工作区是 ${p.meta.packageName}（${p.meta.appLabel}），攒了 ${patches.size} 条改动未落盘",
            Results.json(
                "workspaceId" to p.id,
                "package" to p.meta.packageName,
                "appLabel" to p.meta.appLabel,
                "patchCount" to patches.size,
            ),
        )
    }
}

/** 包的全貌，一次拿完。 */
object ApkMetaTool : Tool {
    override val spec = ToolSpec(
        name = "apk.meta",
        description = "一次拿到包的全貌：包名、版本、SDK、体积、签名、权限数、组件数、DEX 汇总。" +
            "要「先了解这是个什么包」就用它，别一个字段一个字段地问。",
        params = schema { },
        returns = "基本信息 + 签名方案与指纹 + 权限清单 + 组件计数 + 每个 dex 的类/方法数",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val m = ctx.requireWorkspace().meta
        val pending = m.dexStats.filter { it.methods >= 65_536 * 0.95 }.map { it.name }

        val sign = m.signatures.joinToString("、") { "v${it.scheme}" }.ifEmpty { "无" }
        val text = buildString {
            appendLine("${m.appLabel}｜${m.packageName}｜${m.versionName}(${m.versionCode})")
            appendLine("minSdk ${m.minSdk} / targetSdk ${m.targetSdk}｜${ApkReport.humanSize(m.sizeBytes)}｜${if (m.isSplit) "split 包" else "单包"}")
            appendLine("签名：$sign")
            appendLine("权限 ${m.permissions.size} 项｜组件 ${m.components.size} 个｜DEX ${m.dexStats.size} 个")
            appendLine("DEX 合计：${m.dexStats.sumOf { it.classes }} 类 / ${m.dexStats.sumOf { it.methods }} 方法")
            if (pending.isNotEmpty()) appendLine("⚠ ${pending.joinToString("、")} 接近 65536 方法上限，往这些 dex 加东西会打包失败")
            m.packerGuess?.let { appendLine("加固特征：${it.name}（置信度 ${"%.0f".format(it.confidence * 100)}%）") }
        }.trim()

        return Results.ok(
            text,
            Results.json(
                "package" to m.packageName,
                "appLabel" to m.appLabel,
                "versionName" to m.versionName,
                "versionCode" to m.versionCode,
                "minSdk" to m.minSdk,
                "targetSdk" to m.targetSdk,
                "sizeBytes" to m.sizeBytes,
                "signatureSchemes" to m.signatures.map { it.scheme },
                "sha256" to m.signatures.firstOrNull()?.sha256,
                "permissionCount" to m.permissions.size,
                "componentCount" to m.components.size,
                "dexCount" to m.dexStats.size,
                "nearMethodLimit" to pending,
            ),
        )
    }
}

/** 签名全貌。 */
object ApkSignaturesTool : Tool {
    override val spec = ToolSpec(
        name = "apk.signatures",
        description = "看签名细节：每个方案的证书主体、颁发者、SHA-256/SHA-1/MD5 指纹，" +
            "以及是不是 debug 证书。判断「这是不是原开发者签的」「是不是重签过」用得上。",
        params = schema { },
        returns = "每个签名方案的证书信息",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val sigs = ctx.requireWorkspace().meta.signatures
        if (sigs.isEmpty()) {
            return Results.ok(
                "这个包没有有效签名 —— 它装不上去，除非设备关了签名校验。",
                Results.json("count" to 0),
            )
        }
        val text = sigs.joinToString("\n\n") { s ->
            """
            |v${s.scheme}（${if (s.isDebug) "Debug 证书" else "正式证书"}）
            |  主体：${s.subject}
            |  颁发：${s.issuer}
            |  SHA-256：${s.sha256}
            |  SHA-1：${s.sha1}
            |  MD5：${s.md5}
            """.trimMargin()
        }
        return Results.ok(text, Results.json("count" to sigs.size, "schemes" to sigs.map { it.scheme }))
    }
}

/** 权限清单。 */
object ApkPermissionsTool : Tool {
    override val spec = ToolSpec(
        name = "apk.permissions",
        description = "列出声明的权限。高危权限会单独标出来 —— 判断一个包「要那么多权限干嘛」时看这个。",
        params = schema { },
        returns = "权限清单（高危的带标记）",
        effect = Effect.READ,
    )

    private val DANGEROUS = setOf(
        "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
        "android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE",
        "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.PACKAGE_USAGE_STATS",
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val perms = ctx.requireWorkspace().meta.permissions.sorted()
        val lines = perms.map { if (it in DANGEROUS) "$it  ⚠ 高危" else it }
        return Results.list(lines, perms.size, perms.size, emptyHint = "这个包没声明任何权限")
    }
}

/** 组件清单。 */
object ApkComponentsTool : Tool {
    override val spec = ToolSpec(
        name = "apk.components",
        description = "列出四大组件，并标出哪些是导出的（exported）。" +
            "导出的组件是外部能直接唤起的面，分析攻击面、找入口点都看它。",
        params = schema {
            string("kind", "只看某一类：ACTIVITY / SERVICE / RECEIVER / PROVIDER")
        },
        returns = "组件清单（导出的带标记）",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val all = ctx.requireWorkspace().meta.components
        val want = a.str("kind")?.uppercase()
        val list = if (want == null) all else all.filter { it.kind.name.equals(want, ignoreCase = true) }

        if (list.isEmpty()) {
            return Results.ok(
                if (want == null) "这个包没有解析到组件。" else "没有 ${want} 类型的组件。",
                Results.json("count" to 0),
            )
        }
        val lines = list.sortedBy { it.name }.map {
            "${it.kind.name}  ${it.name}${if (it.exported) "  — 导出" else ""}"
        }
        return Results.list(lines, list.size, list.size)
    }
}

/** 导出 Markdown 分析报告。 */
object ReportExportTool : Tool {
    override val spec = ToolSpec(
        name = "report.export",
        description = "生成完整的 Markdown 分析报告（基本信息 / 签名 / 权限 / 组件 / DEX 五节）。" +
            "用户要「一份报告」「分析结果」时就给它，而不是把前面几次工具调用的结果拼一拼。",
        params = schema { },
        returns = "Markdown 文本（超长会截断并把完整版写到文件）",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val p = ctx.requireWorkspace()
        val md = ApkReport.toMarkdown(p.meta, p.list().size, p.meta.sourcePath.substringAfterLast('/'))
        return Results.truncate(md, sink = toolSink("report-${p.meta.packageName}.md"))
    }
}

/** 列包内条目。 */
object EntryListTool : Tool {
    override val spec = ToolSpec(
        name = "entry.list",
        description = "列出包里的文件条目。prefix 是**路径前缀**语义（如 res/、lib/、assets/），" +
            "不是子串匹配 —— 想按目录看就用它。",
        params = schema {
            string("prefix", "路径前缀，如 res/ 或 lib/。不给就列全部")
            integer("limit", "最多返回多少条（默认 200）", min = 1, max = 2000)
        },
        returns = "条目路径、原始大小、压缩后大小",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val limit = a.int("limit", 200)
        val prefix = a.str("prefix")
        val all = ctx.requireWorkspace().list(prefix).filter { !it.isDirectory }
        val shown = all.take(limit)
        val lines = shown.map { "${it.path}  ${it.size}B" }
        return Results.list(lines, all.size, limit, emptyHint = "没有匹配「${prefix ?: "全部"}」的条目")
    }
}

/** 读条目内容。 */
object EntryReadTool : Tool {
    override val spec = ToolSpec(
        name = "entry.read",
        description = "读一个文件条目的内容（当文本读）。二进制条目（图片、dex 等）会明确告诉你读不了 —— " +
            "那些要用对应的工具（dex 用 dex.search，图片用 entry.list 看大小）。",
        params = schema {
            string("path", "条目路径，如 META-INF/MANIFEST.MF 或 assets/config.json", required = true)
        },
        returns = "文本内容（超长会截断并写文件）",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val path = ArgReader(args).requireStr("path", "给出要读的条目路径")
        val p = ctx.requireWorkspace()
        val bytes = p.readEntry(path).use { it.readBytes() }

        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        val readable = text != null && !text.contains('\uFFFD') &&
            text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } < (text.length / 50).coerceAtLeast(1)

        if (!readable) {
            return Results.ok(
                "$path 是二进制内容（${bytes.size} 字节），不能当文本读。" +
                    if (path.endsWith(".dex")) " 要看它的代码请用 dex.search 或 jadx.decompile_class。" else "",
                Results.json("path" to path, "binary" to true, "size" to bytes.size),
            )
        }
        return Results.truncate(text!!, sink = toolSink(path))
    }
}

/** 替换条目内容（文本）。 */
object EntryWriteTool : Tool {
    override val spec = ToolSpec(
        name = "entry.write",
        description = "把某个条目的内容替换成给定文本。只能写文本内容 —— " +
            "二进制（图片等）改不了，那需要真正的图片处理。新条目也可以用它创建。",
        params = schema {
            string("path", "要写的条目路径", required = true)
            string("text", "新的文本内容（会按 UTF-8 写入）", required = true)
        },
        returns = "改动记录",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val path = a.requireStr("path", "给出条目路径")
        val text = a.requireStr("text", "给出要写入的内容")
        val rec = ctx.requireWorkspace().writeEntry(path, text.byteInputStream())
        return Results.ok(
            "已替换 $path（${text.length} 字符）。改动在覆盖层里，重打包后才生效。",
            Results.json("target" to rec.target, "kind" to rec.kind.name),
        )
    }
}

/** 删除条目。 */
object EntryDeleteTool : Tool {
    override val spec = ToolSpec(
        name = "entry.delete",
        description = "从包里删掉一个条目。会二次确认；而且删除只是记在改动里，" +
            "重打包时才真正跳过它 —— 打包前随时能撤销。",
        params = schema {
            string("path", "要删除的条目路径", required = true)
        },
        returns = "改动记录",
        effect = Effect.DESTRUCTIVE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val path = ArgReader(args).requireStr("path", "给出要删除的条目路径")
        val rec = ctx.requireWorkspace().deleteEntry(path)
        return Results.ok(
            "已标记删除 $path。重打包后才真正生效；在此之前可以用 patch.revert 撤销。",
            Results.json("target" to rec.target, "patchId" to rec.id),
        )
    }
}

/** 列改动。 */
object PatchListTool : Tool {
    override val spec = ToolSpec(
        name = "patch.list",
        description = "列出当前攒下的所有改动，每条带 id。要回退某条改动就先看这里拿 id。",
        params = schema { },
        returns = "改动清单（id / 类型 / 目标 / 说明）",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val patches = ctx.requireWorkspace().patches()
        if (patches.isEmpty()) return Results.ok("还没有任何改动。", Results.json("count" to 0))

        val lines = patches.map { "${it.id}  ${it.kind.name}  ${it.target}  ${it.note ?: ""}" }
        return Results.list(lines, patches.size, patches.size)
    }
}

/** 回退一条改动。 */
object PatchRevertTool : Tool {
    override val spec = ToolSpec(
        name = "patch.revert",
        description = "撤销一条改动（用 patch.list 拿到的 id）。只影响这一条，其他改动不受影响。",
        params = schema {
            string("patchId", "要撤销的改动 id", required = true)
        },
        returns = "剩余改动数",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val id = a.requireStr("patchId", "先用 patch.list 看有哪些 id")
        val p = ctx.requireWorkspace()
        val before = p.patches().size
        runCatching { p.revert(id) }.getOrElse {
            return Results.fail(
                "NO_SUCH_PATCH",
                "没有 id 为 $id 的改动",
                "用 patch.list 看当前有哪些改动 id",
            )
        }
        return Results.ok("已撤销 $id，现在还剩 ${p.patches().size} 条改动", Results.json("remaining" to p.patches().size, "before" to before))
    }
}
