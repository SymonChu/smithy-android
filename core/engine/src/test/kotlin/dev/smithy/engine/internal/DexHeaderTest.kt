package dev.smithy.engine.internal

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DexHeaderTest {

    /** 造一个 112 字节的合法 dex 头 */
    private fun header(
        stringIds: Int = 0,
        methodIds: Int = 0,
        classDefs: Int = 0,
        magic: String = "dex\n",
        size: Int = 112,
    ): ByteArray {
        val b = ByteArray(size.coerceAtMost(112).coerceAtLeast(8))
        magic.toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        b[4] = '0'.code.toByte()
        b[5] = '3'.code.toByte()
        b[6] = '5'.code.toByte()
        b[7] = 0
        if (b.size >= 112) {
            putU32(b, 0x38, stringIds)
            putU32(b, 0x58, methodIds)
            putU32(b, 0x60, classDefs)
        }
        return b
    }

    private fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    @Test
    fun `解析合法 dex 头的三个统计字段`() {
        val bytes = header(stringIds = 1234, methodIds = 5678, classDefs = 90)
        val stats = DexHeader.read(ByteArrayInputStream(bytes))

        assertTrue(stats.valid, "合法头应解析成功")
        assertEquals(1234, stats.strings)
        assertEquals(5678, stats.methods)
        assertEquals(90, stats.classes)
    }

    @Test
    fun `大端序写入的值不会被误读`() {
        // 0x01020304 小端存储，读回来必须还是 0x01020304；写错字节序会得到 0x04030201
        val b = header()
        putU32(b, 0x58, 0x01020304)
        val stats = DexHeader.read(ByteArrayInputStream(b))
        assertEquals(0x01020304, stats.methods)
    }

    @Test
    fun `magic 不是 dex 时判定无效`() {
        val stats = DexHeader.read(ByteArrayInputStream(header(magic = "zip\n")))
        assertFalse(stats.valid)
        assertEquals(DexHeader.Stats.INVALID, stats)
    }

    @Test
    fun `数据不足 112 字节时判定无效`() {
        val stats = DexHeader.read(ByteArrayInputStream(header(size = 40)))
        assertFalse(stats.valid)
    }

    @Test
    fun `空输入不抛异常`() {
        val stats = DexHeader.read(ByteArrayInputStream(ByteArray(0)))
        assertFalse(stats.valid)
    }
}
