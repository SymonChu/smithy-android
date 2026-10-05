package dev.smithy.fs

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.IOException

/**
 * FTP 客户端（网络存储第一步，也是目前唯一的一步）。
 *
 * 选 FTP 而不是 SMB/SFTP 的理由：commons-net 是纯 Java、Apache-2.0、几百 KB；
 * SMB 的可靠实现（smbj）拖 BouncyCastle，SFTP（JSch / sshj）要处理主机密钥 ——
 * 都不是「浏览一个局域网目录」该有的成本。
 *
 * 设计：**一个连接对应一个 FtpSession，用完即关**。不做连接池/保活 ——
 * 文件管理器的操作是「进目录、下载」，一次连上做完就断，
 * 服务器端的空闲超时（vsftpd 默认 300s）就咬不到我们。
 *
 * **明文传输**：FTP 协议本身如此。局域网可接受，公网地址请自知。
 * 凭据只存在内存（见 FilesViewModel），不落盘。
 */
class FtpSession private constructor(private val client: FTPClient) : AutoCloseable {

    /** 一个远端条目（和 FsItem 同构，浏览 UI 可复用）。 */
    data class Entry(
        val name: String,
        val path: String,
        val dir: Boolean,
        val size: Long,
        val modified: Long,
    )

    /** 列目录。 */
    fun list(path: String): List<Entry> {
        val files = client.listFiles(path) ?: throw IOException("FTP 列目录失败：${client.replyString}")
        return files
            .filter { it.name != "." && it.name != ".." }
            .map {
                Entry(
                    name = it.name,
                    path = joinPath(path, it.name),
                    dir = it.isDirectory,
                    size = it.size,
                    modified = it.timestamp?.timeInMillis ?: 0L,
                )
            }
            .sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
    }

    /** 下载单个文件到本地 [target]。已存在直接抛错（覆盖由调用方决定）。 */
    fun download(remotePath: String, target: File, size: Long): File {
        require(!target.exists()) { "本地已存在：${target.name}" }
        client.retrieveFileStream(remotePath)?.use { input ->
            target.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("FTP 打开下载流失败：${client.replyString}")
        if (!client.completePendingCommand()) {
            target.delete()
            throw IOException("FTP 下载未完成（连接中断？）")
        }
        // 大小对不上就当失败：FTP 没有校验和，长度是最后的护栏
        if (size > 0 && target.length() != size) {
            val got = target.length()
            target.delete()
            throw IOException("下载不完整：期望 $size 字节，实际 $got")
        }
        return target
    }

    override fun close() {
        runCatching {
            client.logout()
            client.disconnect()
        }
    }

    companion object {
        /**
         * 连接并登录。任何一步失败都抛带原因的 IOException ——
         * 「连不上」的原因（地址错/密码错/服务器不被动模式）必须让人看见。
         */
        fun connect(host: String, port: Int, user: String, password: String): FtpSession {
            val client = FTPClient()
            client.connectTimeout = 10_000
            client.defaultTimeout = 30_000
            try {
                client.connect(host, port)
                val code = client.replyCode
                if (!FTPReply_220(code)) throw IOException("服务器拒绝连接（reply $code）")
                if (!client.login(user, password)) {
                    throw IOException("登录被拒：用户名或密码不对（${client.replyString}）")
                }
                // 手机网络切换频繁，被动模式是唯一现实的选择（主动模式会被 NAT 挡）
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.setControlEncoding("UTF-8")
                return FtpSession(client)
            } catch (e: IOException) {
                runCatching { client.disconnect() }
                throw e
            } catch (e: Exception) {
                runCatching { client.disconnect() }
                throw IOException("连不上 $host:$port —— ${e.message}", e)
            }
        }

        private fun FTPReply_220(code: Int): Boolean = code in 200..299

        fun joinPath(dir: String, name: String): String =
            (if (dir.endsWith("/")) dir else "$dir/") + name
    }
}
