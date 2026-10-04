package dev.smithy.engine

/**
 * 这个包的图标由哪些条目组成。
 *
 * **为什么要专门解析而不是按名字猜**：图标叫什么完全取决于开发者 ——
 * `ic_launcher` 只是 Android Studio 模板的默认名，实际项目里 `app_icon`、`icon`、
 * `launcher_logo` 都很常见。名字猜错的结果是「明明有图标却报找不到」。
 *
 * 正确姿势是顺着清单走：`android:icon` 引用 → 资源名 → 该资源的所有密度变体；
 * 如果指向的是 adaptive icon 的 xml，再顺着 xml 里的 drawable 引用找前景/背景。
 */
data class IconTargets(
    /** 清单里声明的引用，如 `@mipmap/app_icon`。null 表示清单里没声明图标 */
    val declaredIcon: String? = null,

    /** 传统图标：密度 → 条目路径（如 `res/mipmap-xxhdpi-v4/app_icon.png`） */
    val legacy: Map<String, String> = emptyMap(),

    /** adaptive icon 的前景层：密度 → 条目路径 */
    val foreground: Map<String, String> = emptyMap(),

    /** adaptive icon 的背景层：密度 → 条目路径 */
    val background: Map<String, String> = emptyMap(),

    /** adaptive icon 的声明文件路径（`res/mipmap-anydpi-v26/xxx.xml`） */
    val adaptiveXml: String? = null,

    /** 背景是纯色资源而不是图片时，它的名字（如 `@color/ic_launcher_background`） */
    val backgroundColor: String? = null,

    /**
     * 诊断信息：找不到时说明**为什么**。
     *
     * 这段直接决定用户看到的是「换不了」还是「换不了，因为你的包是 XXX 那种情况」——
     * 后者他至少知道下一步该干什么。
     */
    val notes: List<String> = emptyList(),
) {
    val isAdaptive: Boolean get() = foreground.isNotEmpty() || background.isNotEmpty()

    val isReplaceable: Boolean get() = foreground.isNotEmpty() || legacy.isNotEmpty()

    val layerSummary: String
        get() = buildList {
            if (foreground.isNotEmpty()) add("前景 ${foreground.size} 个密度")
            if (background.isNotEmpty()) add("背景 ${background.size} 个密度")
            if (legacy.isNotEmpty()) add("传统图标 ${legacy.size} 个密度")
        }.joinToString("，").ifEmpty { "无可用图层" }
}
