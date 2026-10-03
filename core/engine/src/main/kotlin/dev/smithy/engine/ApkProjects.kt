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
     * 打开一个 APK，返回工程视图。
     *
     * @param keystoreDir 内置签名密钥的存放目录。**调用方应传一个持久位置**（App 私有目录）：
     *   用临时目录的话，密钥会随进程结束丢失，重新生成的指纹与已装的应用不一致，
     *   改过的包就装不上去了。缺省落到系统临时目录，仅供测试与 CLI 使用。
     */
    suspend fun open(
        apkFile: File,
        workspaceId: String = UUID.randomUUID().toString(),
        keystoreDir: File? = null,
    ): ApkProject = ApkProjectImpl.open(
        workspaceId = workspaceId,
        apkFile = apkFile,
        keystoreDir = keystoreDir
            ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "smithy-keystore"),
    )
}
