package dev.smithy.fs

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 跑一条命令的通道。
 *
 * 两种实现：
 * - **普通**（[ProcessShellChannel]）：`ProcessBuilder`，跑在应用自己的 uid 下 —— 编译
 *   （如果工具链就在应用目录里且能执行）走这条。
 * - **root**（实现在 `:feature:apk`，用 libsu）：`chroot` / `mount` / `apk` 都要 root，
 *   rootfs 那条路只能走这条。
 *
 * 为什么做成接口：引擎与工具层是纯 JVM，不认识 libsu、也不认识 magisk；
 * 平台那点事只有在 App 层能办（和 ModuleChannels / InstallChannel 同一个套路）。
 */
interface ShellChannel {

    /** 给人看的名字（出现在日志和「缺什么」的说明里）。 */
    val name: String

    /** 现在能不能用。root 通道要用户授权，没授权就是不能用。 */
    fun available(): Boolean

    /** 跑一条命令。[timeoutSeconds] 到了就杀掉，并标成 [ShellResult.timedOut]。 */
    fun exec(command: String, timeoutSeconds: Long = 180): ShellResult
}

data class ShellResult(
    val code: Int,
    val out: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = code == 0 && !timedOut
}

object ShellChannels {

    @Volatile
    private var channel: ShellChannel? = null

    fun register(c: ShellChannel?) {
        channel = c
    }

    fun current(): ShellChannel? = channel

    /** 要一个一定可用的通道，不可用时抛带原因的异常。 */
    fun require(): ShellChannel {
        val c = channel ?: throw IllegalStateException(
            "这台设备上没有可用的命令通道（App 层没登记，或 root 没授权）。" +
                "chroot / apk 这些要 root 的事干不了",
        )
        if (!c.available()) {
            throw IllegalStateException("命令通道「${c.name}」现在不可用（多半是 root 没授权）")
        }
        return c
    }
}

/** 应用自己 uid 的通道：一台普通机器上也就能跑点 shell 命令，测试与桌面环境用。 */
class ProcessShellChannel(private val workDir: File? = null) : ShellChannel {

    override val name = "process"

    override fun available(): Boolean = true

    override fun exec(command: String, timeoutSeconds: Long): ShellResult {
        return try {
            val pb = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true)
            workDir?.let { pb.directory(it) }
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                ShellResult(-1, out + "\n（超时 ${timeoutSeconds}s，已终止）", timedOut = true)
            } else {
                ShellResult(p.exitValue(), out)
            }
        } catch (e: Exception) {
            ShellResult(-1, "跑不起来：${e.message}")
        }
    }
}
