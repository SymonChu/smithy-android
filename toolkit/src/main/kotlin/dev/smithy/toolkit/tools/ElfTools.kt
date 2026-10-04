package dev.smithy.toolkit.tools

import kotlinx.serialization.json.JsonObject
import dev.smithy.fs.Elf
import dev.smithy.fs.HexEdit
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
 * ELF（`.so`）的查看与字符串替换。
 *
 * **只做等长替换。** 变长会移动后面所有字节，而 ELF 里到处是按偏移的引用
 * （节头表、符号表、重定位、动态段）—— 改出来是个能骗过 `readelf`、加载时才崩的文件。
 * 所以变长请求会被明确拒绝，并指出正确路径是改源码重编。
 *
 * 另外**被按内容索引的节一律拒绝**（`.dynstr`、`.gnu.hash` 这些）：动态链接器按哈希表
 * 查符号名，改掉名字后哈希对不上，那个符号就再也找不到了 —— 等长也不安全。
 */
object ElfInspectTool : Tool {
    override val spec = ToolSpec(
        name = "elf.inspect",
        description = "看一个 ELF 文件（.so / 可执行文件）的架构、类型、入口地址和节表。" +
            "改串之前先用它确认这是不是 ELF、以及要找的串落在哪个节里。",
        params = schema {
            string("path", "ELF 文件路径（模块里的 so 要先取出来）", required = true)
        },
        returns = "架构 / 位数 / 字节序 / 类型 / 节表",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val f = File(a.requireStr("path", "给我 ELF 文件的路径"))
        if (!f.isFile) return Results.fail("BAD_PATH", "找不到文件：${f.absolutePath}")

        val bytes = f.readBytes()
        val elf = Elf.parse(bytes) ?: return Results.fail(
            "NOT_ELF",
            "${f.name} 不是 ELF（头部不是 7f 45 4c 46，或者结构不完整）",
            "如果这是个压缩过的文件，先解压；如果这是个 apk 里的 dex，那该走 dex 那条线",
        )

        val named = elf.sections.filter { it.name.isNotEmpty() }
        val text = buildString {
            append("${f.name}：${elf.abiName}，${if (elf.is64Bit) "64" else "32"} 位，")
            append(if (elf.littleEndian) "小端" else "大端")
            append("，${elf.typeName}\n")
            append("入口 0x${elf.entryPoint.toString(16)}；共 ${elf.sections.size} 个节")
            if (named.isNotEmpty()) {
                append("\n节表：").append(named.take(24).joinToString(", ") { it.name })
                if (named.size > 24) append(" …（共 ${named.size} 个）")
            }
        }
        return Results.ok(
            text,
            Results.json(
                "abi" to elf.abiName,
                "bits" to if (elf.is64Bit) 64 else 32,
                "littleEndian" to elf.littleEndian,
                "entry" to elf.entryPoint,
                "sections" to named.map { it.name },
            ),
        )
    }
}

object ElfStringsTool : Tool {
    override val spec = ToolSpec(
        name = "elf.strings",
        description = "扫出 ELF 里的可打印字符串（像 strings 命令），带**文件偏移**——" +
            "这个偏移就是 elf.patch_string 要用的。可以用 filter 只留含某个词的。" +
            "默认最短 4 字符：再短的东西在二进制里到处都是，列出来只会把有用的淹掉。",
        params = schema {
            string("path", "ELF 文件路径", required = true)
            string("filter", "只留包含这个词的字符串（大小写敏感）")
            integer("minLength", "最短长度，默认 4", min = 2, max = 64)
            integer("limit", "最多返回多少个，默认 200", min = 1, max = 2000)
        },
        returns = "偏移 + 字符串的列表",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val f = File(a.requireStr("path", "给我 ELF 文件的路径"))
        if (!f.isFile) return Results.fail("BAD_PATH", "找不到文件：${f.absolutePath}")

        val minLen = a.int("minLength", 4)
        val limit = a.int("limit", 200)
        val needle = a.str("filter")

        val bytes = f.readBytes()
        val all = Elf.scanStrings(bytes, minLength = minLen, limit = 20000)
        val hit = if (needle == null) all else all.filter { (_, s) -> s.contains(needle) }
        val shown = hit.take(limit)

        if (shown.isEmpty()) {
            return Results.fail(
                "NO_MATCH",
                if (needle == null) "没扫到长度 ≥ $minLen 的可打印字符串" else "没有含「$needle」的字符串",
                "把 minLength 放小，或者换个关键词；也确认这不是压缩过的文件",
            )
        }

        val text = buildString {
            append("扫到 ${hit.size} 条")
            if (hit.size > shown.size) append("（只列前 ${shown.size} 条）")
            append("：\n")
            shown.forEach { (off, s) ->
                append(HexEdit.formatOffset(off)).append("  ").append(s.take(120)).append('\n')
            }
        }
        return Results.ok(
            text,
            Results.json("count" to hit.size, "items" to shown.map { Results.json("offset" to it.first, "text" to it.second) }),
        )
    }
}

object ElfPatchStringTool : Tool {
    override val spec = ToolSpec(
        name = "elf.patch_string",
        description = "把 ELF 里的一处字符串换成**等长**的新串，结果写到 out。" +
            "**变长一律拒绝**：插入字节会让后面所有偏移引用错位，改出来能骗过 readelf、" +
            "加载时才崩。要变长只能改源码重编。" +
            "被按内容索引的节（.dynstr / .gnu.hash / .strtab 等）也一律拒绝 —— " +
            "那些节等长也不安全（哈希表按内容查符号名）。" +
            "同一句话出现多次时要么用 offset 指明，要么先改成唯一的说法。",
        params = schema {
            string("path", "原 ELF 文件路径", required = true)
            string("old", "要被替换的原文（必须原样匹配）", required = true)
            string("new", "新串，**字节数必须和 old 一样**", required = true)
            string("offset", "明确指定改哪一处（十六进制偏移，如 1a2b）。多处命中时用它")
            string("out", "输出文件路径。不给就写成同目录的 <原名>-patched")
        },
        returns = "输出路径、实际改的偏移",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val f = File(a.requireStr("path", "给我 ELF 文件的路径"))
        if (!f.isFile) return Results.fail("BAD_PATH", "找不到文件：${f.absolutePath}")
        val old = a.requireStr("old", "给我要被替换的原串")
        val new = a.requireStr("new", "给我新串（长度要和原串一样）")

        val offsetArg = a.str("offset")
        val explicit = offsetArg?.let { t ->
            HexEdit.parseOffset(t) ?: return Results.fail(
                "BAD_OFFSET",
                "偏移「$t」看不懂。十六进制直接写 1a2b（不要加 0x 也行），十进制要写 0d100",
                "用 elf.strings 拿到的偏移就是十六进制，直接抄过来",
            )
        }

        val out = a.str("out")?.let { File(it) }
            ?: File(f.parentFile, f.name + "-patched")

        val bytes = f.readBytes()
        val check = Elf.checkPatch(bytes, old, new, explicitOffset = explicit, elf = Elf.parse(bytes))
        when (check) {
            is Elf.PatchCheck.Rejected -> return Results.fail(
                "PATCH_REJECTED",
                check.reason,
                "要改逻辑请走源码重编那条路（M6-B）；这里只做等长字符串替换",
            )
            is Elf.PatchCheck.Ok -> {
                val after = Elf.patch(bytes, check.offset, new)
                if (after.size != bytes.size) {
                    // 理论上到不了这里（overwrite 不改长度），但长度是这类操作的命根子，
                    // 多一道保险比事后排查便宜
                    return Results.fail("LENGTH_CHANGED", "替换后长度变了（${bytes.size} → ${after.size}），已放弃写入")
                }
                out.writeBytes(after)
                return Results.ok(
                    "已把「$old」改成「$new」（${new.toByteArray().size} 字节 @ ${HexEdit.formatOffset(check.offset)}），" +
                        "结果在 ${out.absolutePath}",
                    Results.json(
                        "out" to out.absolutePath,
                        "offset" to check.offset,
                        "bytes" to new.toByteArray().size,
                    ),
                )
            }
        }
    }
}
