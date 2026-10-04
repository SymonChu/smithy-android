package dev.smithy.engine.internal

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import dev.smithy.engine.IconTargets

/**
 * 顺着清单里的图标引用找出真正的图标条目。
 *
 * 解析顺序（每一步都往下顺，而不是猜名字）：
 *
 * 1. 清单的 `android:icon` / `android:roundIcon` → 资源 id
 * 2. 资源 id → 资源名（`@mipmap/xxx`）
 * 3. 如果存在 `res/mipmap-anydpi-v26/xxx.xml` 这类声明文件，说明是 adaptive icon →
 *    读那个 xml，拿它引用的前景 / 背景
 * 4. 把「类型 + 名字」映射到各密度的实际文件条目
 *
 * 纯逻辑（输入只有清单、资源表、条目路径列表和一个读 xml 的函数），所以可测。
 *
 * 注意：上面注释里写路径时不要出现 星号紧跟斜杠 —— 那会提前结束块注释。
 */
internal object IconResolver {

    /** 密度名，**从长到短**：否则 `mipmap-xxxhdpi` 可能被前缀匹配抢先命中。 */
    private val DENSITY_NAMES = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "nodpi", "anydpi")

    // adaptive icon 的声明文件长这样：
    //   <adaptive-icon>
    //     <background android:drawable="@color/ic_launcher_background"/>
    //     <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    //   </adaptive-icon>
    // **元素名**才是 foreground / background，属性统一叫 android:drawable。
    // 一开始按 android:foreground="..." 去匹配，自然是匹配不到。
    //
    // 用普通字符串而不是 raw string（"""）：模式以引号结尾时，
    // raw string 的结束符会和那个引号连成四个引号，变成语法错误。
    private val FG_REF = Regex("<foreground[^>]*android:drawable=\"@([^\"]+)\"")
    private val BG_REF = Regex("<background[^>]*android:drawable=\"@([^\"]+)\"")

    fun resolve(
        manifest: AndroidManifestBlock?,
        table: TableBlock?,
        allPaths: List<String>,
        readXml: (String) -> String?,
    ): IconTargets {
        if (manifest == null) {
            return IconTargets(notes = listOf("读不到 AndroidManifest.xml，无法确定这个包的图标是什么"))
        }
        if (table == null) {
            return IconTargets(notes = listOf("这个包没有资源表，图标通常不在这里 —— 它可能是个 split 包或纯粹的代码包"))
        }

        // roundIcon 是 Android 7.1 起的圆形图标，有的包只声明它不声明 icon
        val ids = buildList {
            runCatching { manifest.iconResourceId }.getOrNull()?.takeIf { it != 0 }?.let { add(it) }
            runCatching { manifest.roundIconResourceId }.getOrNull()?.takeIf { it != 0 }?.let { add(it) }
        }.distinct()

        if (ids.isEmpty()) {
            // 清单里没有 android:icon —— 图标可能由主题指定，也可能这个包压根没设图标。
            // **这不是死路**：可以新建一个图标资源，再把 android:icon 加到 <application> 上
            // （清单改得动，见 docs/08 第十节）。所以这里只报告现状，把「要不要建一个」
            // 交给上层决定 —— 见 planIconReplace 的第二条分支
            return IconTargets(
                declaredIcon = null,
                notes = listOf(
                    "清单里没有声明 android:icon / android:roundIcon（图标可能由主题指定）",
                    "可以新建一个图标资源并挂到 <application> 上",
                ),
            )
        }

        val notes = mutableListOf<String>()
        // 即使最后判定「换不了」，也要记住清单声明的是什么 —— 用户看到
        // 「你的包声明的是 @mipmap/ic_launcher，但它是矢量图」才知道下一步怎么办
        var lastDeclared: String? = null
        // 找到 adaptive 声明文件就要记下来，**不要求里面能解析出位图** ——
        // 「只有矢量前景」的包正是这种情况，而它恰恰要靠这个路径去新建资源
        var lastAdaptiveXml: String? = null

        for (id in ids) {
            val ref = runCatching { table.getResource(id) }.getOrNull() ?: continue
            val type = runCatching { ref.type }.getOrNull() ?: continue
            val name = runCatching { ref.name }.getOrNull() ?: continue
            val declared = "@$type/$name"
            lastDeclared = declared

            // ── adaptive icon：清单指向 @mipmap/xxx，真身是 mipmap-anydpi*/xxx.xml ──
            val xmlPath = allPaths.firstOrNull {
                it.startsWith("res/mipmap-anydpi") && it.substringAfterLast('/') == "$name.xml"
            }
            if (xmlPath != null) {
                lastAdaptiveXml = xmlPath
                val fromXml = fromAdaptiveXml(xmlPath, allPaths, readXml)
                if (fromXml != null) {
                    return fromXml.copy(declaredIcon = declared, adaptiveXml = xmlPath)
                }
                notes += "$declared 声明了 adaptive icon（$xmlPath），但没解析出可替换的图层"
            }

            // ── 传统图标 ──
            val legacy = variantsOf(allPaths, type, name)
            if (legacy.isNotEmpty()) {
                return IconTargets(declaredIcon = declared, legacy = legacy, notes = notes)
            }

            notes += "$declared 在包里没有对应的位图文件"
        }

        // 走到这里说明没找到可直接替换的位图。把相关的条目列出来 ——
        // 用户（和 AI）据此能一眼看出是哪种情况：
        //   .png/.webp  → 解析有问题，报 bug
        //   .xml        → 矢量图，图片替换不了
        //   @color 引用 → 背景是纯色
        val related = allPaths
            .filter { it.startsWith("res/") && (it.contains("launcher", true) || it.contains("icon", true)) }
            .take(20)

        if (related.isNotEmpty()) {
            notes += "包里与图标相关的条目：${related.joinToString("、")}"
            val hasVector = related.any { it.endsWith(".xml") }
            val hasBitmap = related.any { it.endsWith(".png") || it.endsWith(".webp") || it.endsWith(".jpg") }
            if (hasVector && !hasBitmap) {
                notes += "这些都是 .xml（矢量图或 adaptive 声明）。矢量和纯色不能用图片替换 —— " +
                    "要换这种包需要生成一整套新的位图资源（含各密度），目前还没实现"
            }
        }

        return IconTargets(
            declaredIcon = lastDeclared,
            adaptiveXml = lastAdaptiveXml,
            notes = notes.ifEmpty { listOf("没能定位到这个包的图标条目") },
        )
    }

    /**
     * 从 adaptive icon 的 xml 里读出前景 / 背景。
     *
     * 形如：
     * ```xml
     * <adaptive-icon>
     *   <background android:drawable="@color/ic_launcher_background"/>
     *   <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
     * </adaptive-icon>
     * ```
     * 背景常常是**纯色**（不是图片）—— 那种情况记下颜色名，但没法替换，
     * 只换前景也能让图标明显变样。
     */
    private fun fromAdaptiveXml(
        xmlPath: String,
        allPaths: List<String>,
        readXml: (String) -> String?,
    ): IconTargets? {
        val xml = readXml(xmlPath) ?: return null

        val fgRef = FG_REF.find(xml)?.groupValues?.get(1)
        val bgRef = BG_REF.find(xml)?.groupValues?.get(1)

        val fg = fgRef?.let { densitiesOf(it, allPaths) }.orEmpty()
        val bg = bgRef?.let { densitiesOf(it, allPaths) }.orEmpty()
        // 背景经常是纯色资源（@color/xxx），那不是能替换的图片 —— 记下名字即可
        val bgColor = bgRef?.takeIf { it.startsWith("color/") }?.let { "@$it" }

        if (fg.isEmpty() && bg.isEmpty()) return null
        return IconTargets(foreground = fg, background = bg, backgroundColor = bgColor)
    }

    /** `mipmap/ic_launcher_foreground` → 该资源在各密度的条目。 */
    private fun densitiesOf(reference: String, allPaths: List<String>): Map<String, String> {
        val parts = reference.split('/')
        if (parts.size != 2) return emptyMap()
        return variantsOf(allPaths, parts[0], parts[1])
    }

    /**
     * 找出「类型 + 名字」对应的所有密度变体的**文件条目**。
     *
     * 只看图片：`.xml` 是矢量或 adaptive 的声明，不是能直接替换的位图。
     */
    private fun variantsOf(allPaths: List<String>, type: String, name: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (path in allPaths) {
            if (!path.startsWith("res/$type-")) continue
            if (path.endsWith(".xml")) continue
            if (path.substringAfterLast('/').substringBeforeLast('.') != name) continue

            val density = DENSITY_NAMES.firstOrNull { path.contains("-$it") } ?: continue
            out[density] = path
        }
        return out
    }
}
