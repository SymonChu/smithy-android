package dev.smithy.feature.files

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 模块的安装与启停 —— **只走 Root 通道**。
 *
 * Shizuku 在这件事上权限不够：它给的是 shell 身份，而 `/data/adb/modules` 是 root 的，
 * 而且刷模块要调用 Magisk 自己的 CLI。所以这些操作有且只有一个前提：设备有 root。
 *
 * **安装交给 `magisk --install-module`，不自己解压。** 原因是模块的 `customize.sh`
 * 必须在 **Magisk 自己的环境**里跑（它依赖 Magisk 注入的变量和函数，比如 `ui_print`、
 * `set_perm`），我们自己解压到目录会跳过那段脚本 —— 装出来的模块看着在、
 * 但缺文件、缺权限，而没有任何报错。
 */
object ModuleOps {

    /** Magisk 的模块目录。 */
    const val MODULES_DIR = "/data/adb/modules"

    /** 一次操作的结果。[message] 是给人看的话，成功时也可能有内容。 */
    data class Result(val ok: Boolean, val message: String) {
        companion object {
            fun ok(message: String = "") = Result(true, message)
            fun fail(message: String) = Result(false, message)
        }
    }

    /** 没有 root 时的统一回复。**说清原因**，而不是静默失败。 */
    private val noRoot =
        "这个操作需要 root：Shizuku 给的是 shell 身份，改不了 /data/adb/modules，" +
            "也没法调用 Magisk 的 CLI。先在文件标签切到 root 模式并授权"

    /**
     * 找 `magisk` 可执行文件。
     *
     * 不假设它在 PATH 里：多数设备的 PATH 没有 `/data/adb/magisk`，
     * 而 `su` 拿到的环境更干净。按常见位置逐个试。
     */
    private fun magiskPath(): String? {
        val candidates = listOf(
            "/data/adb/magisk/magisk",
            "/system/bin/magisk",
            "/debug_ramdisk/magisk",
            "magisk",
        )
        for (c in candidates) {
            val r = Shell.cmd("[ -x ${q(c)} ] && echo YES || echo NO 2>/dev/null").exec()
            if (r.out.firstOrNull()?.trim() == "YES") return c
        }
        // 最后再试直接调（交给 shell 走 PATH）
        val r = Shell.cmd("command -v magisk 2>/dev/null").exec()
        return r.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Magisk 是否可用。 */
    fun hasMagisk(): Boolean = runCatching { magiskPath() != null }.getOrDefault(false)

    /** 列出已安装的模块 id。 */
    fun listInstalled(): List<String> = runCatching {
        Shell.cmd("ls ${q(MODULES_DIR)} 2>/dev/null").exec()
            .out
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /**
     * 刷入一个模块 zip。
     *
     * 走 `magisk --install-module` —— 见类注释，`customize.sh` 必须在 Magisk
     * 自己的环境里跑。
     */
    fun install(zip: File): Result {
        if (!RootFs.isGranted()) return Result.fail(noRoot)
        if (!zip.isFile) return Result.fail("找不到文件：${zip.absolutePath}")

        val magisk = magiskPath()
            ?: return Result.fail("找不到 magisk 命令。设备可能没装 Magisk，或者它不是 Magisk 环境")

        // 先让模块目录可写：有些设备 /data 是只读挂载
        RootFs.remountRw(MODULES_DIR)

        val r = runCatching {
            Shell.cmd("$magisk --install-module ${q(zip.absolutePath)} 2>&1").exec()
        }.getOrNull() ?: return Result.fail("执行 magisk 时出错")

        val text = r.out.joinToString("\n").trim()
        return if (r.isSuccess) {
            Result.ok(
                buildString {
                    append("已刷入")
                    if (text.isNotEmpty()) append("：").append(text.take(500))
                    append("\n多数模块要重启（或软重启 zygote）才生效")
                },
            )
        } else {
            Result.fail(
                buildString {
                    append("刷入失败")
                    if (text.isNotEmpty()) append("：").append(text.take(500))
                    append("\n常见原因：zip 不是模块（根目录要有 module.prop）、")
                    append("模块的 customize.sh 报错、或 Magisk 版本太老")
                },
            )
        }
    }

    /**
     * 停用 / 启用：增删 `disable` 标记文件。
     *
     * 用标记而不是删目录 —— 停用随时能改回来，删了就得重刷。
     * 标记要**重启后才真正生效**，这一点必须说出来。
     */
    fun setEnabled(id: String, enabled: Boolean): Result {
        if (!RootFs.isGranted()) return Result.fail(noRoot)
        if (!installed(id)) return Result.fail("没有已安装的模块「$id」")

        val marker = "$MODULES_DIR/$id/disable"
        val cmd = if (enabled) "rm -f ${q(marker)}" else "touch ${q(marker)}"
        val r = runCatching { Shell.cmd("$cmd 2>&1").exec() }.getOrNull()
            ?: return Result.fail("执行时出错")

        return if (r.isSuccess) {
            Result.ok(
                if (enabled) "已启用「$id」（下次重启后生效）" else "已停用「$id」（下次重启后生效）",
            )
        } else {
            Result.fail("改状态失败：${r.out.joinToString(" ").take(300)}")
        }
    }

    /**
     * 标记为「下次重启时卸载」。
     *
     * **比直接删目录安全**：模块可能正在被 zygote 用着（zygisk 下的 so 已映射进进程），
     * 直接删目录会在下次开机前留下一个半残状态。用标记，让 Magisk 在开机的合适时机
     * 自己清理。
     */
    fun scheduleRemove(id: String): Result {
        if (!RootFs.isGranted()) return Result.fail(noRoot)
        if (!installed(id)) return Result.fail("没有已安装的模块「$id」")

        val r = runCatching {
            Shell.cmd("touch ${q("$MODULES_DIR/$id/remove")} 2>&1").exec()
        }.getOrNull() ?: return Result.fail("执行时出错")

        return if (r.isSuccess) {
            Result.ok("已标记「$id」为下次重启卸载。想反悔就在重启前删掉 $MODULES_DIR/$id/remove")
        } else {
            Result.fail("标记失败：${r.out.joinToString(" ").take(300)}")
        }
    }

    /**
     * 立即删除模块目录。
     *
     * 只在模块**已停用**且用户明确要求时用 —— 正在被映射的 `.so` 被删掉会让进程行为
     * 变得不可预期。所以这里加一道检查：还带着 `disable` 标记才允许立即删。
     */
    fun uninstallNow(id: String): Result {
        if (!RootFs.isGranted()) return Result.fail(noRoot)
        if (!installed(id)) return Result.fail("没有已安装的模块「$id」")

        val disabled = runCatching {
            Shell.cmd("[ -f ${q("$MODULES_DIR/$id/disable")} ] && echo YES || echo NO").exec()
                .out.firstOrNull()?.trim() == "YES"
        }.getOrDefault(false)

        if (!disabled) {
            return Result.fail(
                "「$id」还在启用状态。直接删目录时它的 zygisk .so 可能正被映射进进程，" +
                    "删掉会让进程行为变得不可预期。先停用（或改用「标记卸载」，重启后自动清理）",
            )
        }

        RootFs.remountRw(MODULES_DIR)
        val r = runCatching {
            Shell.cmd("rm -rf ${q("$MODULES_DIR/$id")} 2>&1").exec()
        }.getOrNull() ?: return Result.fail("执行时出错")

        return if (r.isSuccess) Result.ok("已删除「$id」") else {
            Result.fail("删除失败：${r.out.joinToString(" ").take(300)}")
        }
    }

    /**
     * 软重启 zygote —— 让新装的 Zygisk 模块生效，**不用整机重启**。
     *
     * **这个操作会影响所有正在运行的应用**：所有进程都会被杀掉重建，未保存的东西会丢。
     * 所以调用方必须做 [dev.smithy.engine.PatchKind] 那类破坏性门控 + 显式确认，
     * 信任模式下也不该免掉。
     */
    fun restartZygote(): Result {
        if (!RootFs.isGranted()) return Result.fail(noRoot)

        // 先试 init 的方式：`setprop ctl.restart zygote` 是 AOSP 的常规做法，
        // 由 init 负责重启，比直接 kill 干净（能走正常的停止 / 启动流程）
        val viaInit = runCatching {
            Shell.cmd("setprop ctl.restart zygote 2>&1").exec()
        }.getOrNull()
        if (viaInit?.isSuccess == true) {
            return Result.ok("已请求重启 zygote（所有应用会重启）")
        }

        // 退一步用 killall：有些 ROM 的 init 不认 ctl.restart
        val viaKill = runCatching {
            Shell.cmd("killall zygote 2>&1").exec()
        }.getOrNull()
        return if (viaKill?.isSuccess == true) {
            Result.ok("已结束 zygote（所有应用会重启）")
        } else {
            Result.fail(
                "两种方式都没成功。这台设备的 init 可能不认 ctl.restart，也没有 killall。" +
                    "手工重启设备也能达到同样效果",
            )
        }
    }

    /** 已安装的模块里有没有这个 id。 */
    private fun installed(id: String): Boolean = listInstalled().contains(id)

    /** shell 里安全引用一个字符串。 */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
