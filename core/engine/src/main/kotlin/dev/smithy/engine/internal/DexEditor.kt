package dev.smithy.engine.internal

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import dev.smithy.engine.StringReplacement
import java.io.File

/**
 * dex 的改写。
 *
 * 走 dexlib2 的 [DexRewriter]：只改命中处，其余结构原样搬运，
 * 由它负责重算 dex 头的 checksum 与 signature（手写字节改要自己算，容易漏）。
 *
 * **代价要知道**：rewrite 会把整个 dex 重建成对象树，65k 方法级别的 dex
 * 在手机上内存压力不小。所以调用方必须先搜索、**只对确实含目标字符串的 dex** 调用，
 * 不要无脑遍历所有 dex。
 *
 * 只改 `const-string` / `const-string/jumbo` 指令 —— 也就是代码里的字符串常量。
 * 类名、方法名、字段名不是"字符串常量"，不在这里动（改那些是重构，不是改文案）。
 */
internal object DexEditor {

    data class Result(val file: File, val replaced: Int)

    /**
     * 把某个 dex 里的字符串常量替换掉。
     *
     * @param regex 非 null 时按正则匹配并替换（替换值是字面量，`$1` 不会被展开）
     * @param outFile 写出的新 dex；调用方负责清理
     * @return 没有任何命中时返回 null（避免白写一个一模一样的 dex）
     */
    fun replaceString(
        index: DexIndex,
        dexName: String,
        from: String,
        to: String,
        regex: Regex?,
        outFile: File,
    ): Result? {
        val dex = index.dex(dexName) ?: return null

        // 先数一遍，两个作用：
        // ① 不含命中就短路，不为它白建一整棵对象树；
        // ② 拿到**准确**的命中数 —— 不能用 rewriter 内部的计数：惰性代理在写出时
        //    可能被展开多次，那个计数会虚高（实测 1 处报成 3 处）。
        val hits = countMatches(index, dexName, from, regex)
        if (hits == 0) return null

        var touched = 0

        val module = object : RewriterModule() {
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> =
                object : InstructionRewriter(rewriters) {
                    override fun rewrite(instruction: Instruction): Instruction {
                        val current = constString(instruction) ?: return super.rewrite(instruction)
                        val isHit = regex?.containsMatchIn(current) ?: (current == from)
                        if (!isHit) return super.rewrite(instruction)

                        // 用 transform 重载：替换值整体作为字面量，不会被当成 $1 这类反向引用
                        val next = regex?.replace(current) { to } ?: to
                        if (next == current) return super.rewrite(instruction)

                        touched++
                        return when (instruction) {
                            is Instruction21c -> ImmutableInstruction21c(
                                instruction.opcode,
                                instruction.registerA,
                                ImmutableStringReference(next),
                            )
                            is Instruction31c -> ImmutableInstruction31c(
                                instruction.opcode,
                                instruction.registerA,
                                ImmutableStringReference(next),
                            )
                            else -> super.rewrite(instruction)
                        }
                    }
                }
        }

        val rewritten = DexRewriter(module).dexFileRewriter.rewrite(dex)

        // 必须**先写出去**才谈得上判断有没有命中。
        // DexRewriter 每一层返回的都是惰性代理（RewrittenDexFile / RewrittenClassDef / …），
        // 只有 writeDexFile 遍历整棵树时才会真正走到 instruction 层；
        // 在那之前 replaced 恒为 0 —— 曾因此静默零命中（改动被丢掉但没人报错）。
        // 所以别把它"优化"成先判断后写。
        DexFileFactory.writeDexFile(outFile.absolutePath, rewritten)

        // touched 只作兜底：正常应与 hits 一致。不一致说明预检与改写两条路径判定不同，
        // 那种情况宁可当失败，也不留下一个内容其实没变的 dex。
        if (touched == 0) {
            outFile.delete()
            return null
        }
        return Result(outFile, hits)
    }

    /** 数一下某个 dex 里有多少处命中（不改，用于"先看影响面再动手"）。 */
    fun countMatches(index: DexIndex, dexName: String, from: String, regex: Regex?): Int {
        val dex = index.dex(dexName) ?: return 0
        var n = 0
        for (classDef in dex.classes) {
            for (m in classDef.methods) {
                val impl = m.implementation ?: continue
                for (ins in impl.instructions) {
                    val s = constString(ins) ?: continue
                    if (regex?.containsMatchIn(s) ?: (s == from)) n++
                }
            }
        }
        return n
    }

    /**
     * 一次替换一个 dex 里的**多组**字符串常量。
     *
     * 逐组调用 [replaceString] 结果一样，但会把整个 dex 反复重建成对象树
     * （65k 方法的 dex 每次几百毫秒起）。批量只在最后写一次。
     *
     * 匹配策略与资源层保持一致：**按列表顺序，一条值只应用第一个命中的规则**，
     * 不做连锁替换（否则 `A→B`、`B→C` 两条规则会把 A 一路带成 C）。
     */
    fun replaceStrings(
        index: DexIndex,
        dexName: String,
        pairs: List<StringReplacement>,
        outFile: File,
    ): Result? {
        val dex = index.dex(dexName) ?: return null
        if (pairs.isEmpty()) return null

        val hits = countMatchesAny(index, dexName, pairs)
        if (hits == 0) return null

        var touched = 0

        val module = object : RewriterModule() {
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> =
                object : InstructionRewriter(rewriters) {
                    override fun rewrite(instruction: Instruction): Instruction {
                        val current = constString(instruction) ?: return super.rewrite(instruction)
                        val next = applyFirst(pairs, current)
                        if (next == current) return super.rewrite(instruction)

                        touched++
                        return when (instruction) {
                            is Instruction21c -> ImmutableInstruction21c(
                                instruction.opcode,
                                instruction.registerA,
                                ImmutableStringReference(next),
                            )
                            is Instruction31c -> ImmutableInstruction31c(
                                instruction.opcode,
                                instruction.registerA,
                                ImmutableStringReference(next),
                            )
                            else -> super.rewrite(instruction)
                        }
                    }
                }
        }

        val rewritten = DexRewriter(module).dexFileRewriter.rewrite(dex)
        // 同 replaceString：必须写出去，惰性代理才会展开到 instruction 层
        DexFileFactory.writeDexFile(outFile.absolutePath, rewritten)

        if (touched == 0) {
            outFile.delete()
            return null
        }
        return Result(outFile, hits)
    }

    /** 批量版的命中计数：值里含任一 from 就算命中（与替换时的子串语义一致）。 */
    private fun countMatchesAny(index: DexIndex, dexName: String, pairs: List<StringReplacement>): Int {
        val dex = index.dex(dexName) ?: return 0
        val froms = pairs.map { it.from }
        var n = 0
        for (classDef in dex.classes) {
            for (m in classDef.methods) {
                val impl = m.implementation ?: continue
                for (ins in impl.instructions) {
                    val s = constString(ins) ?: continue
                    if (froms.any { s.contains(it) }) n++
                }
            }
        }
        return n
    }

    /** 按顺序找到第一条命中的规则并应用，只应用一条。 */
    private fun applyFirst(pairs: List<StringReplacement>, value: String): String {
        for (p in pairs) {
            if (value.contains(p.from)) return value.replace(p.from, p.to)
        }
        return value
    }

    private fun constString(ins: Instruction): String? {
        val op = ins.opcode
        if (op != Opcode.CONST_STRING && op != Opcode.CONST_STRING_JUMBO) return null
        return ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string
    }
}
