package dev.smithy.fs

/**
 * 文件类型 → 图标/颜色的分类。
 *
 * 放在 core:fs 而不是 feature 层：搜索结果（SearchHits）、压缩包条目、
 * 模块文件树都要用同一套图标语义，分类只认扩展名，不依赖任何 Android API。
 *
 * 之前只有 📁/📄 两种，APK/PDF/图片全都长一个样 —— 文件管理器里
 * 「扫一眼找到那张图/那个安装包」是最频繁的操作，图标是最快的索引。
 */
enum class FileKind(val emoji: String) {
    /** 目录。 */
    DIR("📁"),

    /** 安装包：apk / xapk / apks。 */
    APK("📦"),

    /** 压缩归档：zip / jar / tar 系 / 7z / rar（能开与否是另一回事，图标先说清是什么）。 */
    ARCHIVE("🗜"),

    /** 磁盘镜像 / 系统镜像。 */
    IMAGE_DISK("💾"),

    /** PDF。 */
    PDF("📕"),

    /** 文档：doc / ppt / xls / txt 的办公族。 */
    DOC("📘"),

    /** 电子书。 */
    BOOK("📗"),

    /** 图片。 */
    PICTURE("🖼"),

    /** 音频。 */
    AUDIO("🎵"),

    /** 视频。 */
    VIDEO("🎬"),

    /** 字体。 */
    FONT("🔤"),

    /** 代码 / 脚本 / 配置（文本可编辑的那批）。 */
    CODE("📜"),

    /** Magisk 模块的 prop 与脚本同属配置，但 module.prop 值得单独认出来。 */
    MODULE("🧩"),

    /** 可执行 / 二进制（so / dex / bin / elf）。 */
    BINARY("⚙️"),

    /** 数据库。 */
    DATABASE("🗃"),

    /** 上面都不认的兜底。 */
    OTHER("📄");

    companion object {
        /** 按文件名判类型。目录直接给 DIR，其余按扩展名查表。 */
        fun of(name: String, isDir: Boolean): FileKind {
            if (isDir) return DIR
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in APK_EXTS -> APK
                ext in ARCHIVE_EXTS -> ARCHIVE
                ext in DISK_EXTS -> IMAGE_DISK
                ext == "pdf" -> PDF
                ext in DOC_EXTS -> DOC
                ext in BOOK_EXTS -> BOOK
                ext in PICTURE_EXTS -> PICTURE
                ext in AUDIO_EXTS -> AUDIO
                ext in VIDEO_EXTS -> VIDEO
                ext in FONT_EXTS -> FONT
                ext in CODE_EXTS -> CODE
                ext in MODULE_EXTS -> MODULE
                ext in BINARY_EXTS -> BINARY
                ext in DB_EXTS -> DATABASE
                else -> OTHER
            }
        }

        private val APK_EXTS = setOf("apk", "xapk", "apks")
        private val ARCHIVE_EXTS = setOf("zip", "jar", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")
        private val DISK_EXTS = setOf("iso", "img", "odin", "md5")
        private val DOC_EXTS = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv")
        private val BOOK_EXTS = setOf("epub", "mobi", "azw3")
        private val PICTURE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico", "heic")
        private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "mid")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "webm", "mov", "3gp", "ts")
        private val FONT_EXTS = setOf("ttf", "otf", "woff", "woff2")
        private val CODE_EXTS = setOf(
            "kt", "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "go", "rs",
            "sh", "bash", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "prop", "html", "css",
        )
        private val MODULE_EXTS = setOf("prop")  // module.prop / system.prop；与 MODIFY 语义靠文件名
        private val BINARY_EXTS = setOf("so", "dex", "odex", "vdex", "bin", "elf", "o", "a")
        private val DB_EXTS = setOf("db", "db-wal", "db-shm", "sqlite", "sqlite3")
    }
}
