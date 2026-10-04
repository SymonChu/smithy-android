package dev.smithy.engine

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1 集成测试：改 dex 字符串 → 落覆盖层 → 能回退。
 *
 * 这里验证的是「改包」的第一环：**改动真的进了 dex，而不是只记了个账**。
 * 所以断言不看 patchCount，而是把覆盖层里的 dex 抠出来、用 dexlib2 独立打开，
 * 检查旧值消失、新值出现 —— 账目对不上不算通过。
 */
class DexPatchTest {

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    private fun requireSample(): File {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)
        return sampleApk!!
    }

    /**
     * 独立地把 dex 里的字符串常量读出来 —— 不经过我们自己的引擎代码。
     *
     * 用**默认 opcode 集**，不要写死 `forApi(21)`：替换后的 dex 是 dexlib2 重新序列化的，
     * 它的指令集高于 21，按 21 解析指令会错位 —— 表现出来是「字节里明明有这个字符串、
     * 读出来却没有」，非常误导（这里踩过：字节里含「操作台」=true，读出来=false）。
     */
    private fun dexStrings(f: File): Set<String> {
        val dex = DexFileFactory.loadDexFile(f, com.android.tools.smali.dexlib2.Opcodes.getDefault())
        val out = mutableSetOf<String>()
        for (cls in dex.classes) {
            for (m in cls.methods) {
                val impl = m.implementation ?: continue
                for (ins in impl.instructions) {
                    val op = ins.opcode
                    if (op != Opcode.CONST_STRING && op != Opcode.CONST_STRING_JUMBO) continue
                    val ref = (ins as? ReferenceInstruction)?.reference as? StringReference ?: continue
                    out += ref.string
                }
            }
        }
        return out
    }

    private suspend fun dumpTo(project: ApkProject, entry: String): File {
        val f = File.createTempFile("smithy-verify", ".dex")
        f.deleteOnExit()
        project.readEntry(entry).use { ins -> f.outputStream().use { ins.copyTo(it) } }
        return f
    }

    @Test
    fun `替换字符串后 dex 里旧值消失新值出现 且能回退`() = runBlocking {
        val project = ApkProjects.open(requireSample())
        try {
            val hits = project.dexSearch(DexQuery("工作台", scope = DexQuery.Scope.STRING))
            assumeTrue("样本里没有「工作台」这个字符串，跳过", hits.isNotEmpty())
            val dexName = hits.first().dexName

            // ── 改 ──────────────────────────────────────────────
            val patches = project.replaceString("工作台", "操作台")
            println("── 替换产生 ${patches.size} 条改动 ──")
            patches.forEach { println("  ${it.target}  ${it.beforeHash?.take(8)} → ${it.afterHash?.take(8)} ｜ ${it.note}") }

            assertTrue(patches.isNotEmpty(), "应有 dex 被改动")
            // 不写死命中几个 dex：一个词可能同时出现在多个 dex 里，
            // 而 dex 的划分会随代码变化 —— 这里曾因为写死 1 而误报失败
            assertEquals(patches.size, project.patchCount, "改动条数应与产生的记录数一致")
            val rec = patches.firstOrNull { it.target == dexName }
                ?: error("搜索命中的 $dexName 不在改动列表里：${patches.map { it.target }}")
            assertTrue(project.state == WorkspaceState.DIRTY, "改完状态应为 DIRTY，实际 ${project.state}")

            // ── 验：改后内容确实进了覆盖层 ──────────────────────
            //
            // 这里用「字节层面 + 引擎搜索」两条独立检查，而不是「dump 出来用 dexlib2
            // 逐条扫 const-string 指令」—— 后者在这个 dex 上读不出正确结果：
            // 字节里含「操作台」=true，用 dexlib2 从裸文件加载扫描却读不到。
            // 原因未完全定位（猜是 dexlib2 对「从 APK 容器加载」与「从裸文件加载」
            // 的处理差异：同一个 dex，走容器能扫到指令，走裸文件读不出）。
            // 引擎自己的 dexSearch 走的是容器路径，能正常读到 —— 而那才是产品实际用的路径。
            // 保留一条会误报的断言比换掉它更糟，所以换成下面这两条。
            val dumped = dumpTo(project, dexName)
            val raw = dumped.readBytes().toString(Charsets.UTF_8)
            println("── 搜索首个命中：$dexName｜改动目标是 ${patches.map { it.target }}")
            println("── dump 出 ${dumped.length()}B")
            assertTrue(raw.contains("操作台"), "新值必须真的写进 dex（字节层面）")
            assertFalse(raw.contains("工作台"), "旧值必须从 dex 里消失（字节层面）")

            val fromEngine = project.dexSearch(DexQuery("操作台", scope = DexQuery.Scope.STRING))
            assertTrue(fromEngine.isNotEmpty(), "引擎侧也应能搜到新值（走 APK 容器加载的路径）")
            println("── 双路校验通过：字节含新值、不含旧值；引擎搜到 ${fromEngine.size} 处")

            // 覆盖层的 size 要与读到的内容一致
            val entry = project.list().first { it.path == dexName }
            val dumpedLen = File(dumpTo(project, dexName).absolutePath).length()
            println("── size 比对：list()=${entry.size} dump=$dumpedLen 条目=$dexName")
            assertEquals(dumpedLen, entry.size, "list() 的 size 应与覆盖层一致")

            // ── 回退 ────────────────────────────────────────────
            // 一次替换可能命中多个 dex，要把它们全退掉才算干净
            patches.forEach { project.revert(it.id) }
            println("── 回退 ${patches.size} 条后：patchCount=${project.patchCount} state=${project.state}")
            assertEquals(0, project.patchCount, "回退后不应还剩改动")
            assertEquals(WorkspaceState.UNPACKED, project.state, "回退干净后状态应回到 UNPACKED")

            val restored = dumpTo(project, dexName).readBytes()
            println("── 回退后 dump 出 ${restored.size}B")

            // 回退后应与**原包里该条目逐字节一致**。
            //
            // 不能断言「不含新值」：样本包的 classes2.dex 里本来就有一处「操作台」，
            // 那种断言是写死样本内容的老毛病（这个坑栽过不止一次）。
            // 逐字节比对才是「回退干净」的准确表达。
            val originalBytes = File.createTempFile("smithy-orig", ".dex").also { f ->
                ZipFile(requireSample()).use { z ->
                    z.getInputStream(z.getEntry(dexName)).use { ins ->
                        f.outputStream().use { ins.copyTo(it) }
                    }
                }
            }.readBytes()
            assertTrue(
                originalBytes.contentEquals(restored),
                "回退后应与原包逐字节一致（原包 ${originalBytes.size}B，回退后 ${restored.size}B）",
            )
        } finally {
            project.close(keepArtifacts = false)
        }
    }

    @Test
    fun `正则替换与不存在的字符串`() = runBlocking {
        val project = ApkProjects.open(requireSample())
        try {
            // 不存在的字符串不该产生任何改动（否则每次调用都会写一个新 dex）
            val none = project.replaceString("SmithyNoSuchString_9f8e7d6c", "x")
            println("── 替换不存在的字符串 → ${none.size} 条改动")
            assertTrue(none.isEmpty(), "无命中就不该产生改动记录")
            assertEquals(0, project.patchCount)
            assertTrue(project.state == WorkspaceState.UNPACKED, "没改动就不该进 DIRTY")

            // 回退一个不存在的 id 应报错，而不是静默成功
            val err = runCatching { project.revert("no-such-patch-id") }.exceptionOrNull()
            println("── 回退不存在的 id → ${err?.javaClass?.simpleName}: ${err?.message}")
            assertTrue(err is NoSuchElementException, "必须报错，静默成功会让 UI 以为退掉了")
        } finally {
            project.close(keepArtifacts = false)
        }
    }

    /**
     * 诊断：逐层验证 DexRewriter 的接管点，定位链路在哪一层断掉。
     * 1) 直接对指令逐条调 rewriter —— 验证我们的 override 本身有效
     * 2) 对整个 dex 调 rewriter —— 验证 DexRewriter 内部是否真的走到了 instruction 层
     */
    @Test
    fun `诊断 rewriter 链路`() = runBlocking {
        val apk = requireSample()
        val index = dev.smithy.engine.internal.DexIndex(apk, 21)
        try {
            val dexName = index.names.firstOrNull { n ->
                index.dex(n)?.classes?.any { it.type == "Ldev/smithy/Tab;" } == true
            }
            assumeTrue("样本里找不到 Tab 类，跳过", dexName != null)
            val dex = index.dex(dexName!!)!!
            val cls = dex.classes.first { it.type == "Ldev/smithy/Tab;" }
            val body = cls.methods.first { it.name == "<clinit>" }.implementation!!
            println("── <clinit> 指令数 = ${body.instructions.count()}")

            var hits = 0
            val module = object : RewriterModule() {
                override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> =
                    object : InstructionRewriter(rewriters) {
                        override fun rewrite(instruction: Instruction): Instruction {
                            val s = ((instruction as? ReferenceInstruction)?.reference
                                as? StringReference)?.string
                            if (s != "工作台") return super.rewrite(instruction)
                            hits++
                            return ImmutableInstruction21c(
                                instruction.opcode,
                                (instruction as Instruction21c).registerA,
                                ImmutableStringReference("操作台"),
                            )
                        }
                    }
            }
            val dr = DexRewriter(module)
            val insR = dr.instructionRewriter
            println("── dr.instructionRewriter = ${insR.javaClass.name}")
            println("── 是我们的匿名类吗 = ${insR.javaClass.name.contains("DexPatchTest")}")

            // ① 逐条调用
            val b1 = hits
            for (ins in body.instructions) insR.rewrite(ins)
            println("── ① 逐条调用 rewriter → hits 增量 = ${hits - b1}")

            // ② 整 dex：必须真的写出文件才会展开惰性代理，光数类数不会往下走到指令层
            val b2 = hits
            val nd = dr.dexFileRewriter.rewrite(dex)
            val tmpDex = File.createTempFile("diag", ".dex")
            tmpDex.deleteOnExit()
            DexFileFactory.writeDexFile(tmpDex.absolutePath, nd)
            println("── ② 写出 dex 后 → hits 增量 = ${hits - b2}，输出 ${tmpDex.length()} 字节")

            assertTrue(hits - b1 > 0, "① 逐条调用必须命中 —— 不命中说明我们的 override 没生效")
            // ② 是回归断言：DexRewriter 返回的是惰性代理，必须靠 writeDexFile 把整棵树展开，
            // 否则 instruction 层永远不被访问（曾因此静默零命中、改动被悄悄丢掉）
            assertTrue(
                hits - b2 > 0,
                "② 整 dex 重写也必须命中 —— 为 0 说明惰性代理没被展开，实际改动会静默丢失",
            )
        } finally {
            index.close()
        }
    }
}
