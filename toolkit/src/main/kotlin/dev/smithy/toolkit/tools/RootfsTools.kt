package dev.smithy.toolkit.tools

import dev.smithy.fs.AddOnCatalog
import dev.smithy.fs.AddOnHost
import dev.smithy.fs.RootFs
import dev.smithy.fs.NativeToolchains
import dev.smithy.fs.ShellChannels
import dev.smithy.toolkit.ArgReader
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.Results
import dev.smithy.toolkit.Tool
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.ToolResult
import dev.smithy.toolkit.ToolSpec
import dev.smithy.toolkit.schema
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * rootfs 这条路的两个工具。
 *
 * 「在手机上编 .so」目前唯一不依赖外部产物的一条路：装 Alpine rootfs → 在它里面
 * `apk add clang` → 用它的 clang 编 `zygisk/<abi>.so`。官方没有给 arm64 安卓的 clang
 * （NDK 只有 x86_64/darwin/windows 宿主机版，LLVM 也不发 android 目标），
 * 而 Alpine 的 clang 是原生 aarch64 的 —— 这就是当初为什么留着 rootfs 这一档。
 */

/** 把 rootfs 部署到可执行位置，并在里面装好 clang。 */
object RootfsSetupTool : Tool {
    override val spec = ToolSpec(
        name = "rootfs.setup",
        description = "把已装的 Alpine rootfs 部署到可执行位置（/data/local/tmp/smithy/rootfs）、" +
            "挂好 /proc 与 /dev、写 resolv.conf，然后 apk add clang（约 100–200MB）。" +
            "做完 `module.build` 就能在手机上编了。**要 root**；没有 root 这条路走不通",
        params = schema {
            string("rootfs", "rootfs 在设备上的位置，默认 /data/local/tmp/smithy/rootfs")
            boolean("skipApk", "true = 只部署不装 clang（想自己控制装什么时用）")
        },
        returns = "每一步的结果（部署/准备/装 clang），失败时是命令与输出",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val shell = ShellChannels.current()
            ?: return Results.fail(
                "NO_SHELL",
                "没有可用的命令通道",
                "App 启动时会登记 root 通道；纯 JVM/测试环境里没有它，chroot 这条路用不了",
            )
        if (!shell.available()) {
            return Results.fail(
                "NO_ROOT",
                "root 没授权",
                "在 Root 管理器里给 Smithy 放行，再重跑 rootfs.setup（chroot 与 mount 都要 root）",
            )
        }
        val mgr = AddOnHost.require()
        val rootfsAddon = mgr.installed(AddOnCatalog.rootfsAlpine)
            ?: return Results.fail(
                "NO_ROOTFS",
                "还没装 Alpine rootfs",
                "component.install id=${AddOnCatalog.rootfsAlpine.id}（4MB）之后再 rootfs.setup",
            )

        val mount = a.str("rootfs") ?: RootFs.deployDirFor()
        val rootFs = RootFs(File(mount), shell)

        ctx.progress("部署 rootfs 到 $mount …")
        val deploy = rootFs.deploy(rootfsAddon.dir)
        if (!deploy.ok) {
            return Results.fail("DEPLOY_FAILED", "部署 rootfs 失败：\n${deploy.out.tailLines(12)}", "看输出里第一条 error；空间不够也会长这样")
        }
        ctx.progress("挂 /proc、/dev，写 resolv.conf …")
        val prep = rootFs.prepare()
        if (!prep.ok) {
            return Results.fail("PREPARE_FAILED", "准备 chroot 环境失败：\n${prep.out.tailLines(12)}", "多半是 mount 被 SELinux 拦了，把这段输出留下")
        }
        if (a.bool("skipApk", false)) {
            return Results.ok(
                "rootfs 部署好了（没装 clang）：$mount",
                Results.json("dir" to mount),
            )
        }
        ctx.progress("apk add clang（约 100–200MB，慢）…")
        val apk = rootFs.installClang()
        val version = rootFs.sh("${rootFs.clangPath} --version | head -1", timeoutSeconds = 60).out.trim()
        if (!apk.ok) {
            return Results.fail(
                "APK_FAILED",
                "apk 装 clang 失败：\n${apk.out.tailLines(12)}",
                "常见两种：网络不通（chroot 里 DNS 靠 /etc/resolv.conf，rootfs.setup 会写）；" +
                    "镜像的 https 证书验不过（把 rootfs 的 /etc/apk/repositories 换成 http:// 再试）",
            )
        }
        // 装完立刻让它能用：登记 chroot 工具链，之后 module.build 就走这条路
        val sysroot = NativeToolchains.chrootSysroot(mgr.root.parentFile ?: mgr.root)
        val registered = NativeToolchains.scanChroot(shell, mount, sysroot)
        return Results.ok(
            "clang 装好了：${version.ifBlank { "（版本读不出来，但 apk 说装上了）" }}\n" +
                if (registered != null) {
                    "编译工具链已就绪（走 chroot）。可以 module.build 了 —— 注意还得有 target sysroot（NDK 的 sysroot/ 子树）"
                } else {
                    "但没登记上工具链 —— 检查 ${rootFs.clangPath} 在不在"
                },
            Results.json("dir" to mount, "clang" to version),
        )
    }
}

/** 在 rootfs 里执行命令（要 root；会改东西，所以是 DESTRUCTIVE）。 */
object RootfsExecTool : Tool {
    override val spec = ToolSpec(
        name = "rootfs.exec",
        description = "在一个已装的 Alpine rootfs 里执行命令（chroot 进去）。" +
            "用它装包（apk add ...）、看版本、跑各种 CLI。**要 root，而且会真的改动设备**，" +
            "所以每次都会让用户确认",
        params = schema {
            string("script", "要在 rootfs 里跑的 shell 脚本", required = true)
            string("rootfs", "rootfs 在设备上的位置，默认 /data/local/tmp/smithy/rootfs")
            integer("timeout", "超时秒数，默认 180")
        },
        returns = "退出码与输出（脚本自己产生的日志都在这儿）",
        effect = Effect.DESTRUCTIVE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val script = a.requireStr("script", "给我要跑的脚本")
        val shell = ShellChannels.current()
            ?: return Results.fail("NO_SHELL", "没有可用的命令通道", "App 启动时会登记 root 通道")
        if (!shell.available()) {
            return Results.fail("NO_ROOT", "root 没授权", "在 Root 管理器里给 Smithy 放行再试")
        }
        val mount = a.str("rootfs") ?: RootFs.deployDirFor()
        val rootFs = RootFs(File(mount), shell)
        val r = rootFs.sh(script, timeoutSeconds = a.int("timeout", 180).toLong())
        return if (r.ok) {
            Results.ok(r.out.ifBlank { "（没有输出）" }, Results.json("code" to r.code))
        } else {
            Results.fail(
                if (r.timedOut) "TIMEOUT" else "EXEC_FAILED",
                "退出码 ${r.code}：\n${r.out.tailLines(20)}",
                if (r.timedOut) "加大 timeout，或把脚本拆小" else "看输出里第一条 error",
            )
        }
    }
}

/** 只留最后几行：给用户看的是「出事那几句」，不是整段安装/编译过程。 */
private fun String.tailLines(n: Int): String = lines().takeLast(n).joinToString("\n")
