package dev.smithy.engine.internal

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.smithy.engine.DexHit
import dev.smithy.engine.DexQuery
import dev.smithy.engine.DexQuery.Scope

/**
 * dex 搜索。
 *
 * 关键取舍：**不先把字符串池取出来再反查出处**，而是直接扫 const-string 指令。
 * 这样命中的每个字符串天然带着「哪个类的哪个方法在用它」，
 * AI 拿到结果就能直接定位过去改，不用再查一遍交叉引用。
 *
 * 代价是必须遍历全部指令。这对单次搜索可接受（一次性），
 * 也是为什么要有 [DexQuery.limit]：命中足够就提前退出，不扫完剩下的 dex。
 *
 * 匹配语义：**一律子串**，大小写敏感，CLASS / METHOD / FIELD 也一样 —— 求的是行为可预测。
 * 所以搜 `onCreate` 会连 `onCreateView`、`ensureCompositionCreated` 一起命中，这不是 bug。
 * 要精确匹配就 `regex = true` 配 `^...$`（如 `^onCreate$`），这是子串语义的配套出口。
 */
internal object DexSearch {

    private const val SNIPPET_MAX = 120

    fun run(index: DexIndex, q: DexQuery): List<DexHit> {
        // 无效正则会在这里抛 IllegalArgumentException，由上层转成 ToolError
        val rx = if (q.regex) Regex(q.text) else null
        val hit: (String) -> Boolean = { s -> rx?.containsMatchIn(s) ?: s.contains(q.text) }

        val limit = if (q.limit > 0) q.limit else 200
        val out = ArrayList<DexHit>(minOf(limit, 256))

        val dexes = if (q.dexName != null) index.all().filter { (n, _) -> n == q.dexName } else index.all()

        for ((dexName, dex) in dexes) {
            for (classDef in dex.classes) {
                val className = DexIndex.prettyName(classDef.type)

                when (q.scope) {
                    Scope.CLASS -> if (hit(className)) {
                        out += DexHit(className, null, null, classDef.type, dexName)
                    }

                    Scope.METHOD -> for (m in classDef.methods) {
                        val sig = signature(m)
                        if (hit(m.name) || hit(sig)) out += DexHit(className, sig, null, sig, dexName)
                    }

                    Scope.FIELD -> for (f in classDef.fields) {
                        if (hit(f.name)) {
                            out += DexHit(className, null, f.name, "$className.${f.name}", dexName)
                        }
                    }

                    Scope.STRING -> for (m in classDef.methods) {
                        val impl = m.implementation ?: continue   // 抽象/native 方法没有指令
                        for (ins in impl.instructions) {
                            val s = constString(ins) ?: continue
                            if (!hit(s)) continue
                            out += DexHit(className, signature(m), null, s.take(SNIPPET_MAX), dexName)
                            if (out.size >= limit) return out
                        }
                    }
                }

                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** smali 风格签名：`foo(Ljava/lang/String;)V` */
    private fun signature(m: Method): String =
        m.name + "(" + m.parameterTypes.joinToString("") { it.toString() } + ")" + m.returnType

    /** 只认 const-string / const-string/jumbo，不把类名、方法名当字符串常量 */
    private fun constString(ins: Instruction): String? {
        val op = ins.opcode
        if (op != Opcode.CONST_STRING && op != Opcode.CONST_STRING_JUMBO) return null
        return ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string
    }
}
