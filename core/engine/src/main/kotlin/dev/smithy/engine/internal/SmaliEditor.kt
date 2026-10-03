package dev.smithy.engine.internal

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File

/**
 * smali 文本层的**写**能力：改一处 smali，汇编回 dex。
 *
 * ── 两步走：毫秒级的预检 + 秒级的真改 ──
 *
 * 失败的情况（类不在包里、方法名打错、模式没命中）占改包尝试的大多数，
 * 它们全都能靠**单类反汇编**判出来（毫秒级）。而真正落地的改动必须走整 dex 往返（秒级）。
 * 所以先预检、后真改：打错一个字不该让用户等半分钟。
 *
 * ── 为什么真改要「整个 dex 往返」而不是只重新汇编那一个类 ──
 *
 * 一个类无法单独汇编成可用的 dex：它引用的类型、方法、字段都要有别的类撑着，
 * 单独汇编只能得到「只含这一个类、其余全是外部引用」的残缺 dex；
 * 再合并回原 dex 需要自己实现 dexlib2 的写入层（常量池要重新索引），
 * 出错时产出的是**能装但一启动就崩**的包 —— 比慢更糟。
 * 用 smali/baksmali 自己的往返，产出物与官方工具链完全一致。
 *
 * ── 性能边界（M1 已知）──
 *
 * 往返耗时随 dex 大小线性增长，只对目标类所在的那**一个** dex 做。
 * 真正的优化（只重编改动的那一个类）留到 M2：需要实现
 * [DexFile] 的包装层，把目标类替换成新汇编的类、其余类原样透传。
 */
internal object SmaliEditor {

    sealed interface Outcome {
        /** 汇编成功。[dexName] 是产出条目在包内的路径。 */
        data class Ok(val dexName: String, val dexFile: File, val hits: Int) : Outcome

        /** 要改的类不在包内。 */
        data object NoSuchClass : Outcome

        /** 指定的方法不存在（说明模式本身可能没错，是方法名给错了）。 */
        data object NoSuchMethod : Outcome

        /** 类在、方法在，但模式一处都没命中 —— 绝不能当成功（用户会以为改了）。 */
        data object NotFound : Outcome
    }

    fun patchClass(
        index: DexIndex,
        descriptor: String,
        methodSig: String?,
        pattern: String,
        replacement: String,
        regex: Boolean,
        workDir: File,
    ): Outcome {
        val dexName = index.dexOfClass(descriptor) ?: return Outcome.NoSuchClass

        // ── 预检：只反汇编这一个类（毫秒级），先把失败情况判掉 ──
        val probe = SmaliBridge.disassembleClass(index, descriptor, File(workDir, "probe"))
            ?: return Outcome.NoSuchClass
        val probeText = runCatching { probe.readText() }.getOrNull() ?: return Outcome.NoSuchClass
        replace(probeText, methodSig, pattern, replacement, regex)
            ?.let { if (it.hits == 0) return Outcome.NotFound }
            ?: return Outcome.NoSuchMethod

        // ── 真改：整 dex 往返 ──
        val dex = index.dex(dexName) ?: return Outcome.NoSuchClass
        val smaliDir = File(workDir, "smali").apply { mkdirs() }
        if (!disassembleDex(dex, smaliDir, index.apiLevel)) {
            throw IllegalStateException("反汇编 $dexName 失败 —— 这个 dex 可能不是标准格式（加固过的包常见）")
        }

        val rel = descriptor.removePrefix("L").removeSuffix(";") + ".smali"
        val file = File(smaliDir, rel)
        if (!file.isFile) return Outcome.NoSuchClass

        // 在真正要写出去的那份文本上再算一次：整 dex 反汇编与单类反汇编的输出理论上一致，
        // 但以实际写入的这份为准，避免「预检说命中、落地时改的是另一份文本」。
        val applied = replace(file.readText(), methodSig, pattern, replacement, regex)
            ?: return Outcome.NoSuchMethod
        if (applied.hits == 0) return Outcome.NotFound
        file.writeText(applied.text)

        val outDex = File(workDir, dexName)
        if (!assembleDex(smaliDir, outDex, index.apiLevel)) {
            throw IllegalStateException(
                "改后的 smali 汇编不过 —— 替换多半破坏了语法（寄存器数不够、标签对不上、" +
                    "或把指令改成了别的形状）。这个 dex 的改动已丢弃，工作区里其他改动不受影响。",
            )
        }
        return Outcome.Ok(dexName, outDex, applied.hits)
    }

    /** 整个 dex 反汇编到目录。 */
    private fun disassembleDex(dex: DexFile, outDir: File, apiLevel: Int): Boolean {
        val options = BaksmaliOptions().apply {
            this.apiLevel = apiLevel
            parameterRegisters = true
            sequentialLabels = true
        }
        // jobs = 1：手机上多线程反汇编只放大内存峰值，不会更快
        return runCatching { Baksmali.disassembleDexFile(dex, outDir, 1, options) }.getOrDefault(false)
    }

    /** smali 目录汇编成 dex，输出路径由 [SmaliOptions.outputDexFile] 指定。 */
    private fun assembleDex(smaliDir: File, outFile: File, apiLevel: Int): Boolean {
        outFile.parentFile?.mkdirs()
        val options = SmaliOptions().apply {
            this.apiLevel = apiLevel
            outputDexFile = outFile.absolutePath
            jobs = 1
            verboseErrors = true
        }
        // assemble() 返回 true 也可能产出空文件（无 .smali 输入时），所以还要验产物
        return runCatching { Smali.assemble(options, smaliDir.absolutePath) }.getOrDefault(false) &&
            outFile.isFile && outFile.length() > 0
    }

    private class Replaced(val text: String, val hits: Int)

    /**
     * 在 smali 文本里替换。[methodSig] 非空时只在该方法体内替换 ——
     * 同一条指令往往在许多方法里都出现，不限定范围很容易改到别处。
     *
     * 返回 null 表示方法根本不存在（与「方法在但没命中」是两回事）。
     */
    private fun replace(
        src: String,
        methodSig: String?,
        pattern: String,
        replacement: String,
        regex: Boolean,
    ): Replaced? {
        if (methodSig == null) return applyTo(src, pattern, replacement, regex)

        val block = SmaliBridge.extractMethod(src, methodSig) ?: return null
        val patched = applyTo(block, pattern, replacement, regex)
        if (patched.hits == 0) return patched
        // 方法块整体只出现一次，按整块替换 —— 不要用行号拼接，改完行数会变
        return Replaced(src.replace(block, patched.text), patched.hits)
    }

    private fun applyTo(text: String, pattern: String, replacement: String, regex: Boolean): Replaced =
        if (regex) {
            val rx = Regex(pattern)
            Replaced(rx.replace(text, replacement), rx.findAll(text).count())
        } else {
            Replaced(text.replace(pattern, replacement), countOccurrences(text, pattern))
        }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var from = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }
}
