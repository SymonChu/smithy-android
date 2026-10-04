package dev.smithy.engine

import java.io.File

/**
 * 模块的刷入与启停 —— 「引擎（纯 JVM）↔ 平台」的接缝。
 *
 * 和 [InstallChannel] 是同一个套路：引擎与工具层只认这个接口，不认识 libsu、不认
 * `magisk` 命令、不知道 `su` 怎么用；真正的实现在 App 层注册进来。
 *
 * **注册表为什么放在 `core:engine` 而不是某个 feature 模块**（[InstallChannelRegistry]
 * 是放在 `:feature:apk` 的）：工具层（`:toolkit`）要能调到它，而 `:toolkit` 只依赖
 * `:core:engine` 和 `:core:fs` —— 不依赖任何 feature 模块。放在 feature 里，
 * AI 就永远够不着这些能力，M6 会变成「有实现、没人能调」的死代码。
 */
interface ModuleChannel {

    /** 通道名，用于界面显示与错误措辞（如 `root`）。 */
    val name: String

    /**
     * 现在能不能用。
     *
     * 实现在这里要如实反映「有 root 但用户拒绝了授权」这类情况，
     * 而不是先返回 true、等操作时再失败 —— 前者能让界面提前置灰。
     */
    fun available(): Boolean

    /** 已安装模块的 id 列表。 */
    fun listInstalled(): List<String>

    /** 刷入一个模块 zip。 */
    fun install(zip: File): ModuleOpResult

    /** 停用 / 启用（增删 `disable` 标记）。 */
    fun setEnabled(id: String, enabled: Boolean): ModuleOpResult

    /** 标记为下次重启时卸载。 */
    fun scheduleRemove(id: String): ModuleOpResult

    /** 立即删除模块目录。实现方应拒绝「还在启用」的模块。 */
    fun uninstallNow(id: String): ModuleOpResult

    /**
     * 软重启 zygote，让新装的 Zygisk 模块生效。
     *
     * **会影响所有正在运行的应用**（进程全部重建、未保存的东西会丢），
     * 所以调用方必须做破坏性门控 + 显式确认。
     */
    fun restartZygote(): ModuleOpResult
}

/** 一次模块操作的结果。[message] 是给人看的话。 */
data class ModuleOpResult(val ok: Boolean, val message: String)

/**
 * 当前注册的通道。
 *
 * 只保留一个：模块操作没有「逐级降级」的余地 —— Shizuku 在这一档权限不够，
 * 系统安装器更不行。有 root 才有，没有就是没有。
 */
object ModuleChannels {

    @Volatile
    private var channel: ModuleChannel? = null

    fun register(c: ModuleChannel) {
        channel = c
    }

    fun current(): ModuleChannel? = channel

    /** 拿一个「一定可用」的通道，不可用时抛带原因的异常。 */
    fun require(): ModuleChannel {
        val c = channel
            ?: throw IllegalStateException(
                "当前没有接入模块通道：App 层还没注册 ModuleChannel。" +
                    "模块的刷入与启停只有 Root 一档能做（Shizuku 权限不够）",
            )
        if (!c.available()) {
            throw IllegalStateException(
                "模块通道「${c.name}」现在不可用。" +
                    "这些操作需要 root：Shizuku 给的是 shell 身份，改不了 /data/adb/modules，" +
                    "也调不了 Magisk 的 CLI",
            )
        }
        return c
    }
}
