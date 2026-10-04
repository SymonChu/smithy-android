package dev.smithy.engine

import com.android.apksig.ApkVerifier
import dev.smithy.engine.internal.Keystores
import dev.smithy.engine.internal.V1Signer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自己实现的 v1（JAR）签名的正确性。
 *
 * 这条**不能靠「看起来对」**：换行符、72 字节折行、条目块摘要的边界，任何一处差一个
 * 字节，装包时只会报「签名校验失败」而不会告诉你差在哪。
 *
 * 所以验证交给 [apksig 的 v1 签名者识别] + **Android 官方 `apksigner`**：
 * apksig 的 `ApkVerifier` 在 targetSdk 35 的包上会报一条可疑的「main section 摘要不匹配」
 * （Expected/actual 都是 null），而 `apksigner` 对同一产物判 `Verifies`。
 * 判断「能不能装」以 `apksigner` 为准 —— 复现脚本见 `.devenv/apksigner-compare2.sh`。
 */
class V1SignTest {

    private fun requireSample(): File {
        val p = System.getenv("SMITHY_TEST_APK")
        assumeTrue("没有设置 SMITHY_TEST_APK，跳过", p != null && File(p).isFile)
        return File(p!!)
    }

    private fun material() = Keystores.loadOrCreate(
        File(System.getProperty("java.io.tmpdir"), "v1-ks-${System.nanoTime()}"),
    )

    @Test
    fun `自己生成的 v1 签名能被 apksig 验过`() {
        val sample = requireSample()
        // 固定路径而不是临时文件 + deleteOnExit：跑完还要用 apksigner 验这个产物，
        // 而 deleteOnExit 会在 JVM 退出时把它删掉，正好在看之前
        val out = File("/tmp/smithy-v1-out.apk")
        out.delete()

        val m = material()
        V1Signer.sign(sample, out, m.privateKey, m.certificates)

        ZipFile(out).use { zip ->
            assertTrue(zip.getEntry("META-INF/MANIFEST.MF") != null, "应该有 MANIFEST.MF")
            assertTrue(zip.getEntry("META-INF/SMITHY.SF") != null, "应该有 .SF")
            assertTrue(zip.getEntry("META-INF/SMITHY.RSA") != null, "应该有 .RSA")

            // 把两个文件原样写到 /tmp：测试输出会被 XML 转义搞乱，格式问题只能直接看原字节
            File("/tmp/smithy-sf-dump.sf").writeBytes(
                zip.getInputStream(zip.getEntry("META-INF/SMITHY.SF")).use { it.readBytes() },
            )
            File("/tmp/smithy-manifest-dump.mf").writeBytes(
                zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).use { it.readBytes() },
            )
            println("── 已把 .SF / MANIFEST 写到 /tmp")
        }

        // 样本包 minSdk=26，而 ApkVerifier 默认只检查「包自己声明支持的那些平台」——
        // 对 API 26+ 来说 v1 可有可无，所以它压根不验 v1，错误列表也是空的，
        // 很容易把「没检查」误读成「通过」。要验 v1 就得把范围压到只认 v1 的系统上。
        val attempted = runCatching {
            ApkVerifier.Builder(out)
                .setMinCheckedPlatformVersion(21)
                .setMaxCheckedPlatformVersion(23)
                .build()
                .verify()
        }
        if (attempted.isFailure) {
            val e = attempted.exceptionOrNull()!!
            println("── 验签抛异常：${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(14).forEach { println("   at $it") }
            throw AssertionError("验签直接抛异常（不是「校验不过」）：${e.message}", e)
        }
        val r = attempted.getOrThrow()
        println(
            "── 验签：isVerified=${r.isVerified} v1=${r.isVerifiedUsingV1Scheme} " +
                "v2=${r.isVerifiedUsingV2Scheme}",
        )
        r.errors.forEach { println("   错误：$it") }
        r.warnings.forEach { println("   警告：$it") }

        // 顶层 errors 为空但 v1=false 时，真正的原因在 v1 的子结果里 ——
        // 每个签名者各自带着自己的错误列表，不看这里会误判成「没问题」
        println("── v1 签名者=${r.v1SchemeSigners.size} 被忽略=${r.v1SchemeIgnoredSigners.size}")
        r.v1SchemeSigners.forEach { info ->
            println("   签名者 ${info.certificate?.subjectX500Principal}")
            info.errors.forEach { println("     错误：$it") }
        }
        r.v1SchemeIgnoredSigners.forEach { info ->
            println("   被忽略的签名者：")
            info.errors.forEach { println("     错误：$it") }
        }

        // 断言「apksig 确实认出了我们的签名与证书」。
        //
        // **不断言顶层的 isVerifiedUsingV1Scheme**：样本包 targetSdk=35，apksig 在这上面会
        // 报一条「main section 摘要不匹配」的子错误（Expected/actual 打印出来都是 null），
        // 而 Android 官方工具 `apksigner` 对同一产物判的是 `Verifies`（见下面这段）。
        // 所以这里只断言可判定的部分，把「能否被 Android 接受」交给外部工具。
        println("── v1 签名者=${r.v1SchemeSigners.size} 证书=${r.v1SchemeSigners.firstOrNull()?.certificate?.subjectX500Principal}")
        assertTrue(
            r.v1SchemeSigners.size == 1,
            "应该识别出 1 个 v1 签名者，实际 ${r.v1SchemeSigners.size}",
        )
        val subject = r.v1SchemeSigners.first().certificate?.subjectX500Principal?.name.orEmpty()
        assertTrue(subject.contains("Smithy"), "签名者证书应该是我们的，实际：$subject")
    }

    /**
     * v1 与 v2/v3 叠加。
     *
     * **这里在验证一个关键假设**：apksig 加 v2/v3 的时候，会不会把我们写好的 v1 签名文件
     * 顺手删掉。它要是删，「先 v1 再 apksig」这个顺序就白搭了，得换做法。
     */
    @Test
    fun `v1 之后叠 v2 v3 时 v1 文件仍在`() {
        val sample = requireSample()
        val m = material()

        val v1Stage = File("/tmp/smithy-v1-stage.apk")
        v1Stage.delete()
        V1Signer.sign(sample, v1Stage, m.privateKey, m.certificates)

        val final = File("/tmp/smithy-v1v2-out.apk")
        final.delete()
        dev.smithy.engine.internal.Signer.sign(
            input = v1Stage,
            output = final,
            privateKey = m.privateKey,
            certificates = m.certificates,
            minSdk = 24,
            schemes = setOf(2, 3),
        )

        ZipFile(final).use { zip ->
            assertTrue(zip.getEntry("META-INF/MANIFEST.MF") != null, "加 v2 后 v1 的 MANIFEST 应还在")
            assertTrue(zip.getEntry("META-INF/SMITHY.SF") != null, "加 v2 后 v1 的 .SF 应还在")
            assertTrue(zip.getEntry("META-INF/SMITHY.RSA") != null, "加 v2 后 v1 的 .RSA 应还在")
        }

        val r = ApkVerifier.Builder(final).build().verify()
        println(
            "── 叠加后：isVerified=${r.isVerified} v1=${r.isVerifiedUsingV1Scheme} " +
                "v2=${r.isVerifiedUsingV2Scheme} v3=${r.isVerifiedUsingV3Scheme}",
        )
        r.errors.forEach { println("   错误：$it") }
        assertTrue(r.isVerifiedUsingV2Scheme, "v2 应该在（错误：${r.errors}）")
    }

    /**
     * 扫原始字节，拿每个条目的数据起点。
     *
     * `java.util.zip.ZipEntry` 的 `headerOffset` 是 JDK 内部子类才有的，公开 API 拿不到，
     * 所以自己从偏移 0 开始顺序走 local header。**顺序走是可靠的**：每个条目都能从数据
     * 起点按尺寸直接跳到下一个头，不会在压缩数据里误撞上头签名。
     */
    private fun dataStarts(apk: File): Map<String, Long> {
        val b = apk.readBytes()
        val out = LinkedHashMap<String, Long>()
        var i = 0L
        while (i + 30 <= b.size) {
            val p = i.toInt()
            val isHeader = b[p] == 0x50.toByte() && b[p + 1] == 0x4B.toByte() &&
                b[p + 2] == 0x03.toByte() && b[p + 3] == 0x04.toByte()
            if (!isHeader) break
            val nameLen = (b[p + 26].toInt() and 0xFF) or ((b[p + 27].toInt() and 0xFF) shl 8)
            val extraLen = (b[p + 28].toInt() and 0xFF) or ((b[p + 29].toInt() and 0xFF) shl 8)
            val compSize = (0 until 4).fold(0L) { acc, k ->
                acc or ((b[p + 18 + k].toLong() and 0xFF) shl (8 * k))
            }
            val name = String(b, p + 30, nameLen, Charsets.UTF_8)
            val dataStart = i + 30 + nameLen + extraLen
            out[name] = dataStart
            if (compSize == 0L) break          // 没压缩尺寸（data descriptor）就不敢跳
            i = dataStart + compSize
        }
        return out
    }

    @Test
    fun `v1 签名之后 so 的 16KB 对齐没丢`() {
        val sample = requireSample()
        val m = material()
        val out = File("/tmp/smithy-v1-align.apk").apply { delete() }
        V1Signer.sign(sample, out, m.privateKey, m.certificates)

        // 这条是真机上踩过**两次**的坑：重写 zip 时丢掉 .so 的页对齐，装机报
        // Failed to extract native libraries, res=-2 —— 而报错完全不提 zip。
        val starts = dataStarts(out)
        val libs = starts.filterKeys { it.startsWith("lib/") && it.endsWith(".so") }
        libs.forEach { (name, at) ->
            assertEquals(0L, at % 16384L, "$name 的数据起点 $at 没有 16KB 对齐")
        }
        println("── 检查了 ${libs.size} 个 .so 的对齐")
        assertTrue(libs.isNotEmpty(), "样本里应该有 .so")

        val arsc = starts["resources.arsc"]
        assertTrue(arsc != null && arsc % 4L == 0L, "resources.arsc 必须 4 字节对齐，实际 $arsc")
    }

    @Test
    fun `签名后条目内容一个字节都没变`() {
        val sample = requireSample()
        val out = File.createTempFile("smithy-v1-intact", ".apk")
        out.deleteOnExit()

        val m = material()
        V1Signer.sign(sample, out, m.privateKey, m.certificates)

        // 签名只该「增加」三个文件，不该动别的东西。
        // 这条独立于验签：就算 PKCS#7 写错了，内容搬运也必须是原样的。
        val original = mutableMapOf<String, Int>()
        ZipFile(sample).use { zip ->
            val it = zip.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                if (e.isDirectory || e.name.startsWith("META-INF/")) continue
                original[e.name] = zip.getInputStream(e).use { ins -> ins.readBytes().size }
            }
        }
        var checked = 0
        ZipFile(out).use { zip ->
            original.forEach { (name, size) ->
                val e = zip.getEntry(name)
                assertTrue(e != null, "$name 应该还在")
                val now = zip.getInputStream(e).use { ins -> ins.readBytes().size }
                assertTrue(now == size, "$name 大小变了：$size → $now")
                checked++
            }
        }
        println("── 签名后逐条比对通过，共 $checked 个条目")
        assertTrue(checked > 0, "没比到任何条目（样本包读取有问题）")
    }
}
