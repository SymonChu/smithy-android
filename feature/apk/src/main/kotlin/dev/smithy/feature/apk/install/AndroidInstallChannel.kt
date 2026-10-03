package dev.smithy.feature.apk.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import dev.smithy.engine.InstallChannel
import dev.smithy.engine.InstallResult
import dev.smithy.engine.InstallVia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * 平台侧的装机通道：Shizuku → Root → 系统安装器。
 *
 * 三条路各自的前提：
 * - **Shizuku**：服务在跑 + 已授权给本应用 → 以 shell（uid 2000）身份执行 `pm install`
 * - **Root**：Root 管理器放行 → `su -c pm install`
 * - **系统安装器**：不需要任何前提，弹系统界面让用户点确认 —— 所以它永远是降级链的终点
 *
 * 降级顺序本身不在这里，在引擎的 `ApkProjectImpl.install` 里 ——
 * 那是产品语义（AI 经 MCP 调用时也要走同一套），不该由某个 UI 或通道实现决定。
 */
class AndroidInstallChannel(private val context: Context) : InstallChannel {

    override fun available(): List<InstallVia> = buildList {
        if (isShizukuReady()) add(InstallVia.SHIZUKU)
        if (isRootGranted()) add(InstallVia.ROOT)
        // 系统安装器永远可用：把包交给系统界面，用户点确认即可
        add(InstallVia.INTENT)
    }

    override suspend fun install(apk: File, via: InstallVia): InstallResult = withContext(Dispatchers.IO) {
        when (via) {
            InstallVia.SHIZUKU -> installViaShizuku(apk)
            InstallVia.ROOT -> installViaRoot(apk)
            InstallVia.INTENT -> installViaIntent(apk)
        }
    }

    // ── Shizuku ─────────────────────────────────────────────

    private fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 以 shell 身份执行 `pm install`。
     *
     * **为什么是 `IShizukuService.newProcess` 而不是系统 API**：
     * `Shizuku` 门面类在 13.x 去掉了 `newProcess` 静态方法（`ShizukuRemoteProcess`
     * 的构造器也变成了包私有），但**服务接口里一直有它** —— `IShizukuService.newProcess`
     * 返回 `IRemoteProcess`，两个 stub 都在 `dev.rikka.shizuku:aidl` 里。
     * 拿到句柄直接调即可，不需要去碰 `IPackageInstaller` 那套 hidden API。
     *
     * **为什么用 `-S` 从 stdin 送包，而不是传文件路径**：
     * `pm install <path>` 的路径最终由 PackageManagerService 打开，而 shell 域读
     * app 私有目录（`/data/user/0/<pkg>/...`）在 SELinux 下可能被拦。
     * 流式送包绕开这个限制，也省掉一次大文件拷贝。
     */
    private fun installViaShizuku(apk: File): InstallResult {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return InstallResult(false, InstallVia.SHIZUKU, "Shizuku 服务未运行：请先在 Shizuku 应用里启动服务")
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return InstallResult(false, InstallVia.SHIZUKU, "Shizuku 版本过旧（需要 11 及以上）")
        }
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            return InstallResult(false, InstallVia.SHIZUKU, "Shizuku 未授权给本应用：请在 Shizuku 里允许 ${context.packageName}")
        }

        val service = runCatching { IShizukuService.Stub.asInterface(Shizuku.getBinder()) }.getOrNull()
            ?: return InstallResult(false, InstallVia.SHIZUKU, "拿不到 Shizuku 服务句柄：服务可能刚重启，请重试")

        return runCatching {
            val proc = service.newProcess(
                arrayOf("pm", "install", "-r", "-d", "-S", apk.length().toString()),
                null,
                null,
            )

            // 把包写进 pm 的 stdin。use{} 关闭流 → pm 收到 EOF 才会开始真正安装。
            FileInputStream(apk).use { input ->
                FileOutputStream(proc.outputStream.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }

            val exit = proc.waitFor()
            val text = (readText(proc.inputStream) + "\n" + readText(proc.errorStream)).trim()

            if (exit == 0) {
                InstallResult(true, InstallVia.SHIZUKU, text.ifBlank { "pm install 成功" }.take(200))
            } else {
                InstallResult(false, InstallVia.SHIZUKU, translateFailure(text))
            }
        }.getOrElse { t ->
            InstallResult(false, InstallVia.SHIZUKU, "Shizuku 装机失败：${t.message}")
        }
    }

    /** 远程进程的输出流是个 ParcelFileDescriptor，用 AutoClose 版本读，免得漏 fd。 */
    private fun readText(pfd: ParcelFileDescriptor): String = runCatching {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    // ── Root ────────────────────────────────────────────────

    private fun isRootGranted(): Boolean = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)

    private fun installViaRoot(apk: File): InstallResult {
        if (!isRootGranted()) {
            return InstallResult(false, InstallVia.ROOT, "未获得 Root 授权：请在 Root 管理器里给本应用放行")
        }
        val result = runCatching {
            Shell.cmd("pm install -r -d ${apk.absolutePath.shellQuote()}").exec()
        }.getOrElse { t ->
            return InstallResult(false, InstallVia.ROOT, "执行 pm install 失败：${t.message}")
        }

        if (result.isSuccess) {
            val out = result.out.joinToString(" ").trim()
            return InstallResult(true, InstallVia.ROOT, out.ifBlank { "pm install 成功" }.take(200))
        }
        return InstallResult(false, InstallVia.ROOT, translateFailure((result.err + result.out).joinToString("\n").trim()))
    }

    // ── 系统安装器 ───────────────────────────────────────────

    /**
     * 交给系统安装界面。
     *
     * 返回 ok = true 表示「请求已经交给系统」，**不代表已经装上** ——
     * 用户还要在系统界面点一次确认。所以 message 必须写清楚，
     * 界面上不能把它显示成「已安装」。
     */
    private fun installViaIntent(apk: File): InstallResult {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult(true, InstallVia.INTENT, "已交给系统安装器，请在系统界面点确认完成安装")
        } catch (t: Throwable) {
            InstallResult(false, InstallVia.INTENT, "无法唤起系统安装器：${t.message}")
        }
    }

    // ── 共用 ────────────────────────────────────────────────

    /**
     * 把 `pm` 的原始输出翻译成人话。
     *
     * 三条通道的失败原因是一样的，所以共用一份 —— 原始输出（`Failure [INSTALL_FAILED_...]`）
     * 对用户没有意义，而最常见的那两种失败正好都有很具体的下一步。
     */
    private fun translateFailure(detail: String): String = when {
        detail.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", true) ->
            "包名相同但签名不同：设备上装的是原开发者的签名版本，本工具签的包覆盖不了。需要先卸载原应用"

        detail.contains("INSTALL_FAILED_VERSION_DOWNGRADE", true) ->
            "版本低于设备上已装的版本，且系统不允许降级"

        detail.contains("INSTALL_PARSE_FAILED", true) ||
            detail.contains("INSTALL_FAILED_INVALID_APK", true) ->
            "系统解析这个包失败：文件可能不完整，或改包时弄坏了包结构"

        detail.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", true) ->
            "设备存储空间不足"

        detail.contains("Permission Denial", true) ->
            "权限被拒：这条通道没有拿到安装权限，试试换一条（Shizuku 需要在 Shizuku 里授权，Root 需要在 Root 管理器里放行）"

        else -> detail.ifBlank { "pm install 返回非 0 但没有输出" }.take(300)
    }
}

/** 把路径安全地放进 shell 命令：路径里有空格或引号时不至于把命令拆坏。 */
private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
