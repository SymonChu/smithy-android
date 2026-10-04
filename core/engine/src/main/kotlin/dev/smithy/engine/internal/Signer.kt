package dev.smithy.engine.internal

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import dev.smithy.engine.VerifyResult
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * 签名与验签，直接走 apksig（AGP 自己也用它）。
 *
 * 两个方案上的取舍：
 *  - **v4 关掉**：它要求额外产出一个 `.idsig` 文件，而那个文件只有增量安装
 *    （`adb install --incremental`）才用得上 —— 手机上自用安装不需要，徒增一个要管理的产物。
 *  - **v1 关掉**（由调用方保证，见 `ApkProjectImpl.sign`）：本文件的 v1 路径可用，
 *    但当前依赖的 apksig-android 4.4.0 一生成 v1 清单就 NPE，所以上游不给它开。
 *    这个类保留按方案签名的能力，将来升级依赖后只需改调用方。
 */
internal object Signer {

    fun sign(
        input: File,
        output: File,
        privateKey: PrivateKey,
        certificates: List<X509Certificate>,
        minSdk: Int,
        schemes: Set<Int>,
        label: String = "smithy",
    ): File {
        val signerConfig = ApkSigner.SignerConfig.Builder(label, privateKey, certificates).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            // minSdk 决定 apksig 挑哪套方案、以及 v1 是否必需，必须传对
            .setMinSdkVersion(minSdk.coerceAtLeast(1))
            .setV1SigningEnabled(1 in schemes)
            .setV2SigningEnabled(2 in schemes)
            .setV3SigningEnabled(3 in schemes)
            .setV4SigningEnabled(false)
            // **保留「其他签名者」的签名文件**。
            //
            // 我们自写的 v1（`SMITHY.SF` / `SMITHY.RSA`）在 apksig 眼里属于别的签名者，
            // 不开这个开关它会把这些文件删干净（实测：删完老系统直接装不上，报
            // `Missing META-INF/MANIFEST.MF`）。
            //
            // 而 v1 文件**必须先于 v2 就位**：v2 的签名覆盖整个包，先加 v2 再往包里加文件
            // 会让 v2 失效。顺序上没得选，所以只能靠这个开关把 v1 留住。
            .setOtherSignersSignaturesPreserved(true)
            .setCreatedBy("smithy")
            .build()
            .sign()
        return output
    }

    fun verify(apk: File): VerifyResult {
        // 读包自己声明的支持范围：真机校验就落在这个区间里
        val declared = runCatching {
            ApkModule.loadApkFile(apk).use { m ->
                val mf = m.getAndroidManifest()
                (mf.getMinSdkVersion() ?: 1) to (mf.getTargetSdkVersion() ?: 35)
            }
        }.getOrNull()

        // **判据要与真机一致**：只检查这个包声称支持的那些平台。
        //
        // 之前不设范围，apksig 会把「只认 v1 的老平台」也一起检查，于是报出
        // 「MANIFEST 主属性段摘要不匹配」（Expected/actual 都是 null）——
        // 而官方 apksigner 对同一个产物判 Verifies。两边判据不同，界面就会
        // 对一个好包显示「验签失败」。
        val main = ApkVerifier.Builder(apk)
            .apply {
                declared?.let { (lo, hi) ->
                    setMinCheckedPlatformVersion(lo)
                    setMaxCheckedPlatformVersion(hi)
                }
            }
            .build()
            .verify()

        // v1 要**单独**再验一遍，把范围压到只认 v1 的系统上：
        // 上面那遍在 minSdk >= 24 的包上压根不看 v1，不单独验就会漏报。
        //
        // 判据用「识别出几个签名者」而不是 `isVerifiedUsingV1Scheme`：
        // apksig 对 targetSdk 35 的包会多报一条「MANIFEST 主属性段摘要不匹配」
        // （Expected/actual 都是 null），而官方 apksigner 对同一产物判 v1 通过。
        // 那条是库自身的误报，却会把 `isVerifiedUsingV1Scheme` 拉成 false ——
        // 用它当判据就会对着一个好包显示「验签失败」。签名者识别不受它影响。
        val v1Signers = runCatching {
            ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(21)
                .setMaxCheckedPlatformVersion(23)
                .build()
                .verify()
                .v1SchemeSigners
                .size
        }.getOrDefault(0)

        val schemes = buildList {
            if (v1Signers > 0) add(1)
            if (main.isVerifiedUsingV2Scheme) add(2)
            if (main.isVerifiedUsingV3Scheme) add(3)
        }
        val messages = (main.errors + main.warnings).map { it.toString() }

        // 判定：**至少一种方案有效就算通过** —— 这正是 apksigner 的判据
        // （它对同一个产物也判 Verifies）。
        //
        // 不直接用 `main.isVerified`：apksig 对 targetSdk 35 的包会额外报一条
        // 「MANIFEST 主属性段摘要不匹配」（Expected/actual 都是 null），
        // 那条是库自身的误报，却会把 `isVerified` 拉成 false —— 用它当判据，
        // 界面就会对着一个好包显示「验签失败」。签名坏掉时这里会是空集，
        // 判据依然有效。
        return VerifyResult(schemes.isNotEmpty(), schemes, messages)
    }
}
