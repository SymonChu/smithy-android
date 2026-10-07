package dev.smithy.feature.apk

import com.topjohnwu.superuser.Shell
import dev.smithy.fs.ShellChannel
import dev.smithy.fs.ShellResult

/**
 * root 通道（libsu）。
 *
 * 只有 root 能 `chroot` / `mount` / 往 `/data/local/tmp` 写东西，所以 rootfs 那条路
 * 全靠它。放在 :feature 里而不是 core：引擎与工具层是纯 JVM，不该认识 libsu
 * （同一个套路见 ModuleChannels / InstallChannel 的注册方式）。
 *
 * 关于超时：libsu 自己没有超时，所以这里用设备上的 `timeout`（toybox/busybox 都有）。
 * 探测不到就退化成「不设上限」，并把这件事写进返回内容 —— 不假装做到了没做的事。
 */
class LibsuRootChannel : ShellChannel {

    override val name = "root（libsu）"

    override fun available(): Boolean = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)

    override fun exec(command: String, timeoutSeconds: Long): ShellResult {
        if (!available()) {
            return ShellResult(-1, "root 没授权：在 Root 管理器里给 Smithy 放行后再试")
        }
        val hasTimeout = timeoutAvailable()
        val wrapped = if (hasTimeout) "timeout $timeoutSeconds sh -c ${quoteForShell(command)}" else command
        return try {
            val r = Shell.cmd(wrapped).exec()
            val out = (r.out + r.err).joinToString("\n")
            // toybox 的 timeout 被杀时是 124：翻译成人话，别让上层以为「命令自己失败了」
            ShellResult(
                code = r.code,
                out = if (hasTimeout && r.code == 124) "$out\n（超过 ${timeoutSeconds}s，已终止）" else out,
                timedOut = hasTimeout && r.code == 124,
            )
        } catch (e: Exception) {
            ShellResult(-1, "root 命令执行失败：${e.message}")
        }
    }

    /** 设备的 toybox/busybox 里有没有 `timeout`（探一次记住）。 */
    private fun timeoutAvailable(): Boolean {
        if (timeoutProbe == null) {
            timeoutProbe = runCatching {
                val r = Shell.cmd("command -v timeout >/dev/null 2>&1 && echo yes").exec()
                r.out.any { it.trim() == "yes" }
            }.getOrDefault(false)
        }
        return timeoutProbe == true
    }

    private companion object {
        @Volatile
        var timeoutProbe: Boolean? = null

        fun quoteForShell(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
