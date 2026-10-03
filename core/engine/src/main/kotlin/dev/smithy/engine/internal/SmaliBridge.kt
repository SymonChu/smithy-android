package dev.smithy.engine.internal

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import java.io.File

/**
 * smali 文本层的读写。
 *
 * 反汇编走 baksmali 的 **classNames 过滤**重载：只反汇编要看的那个类。
 * 65k 方法的 dex 全量反汇编在手机上要几十秒，单类只要毫秒级 —— 这是
 * 「手机端改包」能不能用的分水岭，所以宁可多写几行也不走全量。
 */
internal object SmaliBridge {

    /** 只反汇编一个类，返回它的 .smali 文件；类不在包内时返回 null。 */
    fun disassembleClass(index: DexIndex, className: String, outDir: File): File? {
        val descriptor = DexIndex.descriptor(className)
        val dex = index.dex(index.dexOfClass(descriptor) ?: return null) ?: return null

        val options = BaksmaliOptions().apply {
            apiLevel = index.apiLevel
            parameterRegisters = true
            sequentialLabels = true   // 标签按出现顺序编号，输出稳定，便于 diff
        }

        val ok = runCatching {
            // jobs = 1：手机上多线程反汇编反而更慢，且会放大内存峰值
            Baksmali.disassembleDexFile(dex, outDir, 1, options, listOf(descriptor))
        }.getOrDefault(false)
        if (!ok) return null

        // baksmali 的输出路径 = 描述符去掉 L/; 后的包路径
        val rel = descriptor.removePrefix("L").removeSuffix(";") + ".smali"
        return File(outDir, rel).takeIf { it.isFile }
    }

    /**
     * 从类 smali 里截出某个方法的字块（含 `.method` 与 `.end method` 两行）。
     *
     * methodSig 可以是 `foo`，也可以是完整签名 `foo(Ljava/lang/String;)V`。
     */
    fun extractMethod(smali: String, methodSig: String): String? {
        val want = methodSig.filterNot { it.isWhitespace() }
        val name = want.substringBefore('(')
        val lines = smali.lines()

        var start = -1
        for (i in lines.indices) {
            val t = lines[i].trim()

            if (start < 0) {
                if (!t.startsWith(".method")) continue
                // `.method public static foo(Ljava/lang/String;)V` → decl / token
                val decl = t.removePrefix(".method").trim()
                val token = decl.substringBefore('(').substringAfterLast(' ')
                if (token != name) continue
                // 只给名字时按名字收；给了完整签名时还要比对参数与返回值
                if (want == name || decl.filterNot { it.isWhitespace() }.endsWith(want)) start = i
            } else if (t == ".end method") {
                return lines.subList(start, i + 1).joinToString("\n")
            }
        }
        return null
    }
}
