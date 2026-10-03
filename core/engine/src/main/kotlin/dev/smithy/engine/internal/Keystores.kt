package dev.smithy.engine.internal

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 内置签名密钥。
 *
 * **必须持久化**，这是这个类唯一重要的设计约束：同一个包反复改包时签名指纹要保持一致，
 * 否则新包与已装的老包签名不匹配，系统直接拒绝安装 —— 用户看到的只是"应用未安装"，
 * 原因极难猜到。所以密钥落在调用方给的目录里，只在文件不存在时才生成。
 *
 * 用自签证书（RSA 2048 / SHA256withRSA）。它不是"调试证书"，但与 Android 官方的
 * debug 证书不同，因此 `isDebug` 判定为 false —— 不会因为 debug 标记被某些包拒装。
 */
internal object Keystores {

    const val ALIAS = "smithy"
    private const val PASSWORD = "smithy"

    /** BouncyCastle 只作为 Provider 实例用，不注册到全局（Android 上注册全局会污染其他组件） */
    private val BC = BouncyCastleProvider()

    data class Material(
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>,
        /** 这次是新生成的（true）还是复用了已存在的（false），用于日志与 UI 提示 */
        val created: Boolean,
    )

    fun loadOrCreate(dir: File): Material {
        val file = File(dir, "smithy-debug.p12")
        if (file.isFile) {
            // 读不出来（文件截断 / 密码变过）就当没有，重新生成：
            // 让签名失败比让整个流程卡住更糟，而重新生成至少能继续用
            runCatching { return read(file) }
        }
        dir.mkdirs()
        return generate(file)
    }

    private fun read(file: File): Material {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, PASSWORD.toCharArray()) }
        val key = ks.getKey(ALIAS, PASSWORD.toCharArray()) as? PrivateKey
            ?: throw IllegalStateException("keystore 里没有别名 $ALIAS 的私钥")
        val chain = ks.getCertificateChain(ALIAS)?.map { it as X509Certificate }
        require(!chain.isNullOrEmpty()) { "keystore 里没有 $ALIAS 的证书链" }
        return Material(key, chain, created = false)
    }

    private fun generate(file: File): Material {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val subject = X500Name("CN=Smithy Debug, O=Smithy, OU=Self-signed, C=CN")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - day),                 // 往前一天，避开设备时钟偏差
            Date(now + 30L * 365 * day),
            subject,
            pair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
        }

        val cert = JcaX509CertificateConverter()
            .setProvider(BC)
            .getCertificate(
                certBuilder.build(
                    JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(pair.private),
                ),
            )

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, pair.private, PASSWORD.toCharArray(), arrayOf(cert))
        file.outputStream().use { ks.store(it, PASSWORD.toCharArray()) }

        return Material(pair.private, listOf(cert), created = true)
    }
}
