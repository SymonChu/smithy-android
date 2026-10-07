package dev.smithy.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.smithy.fs.FileKind

/**
 * 文件类型 → 图标。
 *
 * **为什么在 core:design 而不是某个 feature**：文件页和工作台都要用同一套映射。
 * 放在 feature/files 里，feature/apk 就只能反向依赖它、或者自己抄一份；而抄一份的
 * 必然结果是同一个类型（比如 .dex）在两个页面上是两张图 —— 那正是这一轮改造要
 * 消灭的「两套语言」。
 *
 * 类型分类（[FileKind]）本身留在 core:fs：那是纯数据、不依赖 Compose；
 * 「长什么样」是表现层，所以在这里。
 *
 * 原先 `FileKind` 上挂着一个 `emoji` 字段 —— 表现层的东西长在数据模块里，
 * 最后就退化成了 16 个跨 ROM 字形不一致、彩色、还没法跟着主题着色的 emoji。
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
 * （花的、互相抢注意力）；分类靠**形状**就够了。留下的两个重音正好是这台工具真正
 * 在做的两件事：目录（往哪儿去）和安装包（要改的东西）。
 */
@Composable
fun FileKind.tint(): Color = when (this) {
    FileKind.DIR -> MaterialTheme.colorScheme.primary
    FileKind.APK -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
