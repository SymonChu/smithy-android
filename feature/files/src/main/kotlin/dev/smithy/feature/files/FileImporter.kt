package dev.smithy.feature.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 把用户从系统选择器挑的文件**拷进**应用私有目录。
 *
 * **为什么不直接改原位置的文件**：外部文档给的是 URI 不是路径，很多东西只吃 `File`；
 * 而且我们读它要反复 seek（apk 是 zip），URI 流不支持。拷一份过来最省事，也顺手
 * 避开了「改动写回用户目录」的权限问题。
 *
 * **`.apk.1` 的处理是刻意的**：聊天软件下载 apk 时常加一个后缀防止被系统直接安装，
 * 于是用户手上是 `foo.apk.1`。按原样拷进来，后面所有「按扩展名判断」的地方都会认不出
 * 这是个 apk —— 而用户的心智里「它就是那个 apk」。所以在拷贝这一步把它修正掉。
 */
object FileImporter {

    /** 拷进 [cacheDir]，返回落地后的文件。名字冲突时加序号。 */
    fun import(context: Context, uri: Uri, cacheDir: File): File {
        val raw = queryName(context, uri) ?: "imported-${System.currentTimeMillis()}.apk"
        val fixed = normalizeName(raw)
        var dest = File(cacheDir, fixed)
        var n = 1
        while (dest.exists()) {
            dest = File(cacheDir, "${fixed.substringBeforeLast('.')}_${n++}.${fixed.substringAfterLast('.', "apk")}")
        }
        context.contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { ins.copyTo(it) }
        } ?: throw IllegalArgumentException("读不到这个文件（可能授权已失效）")
        return dest
    }

    /**
     * 修掉「防安装」后缀：`foo.apk.1` → `foo.apk`、`foo.apk.2` → `foo.apk`。
     *
     * 只在被剥掉的是**纯数字**时才动，免得把 `backup.2024` 之类误伤。
     */
    private fun normalizeName(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val tail = name.substring(dot + 1)
        if (tail.length == 1 && tail[0].isDigit()) {
            val stem = name.substring(0, dot)
            if (stem.endsWith(".apk", ignoreCase = true)) return stem
        }
        return name
    }

    private fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
