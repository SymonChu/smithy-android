package dev.smithy.engine.internal

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement

/**
 * 二进制 XML 层的读与改。
 *
 * APK 里的 XML（清单、布局、各种配置）都是 Android 的二进制 XML 格式，
 * 不是文本 —— 直接当文本改会把文件改坏。所以：
 *
 * - **读**：交给 ARSCLib 的 `decodeXMLFile` 解码成可读 XML
 * - **改**：用结构化的元素/属性 API，改完再由 ARSCLib 重新序列化
 *
 * 注意 `ResXmlDocumentOrElement` 这个基类是**包私有**的（ARSCLib 没打算暴露它），
 * 所以这里只能用 `ResXmlDocument` / `ResXmlElement` 两个公开类型 ——
 * 它们从基类继承来的公开方法仍然可用。
 */
internal object XmlBridge {

    private val INDEX = Regex("""\[(\d+)]""")

    /**
     * 取某个 xml 的文档对象，优先取 module 里已有的那份。
     *
     * **但要知道这条路的限度**（实测踩过）：ARSCLib 对普通 xml 是「读时解析、写出时回放
     * 原始字节」，`getResXmlDocument` 每次还会返回不同对象 —— 所以**改普通 xml 的内存对象
     * 不会落到产物里**，而且全程不报错。清单是例外（`AndroidManifestBlock` 是 module
     * 自己的对象），所以改清单有效。
     *
     * 需要改普通 xml 时，正确做法是**从零构造一份新的 xml 字节**再整条替换，
     * 而不是「读出来改对象」。目前还没这个需求（换图标改的是清单，见 `applyIconReplace`）。
     */
    private fun docOf(module: ApkModule, path: String): ResXmlDocument? =
        runCatching { module.getResXmlDocument(path) }.getOrNull()
            ?: runCatching { module.loadResXmlDocument(path) }.getOrNull()

    /**
     * 解码成可读文本。
     *
     * **没走 ARSCLib 的 `decodeXMLFile`**：它对着清单会失败 —— 清单在 ARSCLib 里是特殊类型
     * （`AndroidManifestBlock`），不走普通 xml 的加载路径。自己遍历结构反而更可控，
     * 顺便能把缩进与「只有文本的节点」排版得像人写的。
     *
     * 读不出来返回 null（条目不存在，或它不是二进制 XML）。
     */
    fun decode(module: ApkModule, path: String): String? {
        val doc = docOf(module, path) ?: return null
        return runCatching {
            buildString { for (el in doc.getElements()) appendElement(el, this, 0) }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun appendElement(el: ResXmlElement, sb: StringBuilder, depth: Int) {
        val pad = "  ".repeat(depth)
        val name = runCatching { el.getName() }.getOrNull() ?: return
        sb.append(pad).append('<').append(name)

        val attrs = runCatching { el.getAttributes() }.getOrNull()
        if (attrs != null) {
            while (attrs.hasNext()) {
                val a = attrs.next()
                val an = attrName(a) ?: continue
                val av = attrValue(a)
                sb.append(' ').append(an).append("=\"").append(av.replace("\"", "&quot;")).append('"')
            }
        }

        val children = runCatching { el.getElements().asSequence().toList() }.getOrDefault(emptyList())
        val texts = runCatching {
            el.getTexts().asSequence().filter { !it.isBlank }.map { it.text }.toList()
        }.getOrDefault(emptyList())

        if (children.isEmpty() && texts.isEmpty()) {
            sb.append("/>\n")
            return
        }

        // 只有文本的节点（如 <string name="x">内容</string>）写成一整行，别拆成三行
        if (children.isEmpty()) {
            sb.append('>').append(texts.joinToString(""))
            sb.append("</").append(name).append(">\n")
            return
        }

        sb.append(">\n")
        for (t in texts) sb.append(pad).append("  ").append(t).append('\n')
        for (c in children) appendElement(c, sb, depth + 1)
        sb.append(pad).append("</").append(name).append(">\n")
    }

    /**
     * 改某个元素的属性。
     *
     * [elementPath] 是简化路径：`application/activity` 表示 application 下的第一个 activity，
     * `activity[1]` 表示第二个。不做完整 XPath —— 手机上没人会手写
     * `//activity[@name='x']`，而 AI 层调用时也更适合给「元素名 + 序号」这种直白的东西。
     *
     * 属性已存在就改值，不存在就新建 —— 调用方不必先自己查一遍。
     */
    fun patchAttribute(
        module: ApkModule,
        path: String,
        elementPath: String,
        attr: String,
        value: String,
    ): Boolean {
        val doc = docOf(module, path) ?: return false
        val element = findElement(doc, elementPath) ?: return false

        findAttribute(element, attr)?.let { it.setValueAsString(value); return true }

        val created = runCatching { element.newAttribute() }.getOrNull() ?: return false
        created.setName(attr)
        created.setValueAsString(value)
        return true
    }

    /** 按名字找属性。名字可能带 `android:` 前缀也可能不带，所以三种形态都比一次。 */
    private fun findAttribute(element: ResXmlElement, attr: String): ResXmlAttribute? {
        val attrs = runCatching { element.getAttributes() }.getOrNull() ?: return null
        while (attrs.hasNext()) {
            val a = attrs.next()
            val names = listOf(
                runCatching { a.getName() }.getOrNull(),
                runCatching { a.decodeName(false) }.getOrNull(),
                runCatching { a.decodeName(true) }.getOrNull(),
            )
            if (attr in names) return a
        }
        return null
    }

    /** 按 `a/b[1]/c` 这种路径找元素。找不到返回 null。 */
    fun findElement(doc: ResXmlDocument, elementPath: String): ResXmlElement? {
        var current: ResXmlElement? = null
        for (step in elementPath.split('/').filter { it.isNotBlank() }) {
            val index = INDEX.find(step)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val name = step.substringBefore('[').trim()

            // 用 getElements(name) 而不是 listElements(name)：后者在 ARSCLib 里已废弃
            val found = runCatching {
                val children = current?.getElements(name) ?: doc.getElements(name)
                children.asSequence().elementAtOrNull(index)
            }.getOrNull()

            current = found ?: return null
        }
        return current
    }

    /** 属性名：优先带命名空间前缀的形态（`android:debuggable` 比 `debuggable` 好读也好定位）。 */
    private fun attrName(a: ResXmlAttribute): String? =
        runCatching { a.decodeName(true) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { a.getName() }.getOrNull()

    /**
     * 属性值。
     *
     * **不能只用 `getValueAsString()`**：它是给字符串类型的值用的，
     * 对 `versionCode="1"` 这类整数属性会返回 null —— 表现出来就是解码结果里
     * 一堆属性值全是空的（实测踩过）。`decodeValue()` 会把各种 typed value 都转成可读文本。
     */
    private fun attrValue(a: ResXmlAttribute): String =
        runCatching { a.getValueAsString() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { a.decodeValue(true) }.getOrNull()
            ?: ""

    /** 元素的属性清单，供 UI / AI 展示（名字 -> 值）。 */
    fun attributesOf(element: ResXmlElement): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val it = element.getAttributes()
        while (it.hasNext()) {
            val a = it.next()
            val name = attrName(a) ?: continue
            out[name] = attrValue(a)
        }
        return out
    }
}
