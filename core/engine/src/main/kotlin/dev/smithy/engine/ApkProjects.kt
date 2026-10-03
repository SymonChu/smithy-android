package dev.smithy.engine

import dev.smithy.engine.internal.ApkProjectImpl
import java.io.File
import java.util.UUID

/**
 * 引擎入口。
 *
 * UI 层与 AI 层都只通过这里打开工程，不直接接触 ARSCLib / apksig，
 * 这样将来要换实现（比如把某个畸形包交给 rootfs 里的 apktool 处理）时不用改上层。
 */
object ApkProjects {

    /**
     * 打开一个 APK，返回只读的工程视图（M0）。
     *
     * 只读阶段不动原文件，所以不需要建工作区副本；
     * M1 起一旦涉及写操作，会在此处按 [workspaceId] 建独立工作区并解包。
     */
    suspend fun open(
        apkFile: File,
        workspaceId: String = UUID.randomUUID().toString(),
    ): ApkProject = ApkProjectImpl.open(workspaceId, apkFile)
}
