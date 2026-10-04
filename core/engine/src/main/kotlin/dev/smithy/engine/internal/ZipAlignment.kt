package dev.smithy.engine.internal

/**
 * zip 条目的数据对齐。
 *
 * **为什么单独抽出来**：Android 有两条硬要求 —— `resources.arsc` 要 4 字节对齐，
 * 未压缩的 `.so` 要 **16KB 页对齐**（mmap 它的前提）。不满足就报
 * `Failed to extract native libraries, res=-2`，而那个报错一个字都不提 zip。
 *
 * 这段逻辑原先只在 `ZipRebuilder` 里，后来签名流程（V1Signer）自己重写了一遍 zip
 * **没带上对齐** —— 同一个坑踩了第二次，真机上才暴露。抽出来就是让它只有一份：
 * 谁重写 zip 谁用它。
 */
internal object ZipAlignment {

    /** 未压缩资源的对齐（`resources.arsc` 是 Android 11+ 的硬要求）。 */
    const val DATA = 4L

    /** 未压缩 `.so` 的对齐：16KB，mmap 的前提。 */
    const val PAGE = 16384L

    const val STORED = 0

    /** 填充用的 extra 块 id。 */
    private const val PADDING_ID = 0xFFFF

    /** lib 目录下的 .so 要按页对齐。 */
    fun isNativeLib(name: String): Boolean =
        name.startsWith("lib/") && name.endsWith(".so")

    fun alignmentFor(name: String): Long = if (isNativeLib(name)) PAGE else DATA

    /** 签名文件与 .so 都要 STORED：它们会被直接读，压缩没意义还可能出岔子。 */
    fun needsStored(name: String): Boolean =
        isNativeLib(name) || name == "resources.arsc" || isSignatureFile(name)

    fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    /**
     * 在 extra 字段尾部补一个填充块，让**数据起点**落在对齐边界上。
     *
     * 填充必须是**合法的 extra 块**：结构 `[id:2][长度:2][数据:长度]`，总长 = 4 + 长度。
     * 要让偏移补上 `pad` 字节且块长合法，于是取 `长度 ≡ pad - 4 (mod 对齐值)`。
     *
     * 不用裸字节填充：实测它会让严格的解析器读不出整包（ARSCLib 打开新包后资源表读不到）。
     */
    fun padExtra(
        origExtra: ByteArray,
        offset: Long,
        nameLen: Int,
        method: Int,
        name: String,
    ): ByteArray {
        if (method != STORED) return origExtra
        val alignment = alignmentFor(name)
        val base = offset + 30 + nameLen + origExtra.size
        val pad = ((alignment - base % alignment) % alignment).toInt()
        if (pad == 0) return origExtra

        val dataLen = (((pad - 4) % alignment) + alignment) % alignment
        val block = ByteArray(4 + dataLen.toInt())
        block[0] = (PADDING_ID and 0xFF).toByte()
        block[1] = ((PADDING_ID ushr 8) and 0xFF).toByte()
        block[2] = (dataLen.toInt() and 0xFF).toByte()
        block[3] = ((dataLen.toInt() ushr 8) and 0xFF).toByte()
        return origExtra + block
    }
}
