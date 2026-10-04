package dev.smithy.fs

/**
 * 十六进制查看 / 编辑的纯逻辑。
 *
 * **为什么单独一层**：这些是「用户输入 → 字节」的换算，算错了会**静默写坏文件**。
 * 放在这里能脱离文件系统反复单测，而不是拿真文件去试。
 *
 * **一条硬约束：只做等长覆盖。** [overwrite] 用补丁替换同长度的区间，**文件长度永远不变**。
 * 变长替换会移动后面所有字节 —— 对 ELF / dex / arsc 这类带内部偏移表的格式，
 * 那等于把文件改坏（而且坏得看不出来）。所以这类编辑一律走等长覆盖，
 * 真需要插入/删除就得由懂那个格式的代码去改写偏移表。
 */
object HexEdit {

    /** 一行显示多少字节。16 是通例：一行正好两列对齐，也便于心算偏移。 */
    const val BYTES_PER_ROW = 16

    /**
     * 解析用户输入的偏移。接受 `0x1a2b`、`1a2b`、`6699` 三种写法。
     *
     * 带 `0x` 前缀时按十六进制，否则**也按十六进制** —— 十六进制编辑器里
     * 没人是来输十进制的，把 `10` 当十进制（= 16）会让人跳错地方而不知道。
     * 十进制要输就写 `0d10`。
     */
    fun parseOffset(text: String): Long? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("0d") -> t.removePrefix("0d").toLongOrNull()
            t.startsWith("0x") -> t.removePrefix("0x").toLongOrNull(16)
            else -> t.toLongOrNull(16)
        }?.takeIf { it >= 0 }
    }

    /**
     * 解析一串十六进制字节。接受 `90 90`、`9090`、`90-90`、`0x90,0x90`。
     *
     * 奇数个字符返回 null，而不是补一个 0 —— 那样会写进一个用户没打算写的字节。
     */
    fun parseBytes(text: String): ByteArray? {
        // 先统一小写：大写十六进制（FF、90 90 FF）比小写更常见，
        // 只认 a-f 会让最常见的输入被判成非法
        val cleaned = text.lowercase()
            .replace("0x", "")
            .replace(Regex("[\\s,;:\\-_]"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it.isDigit() || it in 'a'..'f' }) return null
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * 把 [patch] 覆盖到 [original] 的 [offset] 处，返回新数组。
     *
     * **长度恒等于 [original].size** —— 见类注释。越界直接抛异常：宁可当场失败，
     * 也不要让调用方以为改成功了。
     */
    fun overwrite(original: ByteArray, offset: Long, patch: ByteArray): ByteArray {
        require(offset >= 0) { "偏移不能是负数：$offset" }
        require(offset + patch.size <= original.size) {
            "改到文件外面去了：偏移 $offset + ${patch.size} 字节 > 文件 ${original.size} 字节"
        }
        val out = original.copyOf()
        patch.copyInto(out, offset.toInt())
        return out
    }

    /** 字节是否是可打印 ASCII（用于右侧的文本列）。 */
    fun isPrintable(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 0x20..0x7E
    }

    /** 一行里显示的字节。 */
    data class Row(
        /** 这行第一个字节的绝对偏移。 */
        val offset: Long,
        /** 这行的字节，最多 [BYTES_PER_ROW] 个。 */
        val bytes: ByteArray,
    ) {
        // ByteArray 是引用类型，data class 的默认 equals/hashCode 会按引用比。
        // 这里要按内容比，否则两个内容相同的行会被当成不同的行，列表会白刷。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Row) return false
            return offset == other.offset && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * offset.hashCode() + bytes.contentHashCode()
    }

    /**
     * 把 [bytes] 切成行，偏移从 [baseOffset] 开始。
     *
     * [baseOffset] 要传**这个窗口在文件里的真实偏移**，不是 0 —— 界面上的地址列
     * 必须和文件里的地址一致，否则用户按界面上的地址去别处搜会找不到。
     *
     * [perRow] 默认 16。窄屏要调小：地址列 + 16 个字节 + 文本列约 73 个等宽字符，
     * 手机竖屏放不下，会横向溢出或者把字号压到看不清。
     */
    fun rows(bytes: ByteArray, baseOffset: Long, perRow: Int = BYTES_PER_ROW): List<Row> {
        require(perRow > 0) { "每行至少一个字节" }
        val out = ArrayList<Row>((bytes.size + perRow - 1) / perRow)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + perRow, bytes.size)
            out += Row(baseOffset + i, bytes.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /** 地址列（8 位十六进制，够表示 4GB）。 */
    fun formatOffset(offset: Long): String = "%08x".format(offset)

    /**
     * 十六进制列，不足一行的补空格，保证右列的文本对齐。
     *
     * 中间（第 8 个字节后）多留一个空格：那是十六进制里最自然的分组断点，
     * 也方便数「第几个字节」。
     */
    fun formatHex(bytes: ByteArray, perRow: Int = BYTES_PER_ROW): String {
        val sb = StringBuilder(perRow * 3)
        for (i in 0 until perRow) {
            if (i < bytes.size) {
                sb.append("%02x".format(bytes[i]))
            } else {
                sb.append("  ")
            }
            sb.append(' ')
            if (i == 7) sb.append(' ')
        }
        return sb.toString()
    }

    /** 文本列：不可打印的字节显示成 `.`。 */
    fun formatAscii(bytes: ByteArray): String =
        String(CharArray(bytes.size) { if (isPrintable(bytes[it])) bytes[it].toInt().toChar() else '.' })
}
