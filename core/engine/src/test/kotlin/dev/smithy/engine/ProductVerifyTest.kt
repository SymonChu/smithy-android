package dev.smithy.engine

import dev.smithy.engine.internal.ZipOffsets
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * zip 偏移读取 + 产物验证。
 *
 * 偏移这一层必须用**手工拼出来的 zip** 来测：`java.util.zip` 不暴露数据绝对偏移，
 * 拿它当参照物等于用被测对象自证。手拼的话每个偏移都是我们自己定的，断言才有意义。
 */
class ProductVerifyTest {

    // ── 手工拼 zip（本地头 + 数据 + 中央目录 + EOCD） ──────────

    private class Item(val name: String, val data: ByteArray, val method: Int, val extra: ByteArray)

    private fun buildZip(items: List<Item>): ByteArray {
        val out = ByteArrayOutputStream()
        val centrals = mutableListOf<ByteArray>()
        val offsets = mutableListOf<Long>()

        items.forEach { item ->
            val localOffset = out.size().toLong()
            offsets += localOffset
            val nameBytes = item.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(item.data) }.value

            val header = ByteArrayOutputStream()
            header.write(u32(0x04034b50))
            header.write(u16(20))                       // version needed
            header.write(u16(0))                        // flags
            header.write(u16(item.method))
            header.write(u16(0)); header.write(u16(0))  // time / date
            header.write(u32(crc))
            header.write(u32(item.data.size.toLong()))  // compressed (= stored)
            header.write(u32(item.data.size.toLong()))
            header.write(u16(nameBytes.size))
            header.write(u16(item.extra.size))
            header.write(nameBytes)
            header.write(item.extra)

            out.write(header.toByteArray())
            out.write(item.data)

            val central = ByteArrayOutputStream()
            central.write(u32(0x02014b50))
            central.write(u16(20)); central.write(u16(20))
            central.write(u16(0))
            central.write(u16(item.method))
            central.write(u16(0)); central.write(u16(0))
            central.write(u32(crc))
            central.write(u32(item.data.size.toLong()))
            central.write(u32(item.data.size.toLong()))
            central.write(u16(nameBytes.size))
            central.write(u16(item.extra.size))
            central.write(u16(0))                       // comment
            central.write(u16(0))                       // disk
            central.write(u16(0))                       // internal attrs
            central.write(u32(0))                       // external attrs
            central.write(u32(localOffset))
            central.write(nameBytes)
            central.write(item.extra)
            centrals += central.toByteArray()
        }

        val cdOffset = out.size().toLong()
        val cd = ByteArrayOutputStream()
        centrals.forEach { cd.write(it) }
        val cdBytes = cd.toByteArray()
        out.write(cdBytes)

        val eocd = ByteArrayOutputStream()
        eocd.write(u32(0x06054b50))
        eocd.write(u16(0)); eocd.write(u16(0))
        eocd.write(u16(items.size)); eocd.write(u16(items.size))
        eocd.write(u32(cdBytes.size.toLong()))
        eocd.write(u32(cdOffset))
        eocd.write(u16(0))
        out.write(eocd.toByteArray())
        return out.toByteArray()
    }

    /** 一个合法的 extra 块：`[id:FFFF][len][数据]`，总长必须是 4 的倍数。 */
    private fun padExtra(align: Long, dataOffsetGuess: Long): ByteArray {
        val pad = ((align - dataOffsetGuess % align) % align).toInt()
        if (pad == 0) return ByteArray(0)
        // 块长 = 4 + 数据长度；要补 pad 字节 ⇒ 数据长度 ≡ pad - 4 (mod align)
        val len = ((pad - 4) % align + align) % align
        val bytes = ByteArray(4 + len.toInt())
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xFF.toByte()
        bytes[2] = (len and 0xFF).toByte(); bytes[3] = ((len shr 8) and 0xFF).toByte()
        return bytes
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    @Test
    fun `读到的数据偏移与我们手工摆的位置一致`() {
        // 第一个条目故意不补 extra：数据起点 = 30 + 名字长
        val soName = "lib/arm64-v8a/libx.so"
        val so = Item(soName, ByteArray(16), 0, ByteArray(0))
        val firstData = 30L + soName.toByteArray().size

        val zip = buildZip(listOf(so))
        val file = File.createTempFile("offset", ".zip").apply { writeBytes(zip) }

        val offsets = ZipOffsets.read(file)
        assertEquals(1, offsets.size)
        assertEquals(firstData, offsets[0].dataOffset, "数据偏移算错了，对齐检查就全是假的")
    }

    @Test
    fun `补了 extra 之后偏移按 extra 长度平移`() {
        val name = "resources.arsc"
        val nameLen = name.toByteArray().size
        // 目标：让数据起点落在 4 的倍数上
        val extra = padExtra(4, 30L + nameLen)
        val zip = buildZip(listOf(Item(name, ByteArray(8), 0, extra)))
        val file = File.createTempFile("offset2", ".zip").apply { writeBytes(zip) }

        val offsets = ZipOffsets.read(file)
        assertEquals(30L + nameLen + extra.size, offsets[0].dataOffset)
        assertEquals(0L, offsets[0].dataOffset % 4, "4B 对齐应当成立")
    }

    @Test
    fun `不是 zip 时返回空表而不是抛异常`() {
        val file = File.createTempFile("notzip", ".bin").apply { writeBytes(ByteArray(100) { 7 }) }
        assertTrue(ZipOffsets.read(file).isEmpty())
    }

    @Test
    fun `截断的文件也不会抛`() {
        val name = "AndroidManifest.xml"
        val zip = buildZip(listOf(Item(name, ByteArray(4), 0, ByteArray(0))))
        val file = File.createTempFile("trunc", ".zip").apply { writeBytes(zip.copyOf(zip.size / 2)) }
        assertTrue(ZipOffsets.read(file).isEmpty())
    }

    // ── 产物验证：拿真 APK 跑一遍（没有样本就跳过，不假装通过） ──

    private val sampleApk: File? =
        (System.getProperty("smithy.testApk") ?: System.getenv("SMITHY_TEST_APK"))
            ?.let(::File)
            ?.takeIf { it.isFile }

    @Test
    fun `真 APK 上五类校验都给出结论`() = runBlocking {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)
        val apk = sampleApk!!

        val report = ProductVerify.of(apk, expectedPackage = null, expectedLabel = null)

        assertEquals(64, report.sha256.length, "sha256 要真的是 64 位十六进制")
        assertEquals(apk.length(), report.sizeBytes)

        val labels = report.checks.map { it.label }
        listOf("清单", "资源表", "签名", "对齐").forEach {
            assertTrue(it in labels, "缺了「$it」这一类结论：$labels")
        }
    }

    @Test
    fun `包名对不上时判定为改动没落进产物`() = runBlocking {
        assumeTrue("未提供样本 APK（SMITHY_TEST_APK），跳过", sampleApk != null)

        val report = ProductVerify.of(sampleApk!!, expectedPackage = "com.definitely.not.the.real.one")

        val check = report.checks.single { it.label == "清单" }
        assertEquals(CheckLevel.BAD, check.level)
        assertTrue(!report.canInstall, "包名不一致时不该允许安装")
    }
}
