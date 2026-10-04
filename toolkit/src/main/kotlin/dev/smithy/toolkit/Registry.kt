package dev.smithy.toolkit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.smithy.toolkit.tools.ApkComponentsTool
import dev.smithy.toolkit.tools.ApkInstallTool
import dev.smithy.toolkit.tools.ApkMetaTool
import dev.smithy.toolkit.tools.ApkPermissionsTool
import dev.smithy.toolkit.tools.ApkRebuildTool
import dev.smithy.toolkit.tools.ApkSignTool
import dev.smithy.toolkit.tools.ApkSignaturesTool
import dev.smithy.toolkit.tools.ApkVerifyTool
import dev.smithy.toolkit.tools.ArscListTool
import dev.smithy.toolkit.tools.ArscReplaceStringTool
import dev.smithy.toolkit.tools.ArscSetTool
import dev.smithy.toolkit.tools.AxmlDecodeTool
import dev.smithy.toolkit.tools.AxmlPatchTool
import dev.smithy.toolkit.tools.DexReplaceStringTool
import dev.smithy.toolkit.tools.DexSearchTool
import dev.smithy.toolkit.tools.EntryDeleteTool
import dev.smithy.toolkit.tools.EntryListTool
import dev.smithy.toolkit.tools.EntryReadTool
import dev.smithy.toolkit.tools.EntryWriteTool
import dev.smithy.toolkit.tools.JadxDecompileTool
import dev.smithy.toolkit.tools.ManifestSetTool
import dev.smithy.toolkit.tools.PatchListTool
import dev.smithy.toolkit.tools.PatchRevertTool
import dev.smithy.toolkit.tools.ReportExportTool
import dev.smithy.toolkit.tools.SmaliPatchTool
import dev.smithy.toolkit.tools.SmaliReadClassTool
import dev.smithy.toolkit.tools.SmaliReadMethodTool
import dev.smithy.toolkit.tools.WorkspaceStateTool

/**
 * 工具注册表 —— 唯一的调用入口。
 *
 * **门控与错误翻译放在这一层，而不是各工具实现里**，有三个理由：
 *
 * 1. **策略只有一份**。「哪种 Effect 要确认」是产品决策；散进 28 个工具里，
 *    迟早会有几个走形（某个破坏性工具忘了确认）。
 * 2. **工具只描述自己是什么**。实现里不写 try/catch、不问「要不要确认」，
 *    只回答「我做什么、参数是什么、副作用多大」。
 * 3. **「失败必须带下一步」这条要求只有一处需要遵守**。模型卡在同一个错误上反复重试，
 *    十有八九是因为工具只回了「失败了」—— 所以在出口处统一补 hint。
 */
class DefaultToolRegistry(
    private val tools: List<Tool>,
    private val policy: ConfirmPolicy = ConfirmPolicy(),
) : ToolRegistry {

    private val byName = tools.associateBy { it.spec.name }

    override fun all(): List<Tool> = tools

    override fun find(name: String): Tool? = byName[name]

    /** 导出成 OpenAI / Anthropic function-calling 的 tools 数组。可按角色只给子集。 */
    override fun toOpenAiSchema(allowed: Set<String>?): JsonElement = buildJsonArray {
        tools.filter { allowed == null || it.spec.name in allowed }.forEach { t ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", t.spec.name)
                            put("description", t.spec.description)
                            put("parameters", t.spec.params)
                        },
                    )
                },
            )
        }
    }

    /** 只给名字与一行说明，用于「让模型知道自己有哪些工具」的紧凑提示。 */
    fun catalog(): String = tools.joinToString("\n") { "${it.spec.name}  ${it.spec.description.lineSequence().first()}" }

    suspend fun invoke(name: String, ctx: ToolContext, args: JsonObject): ToolResult {
        val tool = byName[name] ?: return Results.fail(
            "NO_SUCH_TOOL",
            "没有叫「$name」的工具",
            "可用工具：" + tools.joinToString("、") { it.spec.name },
        )

        if (policy.needsConfirm(tool.spec.effect)) {
            val request = ConfirmRequest(
                toolName = name,
                effect = tool.spec.effect,
                summary = summarize(tool, args),
            )
            if (!ctx.confirm(request)) {
                return Results.fail(
                    "USER_DENIED",
                    "用户拒绝了这次操作：${request.summary}",
                    "不要重试同一个操作。换个做法，或者先问用户想怎么处理",
                )
            }
        }

        // 工具实现里不写 try/catch —— 到这里统一翻译成人话 + 下一步建议
        return try {
            tool.invoke(ctx, args)
        } catch (e: BadArgs) {
            Results.fail("BAD_ARGS", e.message.orEmpty(), e.hint)
        } catch (e: NoWorkspaceException) {
            Results.fail(
                "NO_WORKSPACE",
                e.message.orEmpty(),
                "先让用户在「工作台」里选一个 APK 打开，然后再来做改动",
            )
        } catch (e: NoSuchElementException) {
            Results.fail(
                "NOT_FOUND",
                e.message ?: "找不到目标",
                "确认名字或路径写对了。不确定就先搜一下（dex.search / arsc.list / entry.list）",
            )
        } catch (e: IllegalStateException) {
            Results.fail(
                "ENGINE_REFUSED",
                e.message ?: "引擎拒绝了这次操作",
                "按提示调整参数；换个思路再来",
            )
        } catch (e: Throwable) {
            Results.fail(
                "ENGINE_FAILURE",
                "${e::class.simpleName}: ${e.message}",
                "引擎层的意外错误。把这段原文告诉用户，不要自己重试同样的调用",
            )
        }
    }

    /** 确认卡片上的摘要：工具名 + 关键参数（参数可能很长，截一下）。 */
    private fun summarize(tool: Tool, args: JsonObject): String {
        val shown = args.entries.joinToString("，") { (k, v) ->
            val raw = v.toString().removeSurrounding("\"")
            "$k=${if (raw.length > 60) raw.take(60) + "…" else raw}"
        }
        return tool.spec.name + if (shown.isNotEmpty()) "（$shown）" else ""
    }
}

/**
 * 门控策略。
 *
 * 默认：READ 直接做、WRITE 要确认、DESTRUCTIVE 强制确认。
 *
 * 「信任模式」只放开 WRITE —— **DESTRUCTIVE 永远要确认**。因为它的后果是覆盖层回退不了的：
 * 条目还能撤销，但包已经装到手机上、或者已经发出去，撤销改动也换不回来。
 */
data class ConfirmPolicy(val trustWrites: Boolean = false) {
    fun needsConfirm(effect: Effect): Boolean = when (effect) {
        Effect.READ -> false
        Effect.WRITE -> !trustWrites
        Effect.DESTRUCTIVE -> true
    }
}

/** 全部工具。 */
fun defaultTools(): List<Tool> = listOf(
    // 工作区与条目
    WorkspaceStateTool, ApkMetaTool, ApkSignaturesTool, ApkPermissionsTool, ApkComponentsTool,
    ReportExportTool, EntryListTool, EntryReadTool, EntryWriteTool, EntryDeleteTool,
    PatchListTool, PatchRevertTool,
    // 代码层
    DexSearchTool, JadxDecompileTool, SmaliReadClassTool, SmaliReadMethodTool,
    SmaliPatchTool, DexReplaceStringTool,
    // 资源层
    ArscListTool, ArscSetTool, ArscReplaceStringTool, ManifestSetTool, AxmlDecodeTool, AxmlPatchTool,
    // 打包链路
    ApkRebuildTool, ApkSignTool, ApkVerifyTool, ApkInstallTool,
)

fun defaultRegistry(policy: ConfirmPolicy = ConfirmPolicy()): DefaultToolRegistry =
    DefaultToolRegistry(defaultTools(), policy)
