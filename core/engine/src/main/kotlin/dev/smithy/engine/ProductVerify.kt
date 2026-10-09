package dev.smithy.engine

import com.reandroid.apk.ApkModule
import dev.smithy.engine.internal.ApkProjectImpl
import dev.smithy.engine.internal.Signer
import dev.smithy.engine.internal.ZipAlignment
import dev.smithy.engine.internal.ZipOffsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 一条校验结论。[level] 为 BAD 时表示「这个产物不该装」。 */
data class ProductCheck(val label: String, val level: CheckLevel, val detail: String)

data class ProductReport(
    val apkPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val checks: List<ProductCheck>,
) {
    /** 有没有「装上去一定出问题」的项。WARN 不拦（例如「需要先卸载」是正常的）。 */
    val canInstall: Boolean get() = checks.none { it.level == CheckLevel.BAD }

    val warnCount: Int get() = checks.count { it.level == CheckLevel.WARN }
}

/**
 * 产物验证：**对打好的那个 APK 本身**再查一遍，而不是相信「刚才的步骤都成功了」。
 *
 * ## 为什么值得单独做一遍
 *
 * 「重打包成功 + 签名成功」只说明每一步没报错，不说明产物自洽：清单可能写坏了、
 * 资源表可能序列化失败、改动可能没落进产物、对齐可能在被签名重写 zip 时丢掉
 * （这个坑真踩过：v1 签名自己重写了一版 zip 却没带对齐，装到手机上才报 res=-2）。
 *
 * 这一遍查的都是**装机那一刻才会暴露**的东西，所以放在打包之后、安装之前自动跑，
 * 结论直接摆在界面上。查失败不等于操作失败 —— 是「别装了，先看这条」。
 *
 * [expectedPackage] / [expectedLabel] 是「用户以为改成了什么」：从工程当前状态传进来。
 * 产物里读到的不一致时，说明改动没落进产物 —— 这种错原本只能靠人眼发现。
 */
object ProductVerify {

    suspend fun of(
        apk: File,
        expectedPackage: String? = null,
        expectedLabel: String? = null,
        installedCertSha256: String? = null,
    ): ProductReport = withContext(Dispatchers.IO) {
        val checks = mutableListOf<ProductCheck>()
        // 条目名走 ZipFile：只是列名字，不需要 ARSCLib 参与（拿它列条目要 refresh 一遍，白花时间）
        val entries: List<String> = runCatching {
            ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.toList() }
        }.getOrDefault(emptyList())

        val module = runCatching { ApkModule.loadApkFile(apk) }.getOrNull()
        if (module == null) {
            checks += ProductCheck("包结构", CheckLevel.BAD, "产物不是能打开的 APK —— 不要装")
            return@withContext ProductReport(
                apkPath = apk.absolutePath,
                sizeBytes = apk.length(),
                sha256 = sha256(apk),
                checks = checks,
            )
        }

        module.use { m ->
            val manifest = runCatching { m.getAndroidManifest() }.getOrNull()
            if (manifest == null) {
                checks += ProductCheck("清单", CheckLevel.BAD, "AndroidManifest.xml 读不出来")
            } else {
                val manifestPackage = runCatching { manifest.getPackageName() }.getOrNull()
                val manifestLabel = runCatching { manifest.getApplicationLabelString() }.getOrNull()

                val pkgOk = expectedPackage == null || manifestPackage == expectedPackage
                checks += if (pkgOk) {
                    ProductCheck("清单", CheckLevel.OK, "包名 ${manifestPackage ?: "—"}，与改动一致")
                } else {
                    ProductCheck(
                        "清单",
                        CheckLevel.BAD,
                        "产物里的包名是 ${manifestPackage ?: "—"}，但你改成了 $expectedPackage —— 改动没落进产物",
                    )
                }

                if (expectedLabel != null && !manifestLabel.isNullOrBlank() && manifestLabel != expectedLabel) {
                    checks += ProductCheck(
                        "应用名",
                        CheckLevel.WARN,
                        "产物里读到的应用名是「$manifestLabel」，界面显示的是「$expectedLabel」" +
                            "（可能是资源引用没更新，装上去看着还是旧名字）",
                    )
                }
            }

            val hasArsc = entries.any { it == "resources.arsc" }
            if (hasArsc) {
                val table = runCatching { m.getTableBlock() }.getOrNull()
                checks += if (table != null) {
                    ProductCheck("资源表", CheckLevel.OK, "resources.arsc 可解析")
                } else {
                    ProductCheck("资源表", CheckLevel.BAD, "resources.arsc 存在但读不出来 —— 装上去会崩在启动")
                }
            } else {
                checks += ProductCheck("资源表", CheckLevel.WARN, "产物里没有 resources.arsc")
            }
        }

        // ── 签名 ──
        val verify = runCatching { Signer.verify(apk) }.getOrNull()
        if (verify == null) {
            checks += ProductCheck("签名", CheckLevel.WARN, "读不出签名信息")
        } else if (!verify.valid) {
            checks += ProductCheck(
                "签名",
                CheckLevel.BAD,
                "验签没过：${verify.messages.firstOrNull() ?: "原因未知"}",
            )
        } else {
            val schemes = verify.schemes.sorted().joinToString("+") { "v$it" }
            checks += ProductCheck("签名", CheckLevel.OK, "验签通过（$schemes）")
        }

        // ── 装机冲突：签名与机上已装的那份不同 → 必须卸载才能装 ──
        val certSha = runCatching { ApkProjectImpl.readSignatures(apk).firstOrNull()?.sha256 }.getOrNull()
        if (certSha != null && installedCertSha256 != null &&
            !certSha.equals(installedCertSha256, ignoreCase = true)
        ) {
            checks += ProductCheck(
                "装机",
                CheckLevel.WARN,
                "手机上已装的那个包是另一把证书签的 → 安装会报签名冲突，需要先卸载（会丢它的数据）",
            )
        }

        // ── zip 对齐：装机时 res=-2 的唯一来源 ──
        val offsets = ZipOffsets.read(apk)
        if (offsets.isEmpty()) {
            checks += ProductCheck("对齐", CheckLevel.WARN, "读不出条目偏移（ZIP64 或结构异常），没法校验对齐")
        } else {
            val bad = offsets.filter { e ->
                e.method == ZipAlignment.STORED && e.dataOffset % ZipAlignment.alignmentFor(e.name) != 0L
            }
            checks += if (bad.isEmpty()) {
                ProductCheck("对齐", CheckLevel.OK, "未压缩条目按 16KB（.so）/ 4B（资源表）对齐")
            } else {
                ProductCheck(
                    "对齐",
                    CheckLevel.BAD,
                    "有 ${bad.size} 个未压缩条目没对齐（${bad.take(2).joinToString("、") { it.name }}）" +
                        " —— 装机会报 Failed to extract native libraries, res=-2",
                )
            }
        }

        ProductReport(
            apkPath = apk.absolutePath,
            sizeBytes = apk.length(),
            sha256 = sha256(apk),
            checks = checks,
        )
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
