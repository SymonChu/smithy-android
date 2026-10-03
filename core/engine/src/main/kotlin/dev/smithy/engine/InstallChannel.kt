package dev.smithy.engine

import java.io.File

/**
 * 装机通道 —— 引擎与 Android 平台之间的接缝。
 *
 * 引擎是纯 JVM 的（不 import `android.*`），它不知道 Shizuku、Root shell、系统安装器
 * 各是什么东西，只描述「把这个包装到设备上」。具体怎么装由 App 层实现并注册进来。
 *
 * **为什么降级顺序留在引擎而不是 UI**：`Shizuku → Root → 系统安装器` 是产品语义，
 * 不是某个界面的行为。如果放在 UI 层，AI 通过 MCP 调 `apk.install` 时就会走另一套逻辑，
 * 两条路给出不同的结果 —— 这是最难排查的那类不一致。
 */
interface InstallChannel {

    /**
     * 当前环境实际可用的通道，按优先级排序。
     *
     * 实现方要在这里把「有 Shizuku 但没授权」「有 Root 但用户拒绝了」这类情况如实反映出来，
     * 而不是统一返回全部三档、等调用时再失败 —— 前者能让 UI 提前置灰，后者只能事后报错。
     */
    fun available(): List<InstallVia>

    /**
     * 装一个包。
     *
     * [via] 必定是 [available] 里的一个（引擎在调用前已经筛过）。
     *
     * 失败时 [InstallResult.message] 要给**人能看懂的原因**：
     * 「Shizuku 服务未运行，请在设置里启动」而不是「Session 创建失败」——
     * 见 docs/04 的错误约定：错误要带下一步。
     */
    suspend fun install(apk: File, via: InstallVia): InstallResult
}
