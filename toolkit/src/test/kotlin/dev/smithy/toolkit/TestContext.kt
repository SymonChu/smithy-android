package dev.smithy.toolkit

import dev.smithy.engine.ApkProject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * 工具测试共用的上下文。
 *
 * 之前每个测试文件各写一份 —— 多写一份的代价是「门控断言」和「参数校验断言」在两处
 * 慢慢分叉。放在这里，`:toolkit` 下的测试都拿同一个。
 */
internal class FakeContext(private val answer: Boolean = true) : ToolContext {

    override val workspace: ApkProject? = null

    override fun requireWorkspace(): ApkProject = throw NoWorkspaceException()

    override val sessionId: String = "test"

    override val callId: String = "test"

    override fun progress(message: String) = Unit

    val confirms = mutableListOf<ConfirmRequest>()

    override suspend fun confirm(request: ConfirmRequest): Boolean {
        confirms += request
        return answer
    }
}

/** 跑一次工具调用：门控（确认）也在里面走一遍，和线上同一条路径。 */
internal fun DefaultToolRegistry.call(name: String, ctx: ToolContext, args: JsonObject) =
    runBlocking { invoke(name, ctx, args) }
