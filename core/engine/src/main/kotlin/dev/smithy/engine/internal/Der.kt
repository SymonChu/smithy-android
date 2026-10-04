package dev.smithy.engine.internal

import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 最小 DER 编码器。
 *
 * 只为 v1 签名里那份 PKCS#7 服务，所以只实现用得到的几种类型。
 *
 * **为什么自己写**：生成 PKCS#7 的标准做法是 BouncyCastle，但为了签一个文件引入几 MB
 * 依赖不划算；而 Java 标准库没有公开的「生成 PKCS#7」API（`sun.security.pkcs` 是内部
 * API，Android 上也不存在）。规范本身很短，自己写反而看得清每一步。
 *
 * 正确性由 `ApkVerifier` 闭环验证（它会真的按 v1 规范校验产物），不靠肉眼。
 */
internal object Der {

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /**
     * 一个 TLV：标签 + 长度 + 内容。
     *
     * DER 的长度字段分档：≤127 一个字节；更长时首字节 `0x80 | 字节数`。
     * 证书动辄上千字节，长形式是常态，只写短形式会在证书稍大时就崩。
     */
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n <= 0xFF -> {
                out.write(0x81)
                out.write(n)
            }
            n <= 0xFFFF -> {
                out.write(0x82)
                out.write((n shr 8) and 0xFF)
                out.write(n and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((n shr 16) and 0xFF)
                out.write((n shr 8) and 0xFF)
                out.write(n and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(*parts))

    fun octetString(bytes: ByteArray): ByteArray = tlv(0x04, bytes)

    /** 内容必须是已经 DER 编码过的 OID 字节。 */
    fun oid(encoded: ByteArray): ByteArray = tlv(0x06, encoded)

    /** INTEGER，最小补码表示。 */
    fun integer(value: Long): ByteArray {
        val body = ByteArrayOutputStream()
        if (value == 0L) {
            body.write(0)
        } else {
            val negative = value < 0
            val tmp = ArrayList<Int>()
            var v = value
            while (v != 0L && (!negative || v != -1L)) {
                tmp.add((v and 0xFF).toInt())
                v = v shr 8
            }
            // 最高位是 1 的话，正数要补一个 0x00，否则会被读成负数
            if (!negative && (tmp.lastOrNull() ?: 0) and 0x80 != 0) tmp.add(0)
            tmp.reversed().forEach { body.write(it) }
        }
        return tlv(0x02, body.toByteArray())
    }

    /**
     * INTEGER，内容是原始大端字节。
     *
     * 证书序列号最长 20 字节，`toLong()` 会溢出，所以单独走这条路。前导 0 要去掉
     * （DER 要求最短表示），但去掉后最高位是 1 的话得补回一个 0x00，否则被读成负数。
     */
    fun integerFromBytes(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return tlv(0x02, byteArrayOf(0))
        var start = 0
        while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
        val body = raw.copyOfRange(start, raw.size)
        val out = if ((body[0].toInt() and 0x80) != 0) byteArrayOf(0) + body else body
        return tlv(0x02, out)
    }

    /** UTCTime，形如 `YYMMDDHHMMSSZ`（够用到 2049 年，之后该用 GeneralizedTime）。 */
    fun utcTime(date: Date): ByteArray {
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    /** 上下文相关标签 `[n]`；默认是构造类型（里面还有结构）。 */
    fun context(n: Int, content: ByteArray, constructed: Boolean = true): ByteArray =
        tlv(if (constructed) 0xA0 or n else 0x80 or n, content)
}
