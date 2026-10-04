package dev.smithy.engine.internal

import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 组装 JAR 签名用的 PKCS#7 `SignedData`。
 *
 * 结构（RFC 2315，v1 签名只用得到最小的一份）：
 *
 * ```
 * ContentInfo ::= SEQUENCE {
 *   contentType   OBJECT IDENTIFIER,        -- signedData
 *   content   [0] EXPLICIT SignedData }
 * SignedData ::= SEQUENCE {
 *   version            INTEGER,
 *   digestAlgorithms   SET OF AlgorithmIdentifier,
 *   contentInfo        SEQUENCE {           -- 被签的内容
 *     contentType  OBJECT IDENTIFIER,       -- data
 *     content  [0] EXPLICIT OCTET STRING },
 *   certificates   [0] IMPLICIT SET OF Certificate,
 *   signerInfos        SET OF SignerInfo }
 * ```
 *
 * **签的是 `.SF` 的字节，但不把它塞进 `contentInfo`**（detached 形式）——
 * `jarsigner` 就是这么做的，Android 的 `JarVerifier` 也按这个读：它拿 `signerInfo`
 * 的 `encryptedDigest` 与文件 `.SF` 对照校验。
 */
internal object Pkcs7 {

    private val OID_SIGNED_DATA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02,
    )
    private val OID_DATA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01,
    )
    private val OID_SHA256 = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)
    private val OID_RSA_ENCRYPTION = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
    )

    /**
     * @param signedBytes 被签的内容（`.SF` 的字节）
     */
    fun generate(
        signedBytes: ByteArray,
        privateKey: PrivateKey,
        certificates: List<X509Certificate>,
    ): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signedBytes)
            sign()
        }

        val digestAlgorithm = Der.sequence(Der.oid(OID_SHA256), Der.tlv(0x05, ByteArray(0)))
        val signerInfo = Der.sequence(
            Der.integer(1),
            issuerAndSerial(certificates.first()),
            digestAlgorithm,
            Der.sequence(Der.oid(OID_RSA_ENCRYPTION), Der.tlv(0x05, ByteArray(0))),
            Der.octetString(signature),
        )

        // detach：content 留空。jarsigner 与 Android 的校验都按这个读
        val contentInfo = Der.sequence(
            Der.oid(OID_DATA),
            Der.context(0, Der.octetString(ByteArray(0))),
        )

        val signedData = Der.sequence(
            Der.integer(1),
            Der.set(digestAlgorithm),
            contentInfo,
            // [0] IMPLICIT：内容直接是若干 Certificate 的拼接，不再套一层 SET
            Der.context(0, Der.concat(*certificates.map { it.encoded }.toTypedArray())),
            Der.set(signerInfo),
        )

        return Der.sequence(
            Der.oid(OID_SIGNED_DATA),
            Der.context(0, signedData),
        )
    }

    /**
     * `IssuerAndSerialNumber`：谁签的。
     *
     * **`issuerX500Principal.encoded` 本身就是完整的 DER SEQUENCE（Name）** ——
     * 再套一层就成了 `SEQUENCE(SEQUENCE(...))`，校验方按 Name 解析时直接崩，
     * 而症状是 `improperly specified input name`，完全看不出跟这里有关。
     *
     * 序列号用原始字节：X.509 允许 20 字节，`toLong()` 会溢出。
     */
    private fun issuerAndSerial(cert: X509Certificate): ByteArray =
        Der.sequence(
            cert.issuerX500Principal.encoded,
            Der.integerFromBytes(cert.serialNumber.toByteArray()),
        )
}
