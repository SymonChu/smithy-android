package dev.smithy.fs

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer

/**
 * 「别的用户也能装」这件事的两条硬要求：
 *
 * 1. **主地址不通要能换镜像** —— GitHub / Google / Alpine 官方 CDN 在中国网络里常年抽风，
 *    一个下不动的主地址等于没有这条路。
 * 2. **镜像给的东西不对也不能凑合用** —— 换地址验的还是同一个 sha256，不对就再换。
 *
 * 顺带钉住 Termux 那条（零下载）的命令拼法：它走 root 通道执行，
 * 命令行错了在设备上表现为一串看不懂的报错。
 */
class AddOnMirrorTest {

    private val servers = mutableListOf<MockWebServer>()

    private fun dir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    /** 一份能解开的 tar.gz（装成功才算成功）。 */
    private fun bundle(entry: Pair<String, String>): ByteArray {
        val tar = ByteArrayOutputStream()
        fun pad(n: Int) = ByteArray((512 - n % 512) % 512)
        fun header(name: String, size: Long): ByteArray {
            val h = ByteArray(512)
            fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
            put(0, name); put(100, "0000644\u0000"); put(108, "0000000\u0000"); put(116, "0000000\u0000")
            put(124, size.toString(8).padStart(11, '0') + "\u0000"); put(136, "00000000000\u0000")
            put(148, "        "); put(156, "0"); put(257, "ustar\u000000")
            var sum = 0; h.forEach { sum += (it.toInt() and 0xFF) }
            put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
            return h
        }
        val content = entry.second.toByteArray()
        tar.write(header(entry.first, content.size.toLong())); tar.write(content); tar.write(pad(content.size))
        tar.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }

    private fun server(vararg responses: MockResponse): MockWebServer {
        val s = MockWebServer()
        responses.forEach { s.enqueue(it) }
        s.start()
        servers += s
        return s
    }

    private fun spec(url: String, mirrors: List<String>, body: ByteArray) = AddOnSpec(
        id = "demo-bundle",
        name = "测试包",
        summary = "测试",
        kind = AddOnKind.TOOLCHAIN,
        url = url,
        mirrors = mirrors,
        bytes = body.size.toLong(),
        sha256 = sha256(body),
        archive = AddOnArchive.TAR_GZ,
        license = "测试",
        homepage = "",
        stripComponents = 0,
    )

    @Test
    fun `主地址挂了要自动换镜像`() {
        val good = bundle("bin/clang++" to "#!/bin/sh\n")
        val primary = server(MockResponse().setResponseCode(500))          // 主地址：挂了
        val mirror = server(MockResponse().setResponseCode(200).setBody(Buffer().write(good)))
        val root = dir("mirror-a")

        val res = AddOnManager(root).install(spec(primary.url("/x.tar.gz").toString(),
            listOf(mirror.url("/x.tar.gz").toString()), good))

        assertTrue(res.ok, "主地址挂了但镜像好的，应该装成功：${res.message}")
        assertTrue(File(File(root, AddOnKind.TOOLCHAIN.dirName), "bin/clang++").isFile)
    }

    @Test
    fun `镜像给的内容 sha 不对 就换下一个`() {
        val good = bundle("bin/clang++" to "对的内容\n")
        val bad = bundle("bin/clang++" to "被换掉的假货\n")   // 能解开，但和登记的 sha 不一样
        val primary = server(MockResponse().setResponseCode(200).setBody(Buffer().write(bad)))
        val mirror = server(MockResponse().setResponseCode(200).setBody(Buffer().write(good)))
        val root = dir("mirror-b")

        val res = AddOnManager(root).install(spec(primary.url("/x.tar.gz").toString(),
            listOf(mirror.url("/x.tar.gz").toString()), good))

        assertTrue(res.ok, "第一份 sha 不对，应该换第二个地址：${res.message}")
        val extracted = File(File(root, AddOnKind.TOOLCHAIN.dirName), "bin/clang++").readText()
        assertTrue(extracted.contains("对的内容"), "解出来的必须是第二个地址那份，而不是假货：$extracted")
    }

    @Test
    fun `都不行时报的错要说清每个地址`() {
        val primary = server(MockResponse().setResponseCode(500))
        val mirror = server(MockResponse().setResponseCode(404))
        val root = dir("mirror-c")
        val body = bundle("a.txt" to "x")

        val res = AddOnManager(root).install(spec(primary.url("/x.tar.gz").toString(),
            listOf(mirror.url("/x.tar.gz").toString()), body))

        assertFalse(res.ok)
        val msg = listOfNotNull(res.message, res.hint).joinToString("\n")
        assertTrue(msg.contains("500") || msg.contains("HTTP"), "要说清是 HTTP 多少：$msg")
    }

    // ── Termux 那条路（零下载，走 root 通道） ─────────────────────────

    private class RecordingShell(private val onRun: (String) -> Unit = {}) : ShellChannel {
        override val name = "测试通道"
        val commands = mutableListOf<String>()
        override fun available() = true
        override fun exec(command: String, timeoutSeconds: Long): ShellResult {
            commands += command
            onRun(command)
            return ShellResult(0, "")
        }
    }

    @Test
    fun `Termux 探测与命令行 都走 root 通道 且路径是 Termux 的`() {
        val prefix = "/data/data/com.termux/files/usr"
        val src = dir("termux-src")
        File(src, "module.cpp").writeText("// 空")
        val out = File(src, "out.so")
        // 模拟编译器：把产物写出来（真机上这一步真编）
        val shell = RecordingShell { cmd -> if (cmd.contains("clang++")) out.writeText("假 so") }
        val chain = TermuxClangToolchain(File(prefix), shell)

        assertTrue(chain.available(), "test -x 通过就该算可用")
        // 路径要**带引号**（应用私有路径里可能有空格/特殊字符），所以断言也按带引号的形式
        assertEquals("test -x '$prefix/bin/clang++'", shell.commands.first())

        val res = chain.build(
            NativeBuildSpec(
                abi = NativeAbi.ARM64_V8A, apiLevel = 26, sourceDir = src,
                sources = listOf("module.cpp"), outFile = out, flags = ModuleNativeBuild.TEMPLATE_FLAGS,
            ),
        )
        assertTrue(res.ok, res.log)
        assertNotNull(res.soFile)
        val cmd = shell.commands.last()
        // 命令里的路径都是带引号的（拼给 shell 的），断言也按带引号的形式来
        assertTrue(cmd.contains("LD_LIBRARY_PATH='$prefix/lib'"), "要指到 Termux 的 lib：$cmd")
        assertTrue(cmd.contains("$prefix/bin/clang++"), "用的是 Termux 里的 clang：$cmd")
        assertTrue(cmd.contains("--target=aarch64-linux-android26"), "要交叉到 arm64：$cmd")
        assertTrue(cmd.contains("-nostdlib++"), "Termux 没有静态 libc++，得用 -nostdlib++：$cmd")
        assertTrue(chain.describe().contains("未在真机验证"), "没验过就得写出来：${chain.describe()}")
    }

    @Test
    fun `没装 Termux 时不登记 也不报错`() {
        val shell = object : ShellChannel {
            override val name = "测试通道"
            override fun available() = true
            override fun exec(command: String, timeoutSeconds: Long) = ShellResult(1, "")
        }
        assertTrue(NativeToolchains.scanTermux(shell) == null, "探测不到就该安静返回 null")
        assertTrue(NativeToolchains.scanTermux(null) == null, "没有 root 通道也不该抛")
    }
}
