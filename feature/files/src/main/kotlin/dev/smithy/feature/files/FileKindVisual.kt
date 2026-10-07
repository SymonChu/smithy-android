package dev.smithy.feature.files

import androidx.compose.ui.graphics.vector.ImageVector
import dev.smithy.design.SmithyIcons

/**
 * 文件页**独有**的表现层映射。
 *
 * 文件类型 → 图标/颜色那一套在 `core:design` 的 `FileKindVisual`（文件页和工作台
 * 共用同一套，不能各抄一份）。这里只放这一页特有的东西。
 */

/**
 * 快捷入口的图标。
 *
 * 按路径里的关键字猜 —— 快捷入口本身只带（标签，路径），而这两者里就写着它是什么。
 * 猜不出来就不给图标（返回 null），不要随便配一个看起来像那么回事的。
 */
fun shortcutIcon(label: String, path: String): ImageVector? {
    val key = (label + " " + path).lowercase()
    return when {
        key.contains("下载") || key.contains("download") -> SmithyIcons.Download
        key.contains("图片") || key.contains("picture") || key.contains("dcim") -> SmithyIcons.KindPicture
        key.contains("音乐") || key.contains("music") -> SmithyIcons.KindAudio
        key.contains("视频") || key.contains("movie") -> SmithyIcons.KindVideo
        key.contains("文档") || key.contains("document") -> SmithyIcons.KindDoc
        key.contains("应用") || key.contains("android") -> SmithyIcons.Apps
        key.contains("安装包") || key.contains("apk") -> SmithyIcons.KindApk
        key.contains("模块") || key.contains("module") -> SmithyIcons.KindModule
        key.contains("包内容") || key.contains("workspace") -> SmithyIcons.Package
        else -> null
    }
}
