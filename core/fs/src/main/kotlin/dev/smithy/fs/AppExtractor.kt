package dev.smithy.fs

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * 从设备上提取已安装应用的 APK。
 *
 * 来源是 PackageManager（不是扫目录）：`pm` 给的是**安装系统认的那份路径**，
 * 包括 split、更新过的系统应用指向的用户分区路径。扫 `/data/app` 能碰巧得到
 * 类似结果，但那是把「系统怎么记」换成「我猜目录结构」，各家 ROM 顺序号规则
 * 不同，猜错的版本比猜对的多。
 *
 * 只做**读**：把 base/split APK 拷到目标目录。不卸载、不动原文件。
 */
class AppExtractor(private val context: Context) {

    /** 一个已安装应用。 */
    data class AppInfo(
        /** 包名，如 `dev.smithy.app`。 */
        val packageName: String,
        /** 显示名。拿不到 label 的（个别系统组件）用包名。 */
        val label: String,
        /** base APK 的路径。 */
        val baseApk: String,
        /** split APK 路径（config.xx / delivery.* 等），可能为空。 */
        val splits: List<String>,
        val isSystem: Boolean,
        val isUpdatedSystem: Boolean,
        /** versionName（可能为 null 的极老应用给空串）。 */
        val versionName: String,
        val versionCode: Long,
        val installedAt: Long,
        val updatedAt: Long,
    )

    /**
     * 列出设备上所有应用。轻量：不动文件，只查 PM。
     *
     * @param includeSystem 是否包含没被更新过的纯系统应用（默认不含 ——
     *   那些几百个包几乎都不是提取目标，只会把列表淹掉）
     */
    fun list(includeSystem: Boolean = false): List<AppInfo> =
        context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            .asSequence()
            .mapNotNull { p ->
                val ai = p.applicationInfo ?: return@mapNotNull null
                val updatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // 纯系统应用：按参数决定要不要。更新过的系统应用保留 ——
                // 用户分区里有它的新版本，提取它和提取用户应用是一回事
                if (system && !updatedSystem && !includeSystem) return@mapNotNull null
                AppInfo(
                    packageName = p.packageName,
                    label = runCatching {
                        context.packageManager.getApplicationLabel(ai).toString()
                    }.getOrDefault(p.packageName),
                    baseApk = ai.sourceDir ?: return@mapNotNull null,
                    splits = p.splitNames?.mapIndexed { i, _ ->
                        ai.splitSourceDirs?.getOrNull(i) ?: ""
                    }?.filter { it.isNotBlank() } ?: emptyList(),
                    isSystem = system && !updatedSystem,
                    isUpdatedSystem = updatedSystem,
                    versionName = p.versionName ?: "",
                    versionCode = p.longVersionCode,
                    installedAt = p.firstInstallTime,
                    updatedAt = p.lastUpdateTime,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()

    /**
     * 把一个应用的 APK 拷到目标目录。
     *
     * 命名：`<label>_<version>.apk`（label 里的路径分隔符换掉），有 split 时每个
     * split 各存一份并带后缀。返回拷出来的文件列表（含失败说明的 null 不会出现：
     * 拷贝失败直接抛，让界面报）。
     *
     * **同名不覆盖**：目标目录里已经有同名文件就加序号 —— 提取的目的是留档，
     * 静默覆盖掉上一次留档是拿「方便」换「丢东西」。
     */
    fun extract(app: AppInfo, targetDir: File): List<File> {
        val pm = context.packageManager
        val safeLabel = app.label.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { app.packageName }
        val out = ArrayList<File>(1 + app.splits.size)
        val base = uniqueFile(File(targetDir, "${safeLabel}_${app.versionName}.apk"))
        copy(File(app.baseApk), base)
        out += base
        app.splits.forEachIndexed { i, src ->
            val name = app.packageName.substringAfterLast('.') + "_split${i}_" + File(src).name
            val f = uniqueFile(File(targetDir, name))
            copy(File(src), f)
            out += f
        }
        // 标记它是我们提的：将来看到一堆 base_apk 时分得清来源
        runCatching { pm.getPackageInfo(app.packageName, 0) } // 预热无意义，仅确认包仍可查
        return out
    }

    private fun copy(src: File, dst: File) {
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
    }

    private fun uniqueFile(f: File): File {
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = f.extension
        var i = 1
        while (true) {
            val candidate = File(f.parentFile, "$base(${i}).${if (ext.isBlank()) "apk" else ext}")
            if (!candidate.exists()) return candidate
            i++
        }
    }
}
