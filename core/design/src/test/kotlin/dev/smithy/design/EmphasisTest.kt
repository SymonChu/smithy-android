package dev.smithy.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 强调标记的解析。
 *
 * 这些用例对着的是「屏幕上会不会出现两个星号」这件事：引擎给的结论里 `**…**` 是有信息量的
 * （重点在哪几个字），但 Compose 的 Text 不认识它。解析器本身很简单，**边界**才是要钉的 ——
 * 漏一个星号时不能把整段染粗，也不能吞掉后面的内容。
 */
class EmphasisTest {

    private fun plain(text: String) = smithyEmphasis(text).text

    @Test
    fun `成对的标记去掉星号 文字一字不动`() {
        val out = smithyEmphasis("可以改。但**改包名**会让注入失效")
        assertEquals("可以改。但改包名会让注入失效", out.text)
        // 「改包名」三个字要落在加粗区间里
        val span = out.spanStyles.single()
        assertEquals("改包名", out.text.substring(span.start, span.end))
    }

    @Test
    fun `多段标记各自成段`() {
        val out = smithyEmphasis("**没有 arm64-v8a**：64 位系统**装不上**")
        assertEquals("没有 arm64-v8a：64 位系统装不上", out.text)
        assertEquals(2, out.spanStyles.size)
    }

    @Test
    fun `没有标记时原样返回`() {
        val out = smithyEmphasis("未检测到常见的加固壳")
        assertEquals("未检测到常见的加固壳", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `只有一个孤立标记时不吞后面的内容`() {
        // 文案漏写一个星号是常见事。这种情况下宁可原样显示（看得见问题），
        // 也不能把后半段全染粗、或者干脆丢掉
        val out = smithyEmphasis("这里有**一个没配对的标记，后面还有话")
        assertEquals("这里有**一个没配对的标记，后面还有话", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `去标记的版本用于纯文本场合`() {
        assertEquals("改包名会失效", "**改包名**会失效".withoutEmphasis())
    }
}
