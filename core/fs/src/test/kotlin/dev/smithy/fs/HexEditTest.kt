package dev.smithy.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexEditTest {

    @Test
    fun `偏移默认按十六进制解析`() {
        // 十六进制编辑器里没人是来输十进制的。把 10 当十进制（=16）会让人
        // 跳到别的地方，而他不会知道自己跳错了
        assertEquals(0x10L, HexEdit.parseOffset("10"))
        assertEquals(0x1a2bL, HexEdit.parseOffset("1a2b"))
        assertEquals(0x1a2bL, HexEdit.parseOffset("0x1A2B"))
        assertEquals(0x1a2bL, HexEdit.parseOffset("  1A2B  "))
        // 真要十进制就明写 0d
        assertEquals(10L, HexEdit.parseOffset("0d10"))
    }

    @Test
    fun `偏移的坏输入返回 null 而不是 0`() {
        // 返回 0 会让人以为「跳到了文件开头」，而其实是他输错了
        assertNull(HexEdit.parseOffset(""))
        assertNull(HexEdit.parseOffset("   "))
        assertNull(HexEdit.parseOffset("xyz"))
        assertNull(HexEdit.parseOffset("1g"))
        assertNull(HexEdit.parseOffset("-5"))
    }

    @Test
    fun `十六进制字节串的几种常见写法都能解析`() {
        assertEquals(listOf<Byte>(0x90.toByte(), 0x90.toByte()), HexEdit.parseBytes("90 90")!!.toList())
        assertEquals(listOf<Byte>(0x90.toByte(), 0x90.toByte()), HexEdit.parseBytes("9090")!!.toList())
        assertEquals(listOf<Byte>(0x0a.toByte(), 0x0b.toByte()), HexEdit.parseBytes("0a-0b")!!.toList())
        assertEquals(listOf<Byte>(0xff.toByte()), HexEdit.parseBytes("FF")!!.toList())
        assertEquals(listOf<Byte>(0x90.toByte(), 0x0f.toByte()), HexEdit.parseBytes("0x90,0x0f")!!.toList())
    }

    @Test
    fun `字节串的坏输入返回 null 而不是补零`() {
        // 奇数位补一个 0 会写进一个用户没打算写的字节 —— 那是改坏文件
        assertNull(HexEdit.parseBytes("909"))
        assertNull(HexEdit.parseBytes(""))
        assertNull(HexEdit.parseBytes("zz"))
        assertNull(HexEdit.parseBytes("9g"))
    }

    @Test
    fun `覆盖写入之后文件长度一个字节都不变`() {
        // 这是这个模块最重要的一条约束：变长替换会移动后面所有字节，
        // 对 ELF/dex/arsc 这类带内部偏移表的格式等于把文件改坏
        val original = ByteArray(32) { it.toByte() }
        val out = HexEdit.overwrite(original, 4, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))

        assertEquals(original.size, out.size, "长度必须不变")
        assertEquals(0xAA.toByte(), out[4])
        assertEquals(0xBB.toByte(), out[5])
        // 前后都没被动
        assertEquals(0x03.toByte(), out[3])
        assertEquals(0x06.toByte(), out[6])
    }

    @Test
    fun `覆盖越界会当场抛异常`() {
        // 宁可当场失败，也不要让调用方以为改成功了
        val original = ByteArray(8)
        assertFailsWith<IllegalArgumentException> {
            HexEdit.overwrite(original, 6, byteArrayOf(1, 2, 3, 4))
        }
        assertFailsWith<IllegalArgumentException> {
            HexEdit.overwrite(original, -1, byteArrayOf(1))
        }
    }

    @Test
    fun `刚好写到最后一个字节是允许的`() {
        val original = ByteArray(8)
        val out = HexEdit.overwrite(original, 7, byteArrayOf(0x7F))
        assertEquals(0x7F.toByte(), out[7])
        assertEquals(8, out.size)
    }

    @Test
    fun `行偏移用的是文件里的真实地址而不是从 0 开始`() {
        // 界面上的地址列必须和文件地址一致，否则用户拿着界面上的地址
        // 去别处搜会找不到
        val bytes = ByteArray(40) { it.toByte() }
        val rows = HexEdit.rows(bytes, baseOffset = 0x1000)

        assertEquals(3, rows.size)
        assertEquals(0x1000L, rows[0].offset)
        assertEquals(0x1010L, rows[1].offset)
        assertEquals(0x1020L, rows[2].offset)
        // 最后一行只有 8 个字节
        assertEquals(8, rows[2].bytes.size)
        assertEquals(16, rows[0].bytes.size)
    }

    @Test
    fun `文本列把不可打印字节显示成点`() {
        assertTrue(HexEdit.isPrintable('A'.code.toByte()))
        assertTrue(HexEdit.isPrintable(' '.code.toByte()))
        assertTrue(!HexEdit.isPrintable(0x00.toByte()))
        assertTrue(!HexEdit.isPrintable(0x0A.toByte()))
        assertTrue(!HexEdit.isPrintable(0xC0.toByte()))

        val row = byteArrayOf('P'.code.toByte(), 0x00, 'K'.code.toByte(), 0x7F)
        assertEquals("P.K.", HexEdit.formatAscii(row))
    }

    @Test
    fun `十六进制列长度固定 每行都能对齐`() {
        // 不足一行的补空格，否则右侧文本列会错位，看起来像另一个地址的内容
        val full = HexEdit.formatHex(ByteArray(16) { 0x41 })
        val partial = HexEdit.formatHex(byteArrayOf(0x41))
        assertEquals(full.length, partial.length)
    }

    @Test
    fun `地址列固定八位`() {
        assertEquals("00000000", HexEdit.formatOffset(0))
        assertEquals("0000ffff", HexEdit.formatOffset(0xFFFF))
        assertEquals("12345678", HexEdit.formatOffset(0x12345678))
    }

    @Test
    fun `行内容相同就算相等`() {
        // Row 里有 ByteArray。不覆写 equals 的话会按引用比，
        // 内容相同的行被当成不同的行，列表会整片白刷
        val a = HexEdit.Row(0, byteArrayOf(1, 2, 3))
        val b = HexEdit.Row(0, byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
