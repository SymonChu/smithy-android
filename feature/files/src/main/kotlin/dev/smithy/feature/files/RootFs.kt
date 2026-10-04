package dev.smithy.feature.files

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 用 root shell 访问文件系统。
 *
 * **为什么需要**：普通文件 API 只能看应用私有目录和用户授权过的目录 —— 系统分区、
 * 别的应用的数据、`/data/adb/modules`（Zygisk 模块住这儿）一概进不来。
 * 有 root 却用不上，那 root 就白给了。
 *
 * 全部走 `Shell.cmd`（libsu），**不碰 `java.io.File` 的判断方法**：后者对这些路径
 * 要么直接拒绝，要么给出错误答案（`canRead()` 在 root 下也不可信）。
 */
object RootFs {

    /** 有没有拿到 root 授权。 */
    fun isGranted(): Boolean =
        runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)

    /**
     * 列目录。
     *
     * 用 `ls -la` 而不是 `find -printf`：后者在 toybox / busybox 上支持不一，而 `ls -la`
     * 到哪都有。但它的**输出格式各版本有差异**（日期样式、链接数、属主显示都可能不同），
     * 所以解析刻意宽松：只依赖两条稳定事实（权限串在最前、名字在最后），中间尽力取。
     * 硬按固定列数切会在某些设备上整列全空 —— 那比少一个大小字段糟得多。
     */
    fun list(path: String): List<FsItem> =
        Shell.cmd("ls -la ${q(path)} 2>&1").exec().out.mapNotNull { parseLine(path, it) }

    private fun parseLine(dir: String, line: String): FsItem? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("total ") || t.startsWith("ls:")) return null
        val perm = t.substringBefore(' ')
        if (perm.length < 10) return null
        if (perm[0] != '-' && perm[0] != 'd' && perm[0] != 'l') return null

        val isDir = perm[0] == 'd'
        // 名字是最后一段；软链接会带 " -> target"，切掉
        val name = t.substringAfterLast(' ').substringBefore(" -> ")
        if (name == "." || name == "..") return null

        // 大小：常见布局下是倒数第 4 段（权限 链接 属主 属组 大小 月 日 时间 名字）。
        // 取不到就记 0 —— 大小只是展示项，不值得为它丢掉整个条目。
        val parts = t.split(Regex("\\s+"))
        val size = parts.getOrNull(parts.size - 4)?.toLongOrNull() ?: 0L

        return FsItem(
            name = name,
            path = if (dir.endsWith("/")) dir + name else "$dir/$name",
            dir = isDir,
            size = size,
            modified = 0L, // ls 的日期格式太杂，解析它不划算；要准确时间就用 stat
        )
    }

    /** 读一个文件。先让 root 抄到临时文件，再按普通方式读回来。 */
    fun read(path: String): ByteArray? = runCatching {
        val tmp = File.createTempFile("rootfs-r", ".bin")
        Shell.cmd("cat ${q(path)} > ${q(tmp.absolutePath)} 2>/dev/null").exec()
        val bytes = if (tmp.length() > 0) tmp.readBytes() else null
        tmp.delete()
        bytes
    }.getOrNull()

    /** 写一个文件。先写临时文件，再让 root 搬过去（这样内容不用经 shell 转义）。 */
    fun write(path: String, bytes: ByteArray): Boolean = runCatching {
        val tmp = File.createTempFile("rootfs-w", ".bin")
        tmp.writeBytes(bytes)
        val r = Shell.cmd("cp ${q(tmp.absolutePath)} ${q(path)} 2>&1").exec()
        tmp.delete()
        r.isSuccess
    }.getOrDefault(false)

    /** 删除（目录用 rm -rf）。 */
    fun delete(path: String, isDir: Boolean): Boolean = runCatching {
        val flag = if (isDir) "-rf" else "-f"
        Shell.cmd("rm $flag ${q(path)} 2>&1").exec().isSuccess
    }.getOrDefault(false)

    /** 复制 / 移动。[move] 为真时用 `mv`。 */
    fun transfer(from: String, to: String, move: Boolean): Boolean = runCatching {
        val cmd = if (move) "mv" else "cp -r"
        Shell.cmd("$cmd ${q(from)} ${q(to)} 2>&1").exec().isSuccess
    }.getOrDefault(false)

    /** 重新挂载成可写（改系统分区前要）。没挂载成功时返回 false，别硬写。 */
    fun remountRw(path: String): Boolean = runCatching {
        Shell.cmd("mount -o rw,remount ${q(path)} 2>&1 || mount -o rw,remount / 2>&1")
            .exec().isSuccess
    }.getOrDefault(false)

    /** 新建目录。已存在时返回 false（**不静默当成成功**）。 */
    fun mkdir(path: String): Boolean = runCatching {
        Shell.cmd("mkdir ${q(path)} 2>&1").exec().isSuccess
    }.getOrDefault(false)

    /** 新建空文件。已存在时返回 false。 */
    fun touch(path: String): Boolean = runCatching {
        Shell.cmd("touch ${q(path)} 2>&1").exec().isSuccess
    }.getOrDefault(false)

    /**
     * 改权限。`mode` 是八进制串（如 `644`）。
     *
     * 格式校验放在调用方（VM）：那里能给一句人话的提示，而丢给 shell 只会回一段
     * 看不懂的 stderr。
     */
    fun chmod(path: String, mode: String): Boolean = runCatching {
        Shell.cmd("chmod ${q(mode)} ${q(path)} 2>&1").exec().isSuccess
    }.getOrDefault(false)

    /**
     * 读权限与属主，形如 `644 root root`。
     *
     * 用 `stat -c`：它在 toybox 和 busybox 上都有（GNU 的 `--format` 不一定）。
     * 取不到返回 null —— 界面上不显示这一块，而不是瞎猜一个值出来。
     */
    fun stat(path: String): Triple<String, String, String>? = runCatching {
        val line = Shell.cmd("stat -c '%a %U %G' ${q(path)} 2>/dev/null")
            .exec().out.firstOrNull()?.trim().orEmpty()
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) null else Triple(parts[0], parts[1], parts[2])
    }.getOrNull()

    /** 在 shell 里安全引用一个路径（单引号包裹，内部的引号转义）。 */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
