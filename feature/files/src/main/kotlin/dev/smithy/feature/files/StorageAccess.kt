package dev.smithy.feature.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 能不能看手机上的文件。
 *
 * **这是文件管理器的底线**：看不到 `/sdcard` 就等于没有文件管理器。
 * 声明了权限不等于拿到了 —— `MANAGE_EXTERNAL_STORAGE` 尤其如此，它必须在系统设置里
 * 由用户手动打开，没有运行时弹窗。只声明不申请，结果就是「打开是空的」，
 * 而用户完全不知道差了什么。
 */
object StorageAccess {

    /** 当前状态。 */
    sealed interface State {
        /** 能读 `/sdcard` 了。 */
        data object Granted : State

        /**
         * Android 11+ 需要用户去系统设置里打开「所有文件访问」。
         *
         * 没有弹窗可以要这个权限，只能把人送到那个页面。
         */
        data object NeedAllFiles : State

        /** Android 10 及以下：普通运行时权限，能弹窗。 */
        data object NeedRuntimePermission : State

        /** 用户永久拒绝了（不再弹窗）。只能去应用详情页里改。 */
        data object Blocked : State
    }

    fun state(ctx: Context): State = when {
        hasAccess(ctx) -> State.Granted
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> State.NeedAllFiles
        else -> State.NeedRuntimePermission
    }

    /** 现在能读外部存储吗。 */
    fun hasAccess(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // 「所有文件访问」是 API 30+ 的正路，系统会直接告诉我们开没开
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 跳到「装了这个应用的那个设置页」。
     *
     * 先试带包名的那个 intent —— 它能直接落到本应用的权限页；有的 ROM 不认，
     * 就退回不带包名的（落到系统设置首页），总比什么都不发生强。
     */
    fun settingsIntent(ctx: Context): Intent {
        val pkgUri = Uri.fromParts("package", ctx.packageName, null)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }
    }

    /** 这句话是给界面上那条提示用的。 */
    fun reasonFor(state: State): String = when (state) {
        State.Granted -> ""
        State.NeedAllFiles ->
            "现在只能看应用自己的目录，看不到手机上的文件。Android 11 起「所有文件访问」" +
                "没有弹窗，要去系统设置里手动打开。点右边那个按钮会直接跳到那一页"
        State.NeedRuntimePermission ->
            "现在只能看应用自己的目录，看不到手机上的文件。需要存储权限才能读 /sdcard"
        State.Blocked ->
            "存储权限被拒绝了。去应用详情页里手动打开，系统不会再弹窗问第二次"
    }
}
