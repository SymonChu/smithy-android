package dev.smithy.fs

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把一批文件/目录打包成一个 zip。
 *
 * 和 [ZipEditor] 的分工：那是「打开已有的 zip 改条目」，这里是「从零创建一个」。
 * 多选打包、模块重打包之外的「塞个文件夹发人」场景都走这。
 *
 * 目录**递归**打包；条目名使用相对路径（selected 的公共前缀剥掉）——
 * 打包 `/sdcard/a/b.txt` 和 `/sdcard/a/c/d.txt` 时，zip 里是 `a/b.txt`、`a/c/d.txt`，
 * 而不是带全盘路径。撞名（不同目录同名文件）后到的加数字后缀，不静默覆盖。
 */
object ZipCreator {

    data class Report(
        val fileCount: Int,
        val dirCount: Int,
        val bytes: Long,
        val target: File,
    )

    /**
     * @param sources 要打包的文件/目录（至少一个）
     * @param target  产物路径。已存在时抛错 —— 覆盖是调用方（界面确认框）的事
     */
    fun create(sources: List<File>, target: File): Report {
        require(sources.isNotEmpty()) { "没有可打包的内容" }
        require(!target.exists()) { "已存在同名文件：${target.name}" }

        // 公共父目录：所有选中项的直接父一致时剥掉它，zip 根就是选中项本身
        val parents = sources.map { it.parentFile?.absolutePath ?: "" }.distinct()
        val stripPrefix = if (parents.size == 1) parents[0] + "/" else ""

        val usedNames = HashSet<String>()
        var fileCount = 0
        var dirCount = 0
        var bytes = 0L

        ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
            fun putFile(f: File, entryName: String) {
                val entry = ZipEntry(entryName)
                entry.time = f.lastModified()
                zip.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                fileCount++
                bytes += f.length()
            }

            fun uniqueName(base: String): String {
                if (usedNames.add(base)) return base
                var i = 1
                while (true) {
                    val candidate = base.substringBeforeLast('.', "") + "($i)." + base.substringAfterLast('.', "")
                    if (usedNames.add(candidate)) return candidate
                    i++
                }
            }

            fun walk(f: File, relName: String) {
                if (f.isDirectory) {
                    val name = uniqueName(relName.trimEnd('/') + "/")
                    zip.putNextEntry(ZipEntry(name))
                    zip.closeEntry()
                    dirCount++
                    f.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { child ->
                        walk(child, name + child.name)
                    }
                } else {
                    putFile(f, uniqueName(relName))
                }
            }

            sources.forEach { src ->
                val rel = src.absolutePath.removePrefix(stripPrefix)
                walk(src, rel)
            }
        }
        return Report(fileCount, dirCount, bytes, target)
    }
}
