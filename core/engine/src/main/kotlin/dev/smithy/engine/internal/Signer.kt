package dev.smithy.engine.internal

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
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
        val r = ApkVerifier.Builder(apk).build().verify()
        val schemes = buildList {
            if (r.isVerifiedUsingV1Scheme) add(1)
            if (r.isVerifiedUsingV2Scheme) add(2)
            if (r.isVerifiedUsingV3Scheme) add(3)
        }
        val messages = (r.errors + r.warnings).map { it.toString() }
        return VerifyResult(r.isVerified, schemes, messages)
    }
}
