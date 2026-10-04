package dev.smithy.engine

/**
 * 换图标的执行计划。
 *
 * **为什么切成「规划 → 画图 → 应用」三步**：画图要用 Android 的 `Bitmap`
 * （`javax.imageio` 在 Android 上不存在），而改包结构要用 ARSCLib —— 引擎是纯 JVM、
 * 不能 import `android.*`。所以引擎只负责说清「要画哪些图、多大、内容画在哪个范围」，
 * 调用方画好再回传。职责边界干净，两边还各自可测。
 */
data class IconPlan(
    /** 图要落到哪儿：直接替换现有图层，还是要新建资源 */
    val mode: Mode,
    /** 要画的图 */
    val renders: List<IconRender>,
    /** 包的图标声明，如 `@mipmap/ic_launcher` */
    val declaredIcon: String? = null,
    /**
     * adaptive 声明文件的路径。
     * [Mode.NEW_RESOURCES] 时要改它 —— 因为新建的位图资源要被它引用才生效。
     */
    val adaptiveXml: String? = null,
    /** 新建资源的公共前缀，如 `smithy_icon` → `smithy_icon_fg` */
    val newResourceBase: String? = null,
    /** 给人看的说明（UI 与 AI 都用得上） */
    val notes: List<String> = emptyList(),
) {
    enum class Mode {
        /** 包里已有位图图层，直接覆盖它们 */
        OVERLAY,

        /**
         * 只有矢量图 / 纯色，没有任何位图。
         *
         * 这种情况下**只往包里塞 PNG 是没用的** —— 资源表里没有对应条目，
         * 系统找不到那张图，图标会变成默认的。所以必须：
         * 新建 mipmap 资源（各密度）→ 写 PNG → 把 adaptive 声明指过来。
         */
        NEW_RESOURCES,
    }

    val isEmpty: Boolean get() = renders.isEmpty()

    /** 涉及新资源时，要改的文件（除了要写的图之外） */
    fun extraTouchedEntries(): Set<String> = buildSet {
        if (mode == Mode.NEW_RESOURCES) {
            add(ARSC_ENTRY)
            adaptiveXml?.let { add(it) }
        }
    }

    companion object {
        const val ARSC_ENTRY = "resources.arsc"
    }
}

/**
 * 一张要生成的图。
 *
 * [contentSize] 是给调用方的关键信息：**内容画在「居中的 contentSize×contentSize 方框」里，
 * 其余留透明**。传统图标 contentSize = 画布边长；adaptive 前景是画布的 72/108（安全区，
 * 超出会被启动器裁掉）。
 *
 * 这条规则放在引擎里而不是调用方，是为了让「不变形、不被裁」只有一处定义 ——
 * 换图标最容易出的问题就是这里，规则散开就必然会有一处写错。
 */
data class IconRender(
    /** 回传时用的 key */
    val key: String,
    /** 目标条目路径 */
    val entryPath: String,
    /** 密度名：mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi */
    val density: String,
    /** 画布边长（px） */
    val canvasSize: Int,
    /** 内容边长（px） */
    val contentSize: Int,
    val role: Role,
) {
    enum class Role {
        /** 传统图标：铺满即可 */
        LEGACY_ICON,

        /** adaptive 前景层：要缩到安全区 */
        ADAPTIVE_FOREGROUND,

        /** adaptive 背景层：铺满整块 */
        ADAPTIVE_BACKGROUND,
    }
}
