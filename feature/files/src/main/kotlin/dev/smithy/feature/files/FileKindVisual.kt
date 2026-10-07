package dev.smithy.feature.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.smithy.design.SmithyIcons
import dev.smithy.fs.FileKind

/**
 * 文件类型 → 图标。
 *
 * **为什么放在 feature 层**：类型分类（[FileKind]）是纯数据，`core:fs` 是纯 JVM 模块、
 * 不依赖 Compose；而「长什么样」是表现层的事。原先 `FileKind` 上挂着 `emoji` 字段 ——
 * 表现层的东西长在了数据模块里，这正是它后来变成 16 个 emoji 的原因（emoji 是当时
 * 唯一不用引依赖就能拿到的东西）。
 *
 * 现在换成 [SmithyIcons] 的单色矢量：跨 ROM 字形一致、能按主题着色、在等宽数字列里
 * 基线也对得齐。
 */
val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.DIR -> SmithyIcons.KindDir
        FileKind.APK -> SmithyIcons.KindApk
        FileKind.ARCHIVE -> SmithyIcons.KindArchive
        FileKind.IMAGE_DISK -> SmithyIcons.KindDisk
        FileKind.PDF -> SmithyIcons.KindPdf
        FileKind.DOC -> SmithyIcons.KindDoc
        FileKind.BOOK -> SmithyIcons.KindBook
        FileKind.PICTURE -> SmithyIcons.KindPicture
        FileKind.AUDIO -> SmithyIcons.KindAudio
        FileKind.VIDEO -> SmithyIcons.KindVideo
        FileKind.FONT -> SmithyIcons.KindFont
        FileKind.CODE -> SmithyIcons.KindCode
        FileKind.MODULE -> SmithyIcons.KindModule
        FileKind.BINARY -> SmithyIcons.KindBinary
        FileKind.DATABASE -> SmithyIcons.KindDatabase
        FileKind.OTHER -> SmithyIcons.KindOther
    }

/**
 * 类型 → 图标颜色。
 *
 * **只给两类上色，其余一律次要色**。十六种类型十六种颜色就是又回到 emoji 的老路
 * （花的、互相抢注意力）；分类靠**形状**就够了。留下的两个重音是这台工具真正在做的
 * 两件事：目录（往哪儿去）和安装包（要改的东西）。
 */
@Composable
fun FileKind.tint(): Color = when (this) {
    FileKind.DIR -> MaterialTheme.colorScheme.primary
    FileKind.APK -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

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
