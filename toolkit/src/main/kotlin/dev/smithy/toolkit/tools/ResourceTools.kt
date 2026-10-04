package dev.smithy.toolkit.tools

import dev.smithy.engine.ManifestField
import dev.smithy.engine.ReplaceScope
import dev.smithy.engine.StringReplacement
import dev.smithy.toolkit.ArgReader
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.Results
import dev.smithy.toolkit.Tool
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.ToolResult
import dev.smithy.toolkit.ToolSpec
import dev.smithy.toolkit.schema
import kotlinx.serialization.json.JsonObject

/** 列资源表条目。 */
object ArscListTool : Tool {
    override val spec = ToolSpec(
        name = "arsc.list",
        description = "看资源表。按类型（string / drawable / mipmap / color / layout…）和关键词过滤，" +
            "filter 同时匹配资源名与值。**找应用名、界面文案先用它** —— 大部分文案在这里，" +
            "比去 dex 里搜快得多。",
        params = schema {
            string("type", "资源类型，如 string / drawable / mipmap / color / layout / xml")
            string("filter", "关键词，同时匹配资源名和值")
            integer("limit", "最多返回多少条（默认 100，上限 500）", min = 1, max = 500)
        },
        returns = "资源清单：@类型/名字 = 值",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val limit = a.int("limit", 100).coerceIn(1, 500)
        val type = a.str("type")
        val filter = a.str("filter")

        val all = ctx.requireWorkspace().resources(type, filter)
        val shown = all.take(limit)
        val lines = shown.map { r ->
            "${r.resName} = ${r.value ?: if (r.isComplex) "（复杂条目）" else ""}"
        }
        return Results.list(
            lines,
            all.size,
            limit,
            emptyHint = "没找到匹配的资源" +
                (if (type != null) "（类型限制在 $type）" else "") +
                (if (filter != null) "（关键词 $filter）" else "") +
                "。去掉 type 或换个关键词试试",
        )
    }
}

/** 改单条资源。 */
object ArscSetTool : Tool {
    override val spec = ToolSpec(
        name = "arsc.set",
        description = "改一条资源的值，资源名写成 @类型/名字 的形式（如 @string/app_name）。" +
            "批量改文案请用 arsc.replace_string，那个一次能改很多条。",
        params = schema {
            string("resName", "资源名，如 @string/app_name", required = true)
            string("value", "新的值", required = true)
        },
        returns = "改动记录",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val resName = a.requireStr("resName", "资源名要写成 @string/app_name 这种形式")
        val value = a.requireStr("value", "给出新的值")

        if (!resName.startsWith("@")) {
            return Results.fail(
                "BAD_RES_NAME",
                "资源名「$resName」不是 @类型/名字 的形式",
                "先 arsc.list 看这个资源叫什么，再按 @string/xxx 的形式传",
            )
        }

        return runCatching { ctx.requireWorkspace().setResource(resName, value) }.fold(
            onSuccess = { rec ->
                Results.ok("已把 $resName 改成「$value」", Results.json("patchId" to rec.id, "target" to rec.target))
            },
            onFailure = { e ->
                Results.fail(
                    "RES_NOT_FOUND",
                    "改不了 $resName：${e.message}",
                    "用 arsc.list 确认资源名与类型（type 参数别落下）",
                )
            },
        )
    }
}

/**
 * 批量改文案。
 *
 * 规则用「每行一条 旧=新」的文本而不是 JSON 数组 —— 模型写多行文本比写嵌套 JSON 稳得多，
 * 而且这个格式人对人也直观（UI 里的批量替换用的是同一个格式）。
 */
object ArscReplaceStringTool : Tool {
    override val spec = ToolSpec(
        name = "arsc.replace_string",
        description = "批量改文案（资源表里的字符串）。**这是改文案的正路** —— 一次调用可以带很多条规则，" +
            "资源表只序列化一次，200 条约 300 毫秒。规则格式：每行一条「旧值=新值」，以 # 开头的行当注释。" +
            "硬编码在代码里的文案不在这里，那要用 dex.replace_string。",
        params = schema {
            string(
                "rules",
                "替换规则，每行一条「旧值=新值」。例如：\n确定=OK\n取消=Cancel",
                required = true,
            )
            boolean("includeDex", "是否连 dex 里的硬编码文案一起改（会慢很多，默认不改）")
        },
        returns = "改动记录（命中多少条）",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val raw = a.requireStr("rules", "给出「旧值=新值」格式的规则，每行一条")

        val pairs = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else StringReplacement(line.substring(0, i), line.substring(i + 1))
            }

        if (pairs.isEmpty()) {
            return Results.fail(
                "BAD_RULES",
                "一条有效规则都没解析出来",
                "格式是每行一条「旧值=新值」，等号两边都要有内容。例如：确定=OK",
            )
        }
        val bad = raw.lines().count { it.isNotBlank() && !it.startsWith("#") && !it.contains('=') }

        val t0 = System.currentTimeMillis()
        val recs = ctx.requireWorkspace().replaceStrings(
            pairs,
            if (a.bool("includeDex", false)) ReplaceScope.BOTH else ReplaceScope.ARSC,
        )
        val cost = System.currentTimeMillis() - t0

        if (recs.isEmpty()) {
            return Results.ok(
                "解析出 ${pairs.size} 条规则，但资源表里一条都没命中。" +
                    "先 arsc.list 确认原值的真实写法（可能带格式符或前后空格）。",
                Results.json("rules" to pairs.size, "hits" to 0),
            )
        }
        return Results.ok(
            "已替换：${pairs.size} 条规则命中，改了 ${recs.size} 个条目，耗时 ${cost}ms。" +
                if (bad > 0) "（另有 $bad 行没有等号，被忽略了）" else "",
            Results.json(
                "rules" to pairs.size,
                "targets" to recs.map { it.target },
                "costMs" to cost,
                "malformedLines" to bad,
            ),
        )
    }
}

/** 改清单字段。 */
object ManifestSetTool : Tool {
    override val spec = ToolSpec(
        name = "manifest.set",
        description = "改清单里的常用字段：应用名（APP_LABEL）、包名（PACKAGE_NAME）、" +
            "版本名（VERSION_NAME）、版本码（VERSION_CODE）、调试开关（DEBUGGABLE）。" +
            "**改包名要慎重**：它会让系统把这个包当成另一个应用（不会覆盖原应用），" +
            "而且组件名、权限、provider authority 都会受影响。",
        params = schema {
            string(
                "field",
                "改哪个字段",
                required = true,
                choices = listOf("APP_LABEL", "PACKAGE_NAME", "VERSION_NAME", "VERSION_CODE", "DEBUGGABLE"),
            )
            string("value", "新值。DEBUGGABLE 传 true/false", required = true)
        },
        returns = "改动记录",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val field = a.enum("field", ManifestField.entries.toTypedArray(), ManifestField.APP_LABEL)
        val value = a.requireStr("value", "给出新值")

        if (field == ManifestField.DEBUGGABLE && value.lowercase() !in setOf("true", "false")) {
            return Results.fail("BAD_VALUE", "DEBUGGABLE 只能是 true 或 false，收到「$value」", "改成 true 或 false")
        }

        return runCatching { ctx.requireWorkspace().setManifestField(field, value) }.fold(
            onSuccess = { rec ->
                val warn = if (field == ManifestField.PACKAGE_NAME) {
                    " 注意：装上去会是一个新应用，不会覆盖原来那个。"
                } else {
                    ""
                }
                Results.ok("已把 $field 改成「$value」。$warn", Results.json("patchId" to rec.id, "target" to rec.target))
            },
            onFailure = { e ->
                Results.fail("MANIFEST_SET_FAILED", "改 $field 失败：${e.message}", "确认值合法（版本码要是整数）")
            },
        )
    }
}

/** 解码二进制 XML。 */
object AxmlDecodeTool : Tool {
    override val spec = ToolSpec(
        name = "axml.decode",
        description = "把包里的二进制 XML 解码成可读文本。清单（AndroidManifest.xml）、布局、" +
            "各种配置 xml 都能解。**改 xml 之前先用它看清楚结构**。",
        params = schema {
            string("path", "xml 条目路径，如 AndroidManifest.xml 或 res/layout/main.xml", required = true)
        },
        returns = "XML 文本（超长截断并写文件）",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val path = ArgReader(args).requireStr("path", "给出 xml 条目路径")
        return runCatching { ctx.requireWorkspace().readXml(path) }.fold(
            onSuccess = { Results.truncate(it, sink = toolSink(path)) },
            onFailure = { e ->
                Results.fail(
                    "AXML_DECODE_FAILED",
                    "解不了 $path：${e.message}",
                    "确认路径写对了。普通文本文件用 entry.read 读",
                )
            },
        )
    }
}

/** 改二进制 XML 的属性。 */
object AxmlPatchTool : Tool {
    override val spec = ToolSpec(
        name = "axml.patch",
        description = "改 xml 里某个元素的属性。elementPath 从根元素的**直接子级**写起 —— " +
            "清单的根是 manifest，所以要写 manifest/application；同名元素取第二个用 activity[1]。" +
            "属性不存在会新建。常用清单字段（应用名/版本）请用 manifest.set，那个更稳妥。",
        params = schema {
            string("path", "xml 条目路径", required = true)
            string("elementPath", "元素路径，如 manifest/application 或 manifest/application/activity[1]", required = true)
            string("attr", "属性名，可带前缀（android:debuggable）", required = true)
            string("value", "新值", required = true)
        },
        returns = "改动记录",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val path = a.requireStr("path", "给出 xml 条目路径")
        val elementPath = a.requireStr("elementPath", "给出元素路径，如 manifest/application")
        val attr = a.requireStr("attr", "给出属性名")
        val value = a.requireStr("value", "给出新值")

        return runCatching { ctx.requireWorkspace().patchXml(path, elementPath, attr, value) }.fold(
            onSuccess = { rec ->
                Results.ok("已把 $path 里 $elementPath 的 $attr 改成「$value」", Results.json("patchId" to rec.id))
            },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val hint = when {
                    msg.contains("找不到元素路径") ->
                        "路径从根元素的直接子级开始写。先用 axml.decode 看结构，确认元素名和层级"
                    msg.contains("一个字节都没变") ->
                        "那个属性本来就是「$value」。要么换个值，要么你其实不用改它"
                    else -> "先用 axml.decode 看这个 xml 的真实结构"
                }
                Results.fail("AXML_PATCH_FAILED", msg.ifBlank { "改 $path 失败" }, hint)
            },
        )
    }
}
