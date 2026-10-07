package dev.smithy.fs

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer

/**
 * 可选组件的下载与解包。
 *
 * 这一层最难的两件事都不是「能不能下」，而是：
 * 1. **半个文件**：手机上下 600MB 断一次是常态 —— 断了要能接着下，服务器不认续传时
 *    不能把两段接起来（接起来会得到「体积对、内容全错」的文件，要到校验才发现）。
 * 2. **归档不是自己的**：往设备上落远端归档，得挡住 `..`、得把符号链接还原
 *    （Alpine 的 `/bin/sh -> /bin/busybox` 解成空文件，整个 rootfs 就废了 ——
 *    `TarReader.extractAll` 正是这么解的，所以这里另写了一份解包）。
 *
 * HTTP 用 MockWebServer 造，不装任何「大概是这样」的假设；真下载那条由
 * `SMITHY_REAL_NET=1` 门控（真去下 Alpine 的 4MB minirootfs）。
 */
class AddOnTest {

    /** Kotlin 没有八进制字面量：0755 = 493、0644 = 420（tar 头里写的是八进制字符串）。 */
    private companion object {
        const val MODE_755 = 493
        const val MODE_644 = 420
    }

    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun stopServers() {
        servers.forEach { runCatching { it.shutdown() } }
        servers.clear()
    }

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-addon-${System.nanoTime()}").apply { mkdirs() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── 造归档 ──────────────────────────────────────────────

    /** 手写 ustar 头：mode / typeflag / 链接指向都要能摆出来（解包逻辑正是读这些）。 */
    private fun tarHeader(name: String, size: Long, type: Char, mode: Int, link: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(b, 0, h, off, b.size)
        }
        put(0, name.take(100))
        put(100, mode.toString(8).padStart(7, '0'))
        put(108, "0000000")
        put(116, "0000000")
        put(124, size.toString(8).padStart(11, '0') + " ")
        put(136, "00000000000 ")
        h[156] = type.code.toByte()
        if (link.isNotEmpty()) put(157, link.take(100))
        put(257, "ustar  \u0000")
        for (i in 148..155) h[i] = ' '.code.toByte()
        var sum = 0L
        for (b in h) sum += b.toLong() and 0xFF
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    private class TEntry(
        val name: String,
        val content: String = "",
        val type: Char = '0',
        val mode: Int = MODE_644,
        val link: String = "",
    )

    private fun tarGz(entries: List<TEntry>): ByteArray {
        val raw = ByteArrayOutputStream()
        entries.forEach { e ->
            val body = e.content.toByteArray(Charsets.UTF_8)
            raw.write(tarHeader(e.name, if (e.type == '0') body.size.toLong() else 0L, e.type, e.mode, e.link))
            if (e.type == '0') {
                raw.write(body)
                repeat((512 - body.size % 512) % 512) { raw.write(0) }
            }
        }
        raw.write(ByteArray(1024))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw.toByteArray()) }
        return out.toByteArray()
    }

    private fun zipFile(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ── 规格与服务器 ────────────────────────────────────────

    private fun spec(
        bytes: Long,
        hash: String,
        archive: AddOnArchive = AddOnArchive.ZIP,
        kind: AddOnKind = AddOnKind.TOOLCHAIN,
        url: String = "https://example.invalid/test",
        strip: Int = 0,
        only: List<String> = emptyList(),
    ) = AddOnSpec(
        id = "test-bundle",
        name = "测试包",
        summary = "只在这组用例里用",
        kind = kind,
        url = url,
        bytes = bytes,
        sha256 = hash,
        archive = archive,
        license = "测试用",
        homepage = "https://example.invalid",
        stripComponents = strip,
        onlyPaths = only,
    )

    /** 起一个按顺序回应答的服务器（应答按调用顺序消费）。 */
    private fun server(vararg responses: MockResponse): MockWebServer {
        val s = MockWebServer()
        responses.forEach { s.enqueue(it) }
        s.start()
        servers += s
        return s
    }

    private fun ok(body: ByteArray) = MockResponse().setResponseCode(200).setBody(Buffer().write(body))

    private fun partial206(from: Int, body: ByteArray) =
        MockResponse().setResponseCode(206).setBody(Buffer().write(body.copyOfRange(from, body.size)))

    // ── 用例 ────────────────────────────────────────────────

    @Test
    fun `下 校验 解包 一路走通 符号链接与可执行位都还原`() {
        val body = tarGz(
            listOf(
                TEntry("bin/", type = '5', mode = MODE_755),
                TEntry("bin/tool", "#!/bin/sh\necho hi\n", mode = MODE_755),
                TEntry("bin/sh", type = '2', link = "tool"),
                TEntry("README.md", "说明", mode = MODE_644),
            ),
        )
        val s = server(ok(body)).let { srv ->
            spec(body.size.toLong(), sha256(body), AddOnArchive.TAR_GZ, url = srv.url("/bundle").toString())
        }
        val root = tmpDir()

        val r = AddOnManager(root).install(s)

        assertTrue(r.ok, "该装成功：${r.message}")
        val dir = File(root, s.kind.dirName)
        val tool = File(dir, "bin/tool")
        assertTrue(tool.isFile)
        // 可执行位：tar 里是 0755，解出来就得能执行（工具链靠这条）
        assertTrue(tool.canExecute(), "bin/tool 该是可执行的")
        // 符号链接不能被解成空文件 —— Alpine 的 /bin/sh 就是这一类
        val sh = File(dir, "bin/sh")
        assertTrue(java.nio.file.Files.isSymbolicLink(sh.toPath()), "bin/sh 该是符号链接")
        assertEquals("tool", java.nio.file.Files.readSymbolicLink(sh.toPath()).toString())
        assertFalse(File(dir, "README.md").canExecute(), "0644 的不该被给上可执行位")
        // 记账：装过就该查得到。
        assertNotNull(AddOnManager(root).installed(s))
    }

    @Test
    fun `断过的一半会带 Range 接着下 且内容接得上`() {
        val body = ByteArray(2000) { (it % 251).toByte() }
        val half = 800
        val srv = server(partial206(half, body))
        val s = spec(body.size.toLong(), sha256(body), kind = AddOnKind.OTHER, url = srv.url("/b").toString())
        val root = tmpDir()
        // 造出「上次下到一半」的现场
        File(root, "test-bundle.zip.part").writeBytes(body.copyOfRange(0, half))

        val file = AddOnManager(root).download(s)

        val req = srv.takeRequest()
        assertEquals("bytes=$half-", req.getHeader("Range"), "该接着下，不是从头来")
        assertEquals(body.size.toLong(), file.length())
        assertEquals(body.toList(), file.readBytes().toList(), "两段得接对（内容错位的话体积也对）")
    }

    @Test
    fun `服务器不认续传时从头下 而不是把两段接起来`() {
        val body = ByteArray(1500) { (it % 97).toByte() }
        val srv = server(ok(body))   // 带了 Range 也只回 200 + 全量
        val s = spec(body.size.toLong(), sha256(body), kind = AddOnKind.OTHER, url = srv.url("/b").toString())
        val root = tmpDir()
        File(root, "test-bundle.zip.part").writeBytes(body.copyOfRange(0, 500))

        val file = AddOnManager(root).download(s)

        assertEquals("bytes=500-", srv.takeRequest().getHeader("Range"))
        assertEquals(body.size.toLong(), file.length())
        assertEquals(body.toList(), file.readBytes().toList(), "接起来会得到 2000 字节的垃圾")
    }

    @Test
    fun `sha 不一致时明确失败 并把那份删掉`() {
        val body = zipFile("a.txt" to "hello")
        val srv = server(ok(body))
        val s = spec(body.size.toLong(), sha256("别的什么".toByteArray()), kind = AddOnKind.OTHER, url = srv.url("/b").toString())
        val root = tmpDir()

        val r = AddOnManager(root).install(s)

        assertFalse(r.ok)
        assertTrue(r.message!!.contains("校验不过"), "要说清是校验问题：${r.message}")
        assertTrue(r.hint!!.contains("sha256"), "要指出下一步：${r.hint}")
        assertFalse(File(root, "test-bundle.zip").exists(), "坏包不该留着")
        assertNull(AddOnManager(root).installed(s), "没装成功就不该有安装记录")
    }

    @Test
    fun `不完整的下载会被认出来 并告诉用户会接着下`() {
        val body = zipFile("a.txt" to "hello")
        val srv = server(ok(body.copyOfRange(0, 10)))   // 服务器只给一半就断
        val s = spec(body.size.toLong(), sha256(body), kind = AddOnKind.OTHER, url = srv.url("/b").toString())
        val root = tmpDir()

        val r = AddOnManager(root).install(s)

        assertFalse(r.ok)
        assertTrue(r.message!!.contains("不完整"), "要说清是下了一半：${r.message}")
        assertTrue(r.hint!!.contains("接着下"), "要告诉用户不用从头：${r.hint}")
        assertTrue(File(root, "test-bundle.zip.part").exists(), "半个文件要留着，下次接着下")
    }

    @Test
    fun `归档里的 路径穿越 条目会被丢掉`() {
        val body = tarGz(
            listOf(
                TEntry("../evil.txt", "我不该出现在外面"),
                TEntry("bin/ok.txt", "正常文件"),
            ),
        )
        val srv = server(ok(body))
        val s = spec(body.size.toLong(), sha256(body), AddOnArchive.TAR_GZ, url = srv.url("/b").toString())
        val root = tmpDir()
        // 安装根在 inner/ 下：`../evil.txt` 正好会落到 root/ 里
        val mgr = AddOnManager(File(root, "inner").apply { mkdirs() })

        val r = mgr.install(s)

        assertTrue(r.ok, "装本身该成功：${r.message}")
        assertFalse(File(root, "evil.txt").exists(), "越界的条目必须被丢掉")
        assertTrue(File(root, "inner/${s.kind.dirName}/bin/ok.txt").isFile, "正常条目要照常解出来")
    }

    @Test
    fun `strip 与 onlyPaths 能只取想要的那一层`() {
        val body = zipFile(
            "android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" to "clang 二进制",
            "android-ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h" to "jni 头",
            "android-ndk/toolchains/llvm/prebuilt/linux-x86_64/lib/libLLVM.so" to "很大的库",
        )
        val srv = server(ok(body))
        // strip=5 去掉 android-ndk/toolchains/llvm/prebuilt/linux-x86_64 这五层
        val s = spec(
            body.size.toLong(), sha256(body), url = srv.url("/b").toString(),
            strip = 5, only = listOf("sysroot"),
        )
        val root = tmpDir()

        val r = AddOnManager(root).install(s)

        assertTrue(r.ok, "该装成功：${r.message}")
        val dir = File(root, s.kind.dirName)
        assertTrue(File(dir, "sysroot/usr/include/jni.h").isFile, "想要的要解出来")
        assertFalse(File(dir, "bin/clang").exists(), "不要的那层不该落盘（656MB 的 NDK 只想要 sysroot）")
        assertFalse(File(dir, "lib/libLLVM.so").exists())
    }

    @Test
    fun `zip 里 bin 下的文件会给上可执行位`() {
        val body = zipFile("bin/clang++" to "#!/bin/sh\n", "lib/x.so" to "库")
        val srv = server(ok(body))
        val s = spec(body.size.toLong(), sha256(body), url = srv.url("/b").toString())
        val root = tmpDir()

        assertTrue(AddOnManager(root).install(s).ok)

        val dir = File(root, s.kind.dirName)
        assertTrue(File(dir, "bin/clang++").canExecute(), "zip 没有权限字段，bin/ 下的一律给可执行位")
        assertFalse(File(dir, "lib/x.so").canExecute())
    }

    /**
     * 真从那份 656MB 的 NDK 包里**只取 arm64 的 sysroot**（约 54MB）。
     *
     * 门控：`SMITHY_REAL_NDK_ZIP` 指向本地那份 zip。这条同时验两件事：
     * ① 登记里的 sha256 与真实文件对得上（校验值不是抄来的）；② `strip` + `onlyPaths`
     * 真的把宿主机 clang 与别的 ABI 挡在外面 —— 手机上装的是 54MB，不是 656MB。
     */
    @Test
    fun `真从 NDK 包里只取 arm64 sysroot`() {
        val zipPath = System.getenv("SMITHY_REAL_NDK_ZIP")
        org.junit.Assume.assumeTrue(
            "没设 SMITHY_REAL_NDK_ZIP，跳过",
            !zipPath.isNullOrBlank() && File(zipPath!!).isFile,
        )
        val spec = AddOnCatalog.sysrootNdkArm64
        val root = tmpDir()

        val res = AddOnManager(root).installFromLocal(spec, File(zipPath!!))

        assertTrue(res.ok, "该装成功：${res.message} / ${res.hint}")
        val dir = File(root, spec.kind.dirName)
        assertTrue(File(dir, "sysroot/usr/include/jni.h").isFile, "要有 jni.h")
        assertTrue(File(dir, "sysroot/usr/include/android/log.h").isFile, "要有 android/log.h")
        assertTrue(File(dir, "sysroot/usr/include/c++/v1/string").isFile, "要有 libc++ 头")
        assertTrue(
            File(dir, "sysroot/usr/lib/aarch64-linux-android/26/liblog.so").isFile,
            "要有 arm64 的桩库",
        )
        assertFalse(File(dir, "sysroot/usr/lib/x86_64-linux-android").exists(), "别的 ABI 不该落盘")
        assertFalse(File(dir, "bin/clang").exists(), "宿主机的 clang 一个字节都不该落盘")
        assertTrue(res.install!!.bytes < 100L * 1024 * 1024, "只留 sysroot 不该有几百 MB：${res.install.bytes}")
    }

    /**
     * 真去下一趟：Alpine 的 minirootfs（4MB）。
     *
     * 门控用例 —— 每次构建都下一遍 4MB 不合适，但它才是「这套东西真能用」的证据：
     * 真 URL、真 HTTP、真 sha256、真解包，最后检查解出来的是不是一个能用的 Linux 用户态。
     */
    @Test
    fun `真下载 Alpine rootfs 并解出可用的用户态`() {
        org.junit.Assume.assumeTrue("没设 SMITHY_REAL_NET，跳过真下载", System.getenv("SMITHY_REAL_NET") == "1")
        val spec = AddOnCatalog.rootfsAlpine
        val root = tmpDir()
        val mgr = AddOnManager(root)

        val r = mgr.install(spec)

        assertTrue(r.ok, "该装成功：${r.message} / ${r.hint}")
        val dir = File(root, spec.kind.dirName)
        // busybox 是 aarch64 的静态 ELF —— 解出来的必须真是它，不是被当普通文件写坏的残骸
        val busybox = File(dir, "bin/busybox")
        assertTrue(busybox.isFile, "bin/busybox 该在")
        val head = busybox.readBytes().take(20)
        assertEquals(0x7f, head[0].toInt())
        assertEquals(0xB7, head[18].toInt() and 0xFF, "busybox 该是 aarch64")
        assertTrue(busybox.canExecute(), "busybox 该可执行")
        // Alpine 里满是指向 /bin/busybox 的软链接（applet）—— 解成空文件的话 rootfs 就是废的
        val sh = File(dir, "bin/sh")
        assertTrue(java.nio.file.Files.isSymbolicLink(sh.toPath()), "bin/sh 该是软链接")
        assertEquals("/bin/busybox", java.nio.file.Files.readSymbolicLink(sh.toPath()).toString())
        assertNotNull(mgr.installed(spec))
        // 版本文件读得出来 = 目录树基本完整
        val release = File(dir, "etc/alpine-release")
        assertTrue(release.isFile, "该有 etc/alpine-release")
        assertTrue(release.readText().trim().startsWith("3.20"), "版本不对：${release.readText()}")
    }
}
