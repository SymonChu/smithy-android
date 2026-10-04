package dev.smithy.toolkit.tools

import dev.smithy.engine.DexQuery
import dev.smithy.engine.StringReplacement
import dev.smithy.engine.ReplaceScope
import dev.smithy.toolkit.ArgReader
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.Results
import dev.smithy.toolkit.Tool
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.ToolResult
import dev.smithy.toolkit.ToolSpec
import dev.smithy.toolkit.schema
import kotlinx.serialization.json.JsonObject

/**
 * dex 搜索。
 *
 * **为什么把「搜类 / 搜方法 / 搜字段 / 搜字符串」合成一个工具**：它们的参数几乎一样，
 * 拆成四个只会让模型多一层选错的机会（搜类名却调了 search_string 是最常见的失误），
 * 还白占四份 schema 的 token。用一个 scope 参数区分更省事，也让模型更容易记住。
 */
object DexSearchTool : Tool {
    override val spec = ToolSpec(
        name = "dex.search",
        description = "在 dex 里搜索。scope 决定搜什么：STRING（字符串常量 —— 找硬编码文案、URL、密钥最常用）、" +
            "CLASS（类名）、METHOD（方法名或签名）、FIELD（字段名）。" +
            "**先搜字符串**，比直接读代码快得多。",
        params = schema {
            string("text", "要搜的内容", required = true)
            string("scope", "搜什么", choices = listOf("STRING", "CLASS", "METHOD", "FIELD"))
            boolean("regex", "是否把 text 当正则表达式")
            integer("limit", "最多返回多少条（默认 50，上限 200）", min = 1, max = 200)
            string("dex", "只搜某一个 dex 文件，如 classes2.dex")
        },
        returns = "命中清单：所在 dex / 类 / 方法 / 命中片段",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val text = a.requireStr("text", "给出要搜的内容")
        val scope = a.enum("scope", DexQuery.Scope.entries.toTypedArray(), DexQuery.Scope.STRING)
        val limit = a.int("limit", 50).coerceIn(1, 200)

        val hits = ctx.requireWorkspace().dexSearch(
            DexQuery(
                text = text,
                scope = scope,
                regex = a.bool("regex", false),
                limit = limit,
                dexName = a.str("dex"),
            ),
        )

        if (hits.isEmpty()) {
            return Results.ok(
                "没搜到「$text」（scope=$scope）。" +
                    when (scope) {
                        DexQuery.Scope.STRING -> "换个词，或者资源里的文案用 arsc.list 找。"
                        DexQuery.Scope.CLASS -> "类名是点分全限定名，试试只搜关键词。"
                        else -> "试试换 scope，或者只搜关键词。"
                    },
                Results.json("count" to 0),
            )
        }

        val lines = hits.map { h ->
            buildString {
                append(h.dexName).append("  ").append(h.className)
                h.methodSig?.let { append('#').append(it) }
                h.fieldName?.let { append('.').append(it) }
                append("  ").append(h.snippet.take(80))
            }
        }
        return Results.list(lines, hits.size, limit)
    }
}

/** 单类反编译成 Java。 */
object JadxDecompileTool : Tool {
    override val spec = ToolSpec(
        name = "jadx.decompile_class",
        description = "把一个类反编译成 Java 源码。**给人读**用这个（比 smali 好懂）；" +
            "要**改**代码请读 smali（smali.read_class），改完再 smali.patch。" +
            "第一次反编译一个 dex 要一两秒，之后同一个 dex 里的类很快。",
        params = schema {
            string("className", "类名（点分全限定，如 com.demo.MainActivity）", required = true)
        },
        returns = "Java 源码（超长截断并写文件）",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val name = ArgReader(args).requireStr("className", "给出点分全限定的类名")
        val code = ctx.requireWorkspace().decompileToJava(name)
        if (code.isBlank()) {
            return Results.fail(
                "DECOMPILE_EMPTY",
                "反编译 $name 得到空结果",
                "类名可能不对。用 dex.search（scope=CLASS）确认真实类名",
            )
        }
        return Results.truncate(code, sink = toolSink("$name.java"))
    }
}

/** 读整类的 smali。 */
object SmaliReadClassTool : Tool {
    override val spec = ToolSpec(
        name = "smali.read_class",
        description = "读一个类的 smali 源码。**要改代码就读它** —— smali 是机器可改的形式，" +
            "Java 反编译结果只适合阅读。整类可能很长，只关心某个方法就用 smali.read_method。",
        params = schema {
            string("className", "类名（点分全限定）", required = true)
        },
        returns = "smali 源码（超长截断并写文件）",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val name = ArgReader(args).requireStr("className", "给出点分全限定的类名")
        val smali = ctx.requireWorkspace().readSmali(name)
        return Results.truncate(smali, sink = toolSink("$name.smali"))
    }
}

/** 只读一个方法，省 token。 */
object SmaliReadMethodTool : Tool {
    override val spec = ToolSpec(
        name = "smali.read_method",
        description = "只读一个方法体的 smali，比读整类省得多。方法签名要写全，如 " +
            "`onCreate(Landroid/os/Bundle;)V`。不确定签名就先用 dex.search（scope=METHOD）找。",
        params = schema {
            string("className", "类名（点分全限定）", required = true)
            string("methodSig", "方法名与签名，如 onCreate(Landroid/os/Bundle;)V", required = true)
        },
        returns = "该方法的 smali",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val name = a.requireStr("className", "给出类名")
        val sig = a.requireStr("methodSig", "给出方法签名，如 onCreate(Landroid/os/Bundle;)V")
        val smali = ctx.requireWorkspace().readSmaliMethod(name, sig)
        if (smali.isBlank()) {
            return Results.fail(
                "METHOD_NOT_FOUND",
                "$name 里没有方法 $sig",
                "签名要写全参数与返回类型。用 dex.search（scope=METHOD）找准确签名",
            )
        }
        return Results.truncate(smali, sink = toolSink("$name#$sig.smali"))
    }
}

/**
 * 改 smali 逻辑。
 *
 * 与 [DexReplaceStringTool] 的分工要写进 description —— 否则模型会拿它去改文案，
 * 那代价是整 dex 往返（实测 396ms/次，而改字符串常量只要毫秒级）。
 */
object SmaliPatchTool : Tool {
    override val spec = ToolSpec(
        name = "smali.patch",
        description = "改 smali 指令：寄存器、跳转、常量、指令序列。**改字符串文案请用 dex.replace_string**，" +
            "那个快得多（只动常量池）。这里要整 dex 反汇编再汇编，一次几百毫秒。",
        params = schema {
            string("className", "类名（点分全限定）", required = true)
            string("pattern", "要替换的 smali 片段（要和源码里的写法完全一致，含缩进）", required = true)
            string("replacement", "替换成什么", required = true)
            string("methodSig", "限定在哪个方法内改，如 onCreate(Landroid/os/Bundle;)V。不给就整类找")
            boolean("regex", "pattern 是否当正则表达式")
        },
        returns = "改动记录",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val cls = a.requireStr("className", "给出类名")
        val pattern = a.requireStr("pattern", "给出要替换的 smali 片段")
        val replacement = a.requireStr("replacement", "给出替换内容")

        val rec = ctx.requireWorkspace().patchSmali(
            className = cls,
            methodSig = a.str("methodSig"),
            pattern = pattern,
            replacement = replacement,
            regex = a.bool("regex", false),
        )
        return Results.ok(
            "已改 $cls${a.str("methodSig")?.let { "#$it" } ?: ""}。改动在覆盖层里，重打包后才生效。",
            Results.json("patchId" to rec.id, "target" to rec.target, "kind" to rec.kind.name),
        )
    }
}

/** 改 dex 里的字符串常量。 */
object DexReplaceStringTool : Tool {
    override val spec = ToolSpec(
        name = "dex.replace_string",
        description = "改 dex 里的**硬编码**字符串常量（代码里直接写死的文案、URL、密钥）。" +
            "如果目标是资源里的文案（strings.xml），**请改用 arsc.replace_string** —— 快几十倍。" +
            "不确定文案在哪一层时，先用 dex.search（scope=STRING）确认它真的在 dex 里。",
        params = schema {
            string("from", "原文本", required = true)
            string("to", "新文本", required = true)
            boolean("regex", "把 from 当正则表达式")
        },
        returns = "改动记录（含命中了几个 dex）",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val from = a.requireStr("from", "给出原文本")
        val to = a.requireStr("to", "给出新文本")

        val recs = ctx.requireWorkspace().replaceStrings(
            listOf(StringReplacement(from, to)),
            ReplaceScope.DEX,
        )
        if (recs.isEmpty()) {
            return Results.ok(
                "dex 里没有「$from」这个字符串常量。它可能在资源表里 —— 用 arsc.list 搜一下。",
                Results.json("count" to 0),
            )
        }
        return Results.ok(
            "已替换，涉及 ${recs.size} 个 dex：${recs.map { it.target }}",
            Results.json("count" to recs.size, "targets" to recs.map { it.target }),
        )
    }
}
