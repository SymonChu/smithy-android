package dev.smithy.engine.internal

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.plugins.input.dex.DexInputPlugin
import java.io.File

/**
 * jadx 单类反编译。
 *
 * **为什么按 dex 缓存会话**：jadx 的成本几乎全在「加载并解析整个 dex」这一步
 * （65k 方法的 dex 要数秒），反编译其中某个类只是从已解析好的树上取一个节点。
 * 连续看同一个 dex 里的几个类时，缓存让第二次之后降到毫秒级。
 *
 * **为什么只导出含目标类的那个 dex**：jadx 的输入插件只接受文件或字节流，
 * 而我们的 dex 在 zip 里。把整包交给 jadx 会让它解析全部十几个 dex
 * （手机上白等十几秒、多占几十 MB 内存），所以按需导出单个 dex。
 *
 * **视图读的是原包**：dump 出来的是原包里的字节。工作区覆盖层里的改动
 * （改过的字符串、改过的 smali）不在其中 —— UI 必须把这件事讲清楚，
 * 否则用户会以为「改完为什么 Java 视图还是旧的」。要看待合并的改动，看「改动」标签。
 */
internal class JadxBridge(private val index: DexIndex) : AutoCloseable {

    private val sessions = mutableMapOf<String, Session>()

    private class Session(val jadx: JadxDecompiler)

    /**
     * 反编译一个类，返回 Java 源码。
     *
     * 类不在包内（找不到所属 dex 或 jadx 里没有这个类）返回 null，
     * 由调用方区分「类不存在」与「反编译失败」两种情况。
     */
    @Synchronized
    fun decompile(descriptor: String, workDir: File): String? {
        val dexName = index.dexOfClass(descriptor) ?: return null
        val session = sessions[dexName] ?: openSession(dexName, workDir)
        val javaClass = session.jadx.searchJavaClassByOrigFullName(DexIndex.prettyName(descriptor))
            ?: return null
        return runCatching { javaClass.code }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun openSession(dexName: String, workDir: File): Session {
        val dexFile = index.dump(dexName, File(workDir, dexName))
            ?: throw IllegalStateException("无法从包里导出 $dexName")

        val args = JadxArgs().apply {
            setInputFile(dexFile)
            setSkipResources(true)          // 只要代码，资源表在手机上解析是纯浪费
            setSkipSources(false)
            setShowInconsistentCode(true)   // 反编译不完整时也给部分结果，好过整类不给
            setUseImports(true)
            setThreadsCount(1)              // 手机上多线程反编译会放大内存峰值，反而更慢
        }

        val jadx = JadxDecompiler(args)
        // 不能指望 SPI 自动发现插件：Android 打包会动 META-INF/services，
        // 显式注册才可靠。JadxPluginManager 按 plugin id 去重，重复注册无害。
        jadx.registerPlugin(DexInputPlugin())

        try {
            jadx.load()
        } catch (t: Throwable) {
            runCatching { jadx.close() }
            throw IllegalStateException("jadx 加载 $dexName 失败: ${t.message}", t)
        }
        return Session(jadx).also { sessions[dexName] = it }
    }

    @Synchronized
    override fun close() {
        // 每个会话内部有线程池与解析树，不关会一直占着内存
        sessions.values.forEach { runCatching { it.jadx.close() } }
        sessions.clear()
    }
}
