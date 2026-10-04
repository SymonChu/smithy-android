package dev.smithy.feature.files

import java.io.File
import java.io.RandomAccessFile

/**
 * 按偏移读写文件的一段。
 *
 * **为什么需要它**：十六进制编辑器不能把整个文件读进内存 —— 被改的往往是几十上百 MB
 * 的 apk / so，整读既慢又可能直接 OOM。所以只在需要的位置读一窗。
 *
 * 两种模式各用各的路子：
 *  - **普通**：`RandomAccessFile`，真正的随机访问，不用读前面的数据
 *  - **root**：`dd`（经临时文件中转），因为应用身份读不了那些路径
 *
 * 分开写而不是「都走 root」：普通路径走 `dd` 要 fork 一个 shell 进程，
 * 读一个小文件比直接用 `RandomAccessFile` 慢得多。
 */
object FileWindow {

    /** 文件大小。读不到返回 null（不存在、没权限）。 */
    fun size(path: String, root: Boolean): Long? =
        if (root) {
            RootFs.sizeOf(path)
        } else {
            runCatching { File(path).length().takeIf { File(path).isFile } }.getOrNull()
        }

    /**
     * 读 [offset] 起的 [length] 字节。
     *
     * 返回的数组**可能短于 [length]**（读到文件尾了），这是正常情况 ——
     * 调用方按实际长度渲染，而不是把短的当成错误。
     */
    fun read(path: String, offset: Long, length: Int, root: Boolean): ByteArray? {
        if (offset < 0 || length <= 0) return null
        if (root) return RootFs.readWindow(path, offset, length)

        return runCatching {
            RandomAccessFile(path, "r").use { raf ->
                if (offset >= raf.length()) return ByteArray(0)
                raf.seek(offset)
                // 只读到文件尾为止：seek 到末尾之外再 read 会返回 -1，
                // 而 new ByteArray(length) 会留下一片 0 —— 那看起来像真实数据
                val want = minOf(length.toLong(), raf.length() - offset).toInt()
                val buf = ByteArray(want)
                raf.readFully(buf)
                buf
            }
        }.getOrNull()
    }

    /**
     * 从 [offset] 起覆盖写 [bytes]。
     *
     * **不改文件长度** —— 只覆盖已有区间，越界直接失败（不把文件撑大）。
     * 见 `HexEdit` 的类注释：变长会移动后面所有字节，对带内部偏移表的格式等于改坏。
     */
    fun write(path: String, offset: Long, bytes: ByteArray, root: Boolean): Boolean {
        if (offset < 0 || bytes.isEmpty()) return false
        if (root) return RootFs.writeAt(path, offset, bytes)

        return runCatching {
            RandomAccessFile(path, "rw").use { raf ->
                if (offset + bytes.size > raf.length()) return false
                raf.seek(offset)
                raf.write(bytes)
                true
            }
        }.getOrDefault(false)
    }
}
