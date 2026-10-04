package dev.smithy.feature.files

import android.content.Context
import dev.smithy.engine.ModuleChannel
import dev.smithy.engine.ModuleOpResult
import java.io.File

/**
 * [ModuleChannel] 的 Android 实现：全部转给 [ModuleOps]。
 *
 * 这里只做接口搬运，不放逻辑 —— 判断（有没有 root、magisk 在哪、要不要 remount）
 * 都在 `ModuleOps` 里，那样它不依赖引擎的类型，改一处不会牵连另一处。
 *
 * [context] 暂时用不上（root 操作都走 shell），但留着：以后要弹授权框、
 * 或读 `Build.SUPPORTED_ABIS` 来比对模块的 ABI 覆盖，都得有它。
 */
class AndroidModuleChannel(private val context: Context) : ModuleChannel {

    override val name: String = "root"

    override fun available(): Boolean = RootFs.isGranted() && ModuleOps.hasMagisk()

    override fun listInstalled(): List<String> = ModuleOps.listInstalled()

    override fun install(zip: File): ModuleOpResult = ModuleOps.install(zip).toOp()

    override fun setEnabled(id: String, enabled: Boolean): ModuleOpResult =
        ModuleOps.setEnabled(id, enabled).toOp()

    override fun scheduleRemove(id: String): ModuleOpResult = ModuleOps.scheduleRemove(id).toOp()

    override fun uninstallNow(id: String): ModuleOpResult = ModuleOps.uninstallNow(id).toOp()

    override fun restartZygote(): ModuleOpResult = ModuleOps.restartZygote().toOp()

    private fun ModuleOps.Result.toOp() = ModuleOpResult(ok, message)
}
