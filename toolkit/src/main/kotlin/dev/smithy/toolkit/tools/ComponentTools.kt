package dev.smithy.toolkit.tools

import dev.smithy.fs.AddOnArchive
import dev.smithy.fs.AddOnCatalog
import dev.smithy.fs.AddOnHost
import dev.smithy.fs.AddOnKind
import dev.smithy.fs.AddOnSpec
import dev.smithy.fs.NativeToolchains
import dev.smithy.fs.humanSize
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
 * 可选组件（文档里的 M5「可选模块」）：App 不打包进去、按需拿到手机上的东西。
 *
 * 为什么要有工具而不只做界面：**装它的时候，下一步一定是编译**，而编译是模型在做的活。
 * 让它先用 `component.list` 看清缺什么、能不能一键装，比让它去撞一次 `module.build`
 * 的失败便宜得多。
 */

/** 现在有哪些可选组件、装没装、多大、要不要 root。 */
object ComponentListTool : Tool {
    override val spec = ToolSpec(
        name = "component.list",
        description = "列出可选组件（rootfs、native 编译工具链等）：装没装、多大、装在哪个目录、" +
            "要不要 root、许可是什么，并报出 native 编译工具链现在能不能用。" +
            "**要在 zygisk 模块里编 .so 之前先看这个** —— 缺什么、下一步做什么，这里一次说清",
        params = schema { },
        returns = "每个组件一行（id、体积、已装/未装、许可、装载目录）＋ 工具链状态",
        effect = Effect.READ,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val mgr = AddOnHost.current()
            ?: return Results.fail(
                "NO_HOST",
                "这台设备上没登记可选组件的安装位置",
                "App 正常启动时会登记（SmithyApp）；测试或纯 JVM 里得自己 new 一个 AddOnManager(root)",
            )
        val lines = mutableListOf("安装位置：${mgr.root.absolutePath}")
        AddOnCatalog.all.forEach { spec ->
            val have = mgr.installed(spec)
            lines += buildString {
                append("· ${spec.name}（id=${spec.id}）")
                append(if (have != null) " —— 已装，${humanSize(have.bytes)}" else " —— 未装，约 ${humanSize(spec.bytes)}")
                append("\n  ${spec.summary}")
                if (spec.needsRoot) append("\n  需要 root 才能真正用起来")
                append("\n  许可：${spec.license}")
                if (have == null) append("\n  装：component.install id=${spec.id}")
            }
        }
        val toolchain = NativeToolchains.current()
        // 清单里没有、但磁盘上装着的：用户自己灌进来的包（拿到 arm64 clang 的唯一途径）。
        // 不列出来的话，刚导进去的工具链在界面上会像没装过一样
        val catalogIds = AddOnCatalog.all.map { it.id }.toSet()
        mgr.installedAll().filter { it.spec.id !in catalogIds }.forEach { ins ->
            lines += "· ${ins.spec.name}（id=${ins.spec.id}）—— 已装，${humanSize(ins.bytes)}" +
                "\n  本地灌进来的，不在清单里（不会自动更新）" +
                "\n  目录：${ins.dir.absolutePath}"
        }
        lines += if (toolchain?.available() == true) {
            "native 编译工具链：可用（${toolchain.describe()}）"
        } else {
            "native 编译工具链：不可用。" + NativeToolchains.missingHint()
        }
        return Results.ok(lines.joinToString("\n"))
    }
}

/**
 * 装一个可选组件：按登记的地址下（`id=`），或装手机本地的归档（`file=`）。
 *
 * 两条路都要。因为**有些东西官方没有能直接下到手机的产物** —— 比如给 arm64 安卓用的
 * clang：Google 的 NDK 只有 x86_64/darwin/windows 宿主机版，LLVM 也不发 android 目标。
 * 那种东西只能在外面产出一份、传到手机上、从这里装进去。
 */
object ComponentInstallTool : Tool {
    override val spec = ToolSpec(
        name = "component.install",
        description = "下载并安装一个可选组件，或安装手机本地的一份归档。" +
            "`id=rootfs-alpine` 走登记地址（清单见 component.list）；" +
            "`file=/sdcard/Download/bundle.zip` 装本地那份（配 kind= / sha256= / strip=）。" +
            "装完会自动重扫 native 工具链：能编了就说能编",
        params = schema {
            string("id", "要装的组件 id（component.list 里有）")
            string("file", "或者：手机本地归档的路径（zip / tar.gz）")
            string("kind", "装本地归档时的种类：rootfs | toolchain | other（默认 toolchain）")
            string("sha256", "装本地归档时的期望校验值；给了就核对，不给就直接装")
            integer("strip", "装本地归档时去掉几层目录，默认 1（包通常套了一层目录）")
        },
        returns = "装载目录与占用体积；失败时是原因 + 下一步",
        effect = Effect.WRITE,
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult {
        val a = ArgReader(args)
        val mgr = AddOnHost.current()
            ?: return Results.fail(
                "NO_HOST",
                "这台设备上没登记可选组件的安装位置",
                "App 正常启动时会登记（SmithyApp）；测试里得自己 new 一个 AddOnManager(root)",
            )
        val id = a.str("id")
        val path = a.str("file")
        if (id.isNullOrBlank() && path.isNullOrBlank()) {
            return Results.fail(
                "BAD_ARGS",
                "要给 id=（按登记地址下）或 file=（装本地归档）",
                "先 component.list 看看有什么、装到哪儿",
            )
        }

        if (!id.isNullOrBlank()) {
            val component = AddOnCatalog.find(id)
                ?: return Results.fail(
                    "UNKNOWN_COMPONENT",
                    "没有这个组件：$id",
                    "可选的是：" + AddOnCatalog.all.joinToString { it.id },
                )
            ctx.progress("开始装 ${component.name}（约 ${humanSize(component.bytes)}）")
            val res = mgr.install(component) { p -> ctx.progress("${component.name}：${p.phase} ${p.percent}%") }
            if (!res.ok) {
                return Results.fail("INSTALL_FAILED", res.message ?: "装不上", res.hint)
            }
            val installed = res.install!!
            return Results.ok(
                "${component.name} 装好了：${installed.dir.absolutePath}（${humanSize(installed.bytes)}）" +
                    if (component.needsRoot) "\n注意：它要 root 才能真正跑起来（chroot / 装可执行文件）" else "",
                Results.json(
                    "id" to component.id,
                    "dir" to installed.dir.absolutePath,
                    "bytes" to installed.bytes,
                    "needsRoot" to component.needsRoot,
                ),
            )
        }

        val archive = File(path!!)
        if (!archive.isFile) {
            return Results.fail(
                "NO_FILE",
                "找不到文件：$path",
                "确认路径。手机上从电脑传过去一般是 /sdcard/Download/...；" +
                    "应用私有目录外面看不见，别把包放那儿",
            )
        }
        val kind = AddOnKind.entries.firstOrNull { it.name.equals(a.str("kind") ?: "toolchain", ignoreCase = true) }
            ?: return Results.fail("BAD_KIND", "kind 只能是 rootfs / toolchain / other", "重给一个")
        val localSpec = AddOnSpec(
            id = "local-" + archive.nameWithoutExtension.replace('.', '-'),
            name = archive.name,
            summary = "从本地归档装的（$path）",
            kind = kind,
            url = "",
            bytes = archive.length(),
            sha256 = (a.str("sha256") ?: "").lowercase(),
            archive = if (archive.name.endsWith(".zip", ignoreCase = true)) AddOnArchive.ZIP else AddOnArchive.TAR_GZ,
            license = "随包自带，见包内说明",
            homepage = "",
            stripComponents = a.int("strip", 1),
        )
        val res = mgr.installFromLocal(localSpec, archive) { p ->
            ctx.progress("${archive.name}：${p.phase} ${p.percent}%")
        }
        if (!res.ok) return Results.fail("INSTALL_FAILED", res.message ?: "装不上", res.hint)
        val installed = res.install!!
        return Results.ok(
            "装好了：${installed.dir.absolutePath}（${humanSize(installed.bytes)}）\n" +
                "要是工具链包，现在就能在模块页点编译，或直接 module.build",
            Results.json(
                "dir" to installed.dir.absolutePath,
                "bytes" to installed.bytes,
            ),
        )
    }
}
