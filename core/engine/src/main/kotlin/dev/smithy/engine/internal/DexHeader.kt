package dev.smithy.engine.internal

import java.io.InputStream

/**
 * DEX 文件头解析。
 *
 * 为什么不引 dexlib2 来统计：DEX 的头部（前 112 字节）本身就带了全部计数字段，
 * 读 112 字节比加载整个 dex 快几个数量级，且完全不会 OOM——
 * 手机上一个 100MB 的包里有几十个 dex，逐个全量解析是不可接受的。
 *
 * 字段偏移参考 AOSP dex-format#header-item：
 *   0x38 string_ids_size   u32
 *   0x58 method_ids_size   u32
 *   0x60 class_defs_size   u32
 */
internal object DexHeader {

    private const val MAGIC = "dex\n"
    private const val HEADER_SIZE = 112

    private const val OFF_STRING_IDS_SIZE = 0x38
    private const val OFF_METHOD_IDS_SIZE = 0x58
    private const val OFF_CLASS_DEFS_SIZE = 0x60

    data class Stats(
        val classes: Int,
        val methods: Int,
        val strings: Int,
        val valid: Boolean,
    ) {
        companion object {
            val INVALID = Stats(0, 0, 0, false)
        }
    }

    fun read(input: InputStream): Stats {
        val buf = ByteArray(HEADER_SIZE)
        var read = 0
        while (read < HEADER_SIZE) {
            val n = input.read(buf, read, HEADER_SIZE - read)
            if (n < 0) break
            read += n
        }
        if (read < HEADER_SIZE) return Stats.INVALID
        if (String(buf, 0, 4, Charsets.US_ASCII) != MAGIC) return Stats.INVALID

        return Stats(
            classes = u32(buf, OFF_CLASS_DEFS_SIZE),
            methods = u32(buf, OFF_METHOD_IDS_SIZE),
            strings = u32(buf, OFF_STRING_IDS_SIZE),
            valid = true,
        )
    }

    /** DEX 里的所有整数都是小端 u32；用 Int 承载（上限 2^31，实际不可能到）。 */
    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** 方法数超 65536 需要 multidex，UI 上要给预警。 */
    const val METHOD_LIMIT = 65_536
}
