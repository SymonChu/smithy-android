package dev.smithy.engine.internal

import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * v1（JAR）签名，自己实现。
 *
 * **为什么不用 apksig**：它的 v1 路径在这个 Android 移植版上一生成 `MANIFEST.MF` 就
 * NPE，而那个库最新版就是 4.4.0，升级无望（见 docs/08 第八节）。而 v1 本身很简单：
 * 三个文本文件加一次签名，规范完全公开。
 *
 * 三个文件：
 * 1. `META-INF/MANIFEST.MF`：每个条目「名字 + 内容摘要」
 * 2. `META-INF/SMITHY.SF` ：对 MANIFEST.MF 整体、以及每个条目块的摘要
 * 3. `META-INF/SMITHY.RSA`：对 `.SF` 的 PKCS#7 签名
 *
 * **两处必须精确**（写错就装不上，而且报错完全看不出原因）：
 * - 换行是 `\r\n` 而不是 `\n`；
 * - 每行上限 **72 字节**，超出要折行，且续行以**一个空格**开头。
 *
 * 对错不靠肉眼：跑完用 `ApkVerifier` 验，它报 `isVerifiedUsingV1Scheme` 才算成。
 */
internal object V1Signer {

    private const val LINE_LIMIT = 72
    private const val MANIFEST_NAME = "META-INF/MANIFEST.MF"
    private const val SF_NAME = "META-INF/SMITHY.SF"
    private const val RSA_NAME = "META-INF/SMITHY.RSA"

    /** 用 SHA-256 而不是 JAR 默认的 SHA1：后者在不少环境已被判为弱算法。 */
    private const val DIGEST = "SHA-256"
    private const val DIGEST_ATTR = "SHA-256-Digest"

    /**
     * MANIFEST 的主属性段。
     *
     * 抽成常量是因为 `.SF` 要对它**单独**算一份摘要，而那份摘要要求与 MANIFEST 里这段
     * 逐字节一致 —— 两处各写一遍迟早会漂移。
     */
    private const val MANIFEST_MAIN = "Manifest-Version: 1.0\r\nCreated-By: Smithy\r\n\r\n"

    fun sign(
        input: File,
        output: File,
        privateKey: PrivateKey,
        certificates: List<X509Certificate>,
    ): File {
        require(certificates.isNotEmpty()) { "v1 签名需要至少一张证书" }

        // 第一遍：只算摘要，不把条目内容留在内存里（包可能几十 MB，so 尤其大）
        val digests = LinkedHashMap<String, ByteArray>()
        ZipFile(input).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (isSignatureFile(entry.name)) continue
                val md = MessageDigest.getInstance(DIGEST)
                zip.getInputStream(entry).use { ins ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                digests[entry.name] = md.digest()
            }
        }

        // MANIFEST.MF：每块是「Name 行 + 摘要行 + 空行」
        val manifest = buildString {
            append(MANIFEST_MAIN)
            digests.forEach { (name, digest) ->
                append(fold("Name: $name"))
                append(fold("$DIGEST_ATTR: ${b64(digest)}"))
                append("\r\n")
            }
        }.toByteArray(Charsets.UTF_8)

        // .SF：对整份 MANIFEST 的摘要，加对其中每个条目块的摘要。
        // **条目块的摘要要把「那两行连同结尾空行」一起算** —— 少算一个换行就校验不过。
        val sf = buildString {
            append("Signature-Version: 1.0\r\n")
            append("Created-By: Smithy\r\n")
            append("SHA-256-Digest-Manifest: ${b64(sha256(manifest))}\r\n")
            // 主属性段单独一份摘要。**少了它 apksig 会判定整个 .SF 无效**，
            // 而报出来的错误是「各条目块的摘要不匹配、两边都显示 null」——
            // 完全看不出根因在这里。jarsigner 生成的 .SF 里也有这一条。
            append(
                fold(
                    "SHA-256-Digest-Manifest-Main-Attributes: " +
                        b64(sha256(MANIFEST_MAIN.toByteArray(Charsets.UTF_8))),
                ),
            )
            append("\r\n")
            digests.forEach { (name, digest) ->
                val block = "Name: $name\r\n$DIGEST_ATTR: ${b64(digest)}\r\n\r\n"
                append(fold("Name: $name"))
                append(fold("$DIGEST_ATTR: ${b64(sha256(block.toByteArray(Charsets.UTF_8)))}"))
                append("\r\n")
            }
        }.toByteArray(Charsets.UTF_8)

        val rsa = Pkcs7.generate(sf, privateKey, certificates)

        // 第二遍：条目原样搬运，再把三个签名文件写进去
        val out = ZipOutputStream(output.outputStream().buffered(1 shl 16))
        try {
            ZipFile(input).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (isSignatureFile(entry.name)) continue   // 旧的签名文件丢掉
                    val copy = ZipEntry(entry.name).apply {
                        method = entry.method
                        time = entry.time
                        if (entry.method == ZipEntry.STORED) {
                            // STORED 条目必须自带尺寸与校验和，否则 ZipOutputStream 直接抛
                            // 「STORED entry missing size, compressed size, or crc-32」。
                            // .so 与 resources.arsc 都是 STORED，必然踩中。
                            size = entry.size
                            compressedSize = entry.size
                            crc = entry.crc
                        }
                    }
                    out.putNextEntry(copy)
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            putStored(out, MANIFEST_NAME, manifest)
            putStored(out, SF_NAME, sf)
            putStored(out, RSA_NAME, rsa)
        } finally {
            out.close()
        }
        return output
    }

    /** 签名相关文件：`META-INF/` 下的 MANIFEST / SF / RSA / DSA / EC。 */
    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    /** 三个签名文件都按未压缩写：它们要被直接读，压了没好处。 */
    private fun putStored(out: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }

    /**
     * 按 72 **字节**折行，续行以单个空格开头。
     *
     * 按字节而不是字符算：资源路径里可以有中文，按字符数会算少，而校验方读的是字节。
     * 折行时也不切断多字节字符（UTF-8 的续字节是 `10xxxxxx`），否则解出来是乱码。
     */
    private fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= LINE_LIMIT) return line + "\r\n"

        val sb = StringBuilder()
        var offset = 0
        var first = true
        while (offset < bytes.size) {
            // 续行要多占一个空格的位置
            val capacity = if (first) LINE_LIMIT else LINE_LIMIT - 1
            var take = minOf(capacity, bytes.size - offset)
            while (take > 0 && (bytes[offset + take - 1].toInt() and 0xC0) == 0x80) take--
            if (take == 0) take = minOf(capacity, bytes.size - offset)
            if (!first) sb.append(' ')
            sb.append(String(bytes, offset, take, Charsets.UTF_8))
            sb.append("\r\n")
            offset += take
            first = false
        }
        return sb.toString()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance(DIGEST).digest(bytes)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
