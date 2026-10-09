package dev.smithy.fs

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * 「这个包在手机上装着吗？装的那份是谁签的？」
 *
 * ## 为什么要单独问一句
 *
 * 改包工具最后一步永远是装机，而**装机时唯一会撞的墙是签名**：
 * 系统不允许用另一把证书覆盖安装同一个包名，会直接报
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。用户在装机那一刻才知道，还得回头
 * 猜是哪里的问题。
 *
 * 这信息在打包阶段就能拿到（查一下已装版本的证书），所以放在这里，
 * 让上游把「装机会要求先卸载」提前写进验证结论里。
 *
 * 证书摘要**只取 SHA-256 的十六进制**，和引擎读出来的那份（apksig 给的 digest）
 * 是同一套比较口径 —— 两边格式不一致的话，比出来的「不同」全是假的。
 */
object InstalledApp {

    /** 已装版本是否可查。查不到（没装 / 被限制查询）返回 false。 */
    fun isInstalled(context: Context, packageName: String): Boolean =
        info(context, packageName) != null

    /** 已装版本的签名证书 SHA-256（小写十六进制）；没装或读不到返回 null。 */
    fun certSha256(context: Context, packageName: String): String? {
        val pi = info(context, packageName) ?: return null
        val bytes = signerBytes(pi) ?: return null
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun info(context: Context, packageName: String): PackageInfo? = runCatching {
        val pm = context.packageManager
        // Android 13 起 GET_SIGNING_CERTIFICATES 才是有效标志；低版本用 GET_SIGNATURES
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        } else {
            null
        }
        @Suppress("DEPRECATION")
        if (flags != null) {
            pm.getPackageInfo(packageName, flags)
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    private fun signerBytes(pi: PackageInfo): ByteArray? {
        // 28+ 走 signingInfo；26/27 只有已废弃的 signatures
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = pi.signingInfo ?: return null
            val signers = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return signers?.firstOrNull()?.toByteArray()
        }
        @Suppress("DEPRECATION")
        return pi.signatures?.firstOrNull()?.toByteArray()
    }
}
