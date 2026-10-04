package dev.smithy.fs

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模块工程的来回一趟。
 *
 * 这组用例就是 M6 验收里**能自动化的那半段**：解出模块 → 改 `module.prop` 的版本与描述 →
 * 改 `service.sh` 一行 → 重打包 → 再读回确认。剩下只有「刷入 + 软重启」要真机。
 */
class ModuleProjectTest {

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-mod-${System.nanoTime()}").apply { mkdirs() }

    private fun put(z: ZipOutputStream, name: String, text: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(text.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    /** 造一个和官方样例同构的模块 zip。 */
    private fun makeModule(dir: File, propText: String? = null): File {
        val zip = File(dir, "my_module.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            propText?.let {
                put(z, ModuleProject.ModulePropFile, it)
            }
            put(z, "service.sh", "#!/system/bin/sh\n# 老的一行\necho hello\n")
            put(z, "zygisk/arm64-v8a.so", "\u007fELF-fake-for-structure")
            put(z, "system/etc/hosts", "127.0.0.1 localhost\n")
        }
        return zip
    }

    private val goodProp = """
        id=my_module
        name=My Module
        version=v1.0
        versionCode=1
        author=me
        description=old text
    """.trimIndent()

    @Test
    fun `打开一个模块能认出结构与元数据`() {
        val dir = tmpDir()
        ModuleProject.open(makeModule(dir, goodProp)).use { p ->
            val prop = assertNotNull(p.prop, "module.prop 该解析出来")
            assertEquals("my_module", prop.id)
            assertEquals("v1.0", prop.version)

            val layout = p.layout
            assertTrue(layout.hasProp)
            assertTrue(layout.isZygisk)
            assertEquals(listOf("arm64-v8a"), layout.knownAbis)
            assertTrue(layout.scripts.contains("service.sh"))
            assertTrue(layout.hasSystemOverlay)
        }
    }

    @Test
    fun `改版本与描述 重打包后读回来是新的`() {
        val dir = tmpDir()
        val src = makeModule(dir, goodProp)
        val out = File(dir, "out.zip")

        ModuleProject.open(src).use { p ->
            val old = assertNotNull(p.prop)
            p.updateProp(old.copy(version = "v2.0", versionCode = 2, description = "new text"))
            assertEquals(1, p.changeCount())
            p.packageTo(out)
        }

        ModuleProject.open(out).use { q ->
            val prop = assertNotNull(q.prop)
            assertEquals("v2.0", prop.version)
            assertEquals(2, prop.versionCode)
            assertEquals("new text", prop.description)
            // author 没动过，必须还在
            assertEquals("me", prop.author)
            // id 没动过
            assertEquals("my_module", prop.id)
        }
    }

    @Test
    fun `改脚本一行 重打包后其它行没被动`() {
        val dir = tmpDir()
        val src = makeModule(dir, goodProp)
        val out = File(dir, "out.zip")

        ModuleProject.open(src).use { p ->
            val sh = assertNotNull(p.readText("service.sh"))
            p.writeText("service.sh", sh.replace("# 老的一行", "# 新的一行"))
            p.packageTo(out)
        }

        ModuleProject.open(out).use { q ->
            val sh = assertNotNull(q.readText("service.sh"))
            assertTrue(sh.contains("# 新的一行"), "改的那行该生效：$sh")
            assertTrue(!sh.contains("老的一行"))
            // 首行 shebang 与另一半内容都还在
            assertTrue(sh.startsWith("#!/system/bin/sh"))
            assertTrue(sh.contains("echo hello"))
        }
    }

    @Test
    fun `重打包之后非文本条目原样还在`() {
        val dir = tmpDir()
        val src = makeModule(dir, goodProp)
        val out = File(dir, "out.zip")

        ModuleProject.open(src).use { p ->
            p.writeText("service.sh", "#!/system/bin/sh\necho changed\n")
            p.packageTo(out)
        }

        ModuleProject.open(out).use { q ->
            val names = q.entryNames()
            assertTrue(names.contains("zygisk/arm64-v8a.so"), "so 不该丢：$names")
            assertTrue(names.contains("system/etc/hosts"), "overlay 文件不该丢：$names")
            assertEquals(
                "\u007fELF-fake-for-structure",
                String(assertNotNull(q.readBytes("zygisk/arm64-v8a.so")), Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `id 与已安装目录名不一致时拒绝改 prop`() {
        val dir = tmpDir()
        ModuleProject.open(makeModule(dir, goodProp)).use { p ->
            val prop = assertNotNull(p.prop)
            val e = assertFailsWith<IllegalArgumentException> {
                p.updateProp(prop.copy(id = "renamed"), dirNameOrNull = "my_module")
            }
            assertTrue(e.message!!.contains("目录名"), "原因要说清是目录名：${e.message}")
        }
    }

    @Test
    fun `从 zip 打开时不拿文件名去猜目录名`() {
        // 打包出来常常叫 my_module-v1.2.zip。照文件名判会误报一堆「id 不一致」，
        // 所以从 zip 打开时传 null，不做这条检查
        val dir = tmpDir()
        ModuleProject.open(makeModule(dir, goodProp)).use { p ->
            val prop = assertNotNull(p.prop)
            p.updateProp(prop.copy(version = "v9"), dirNameOrNull = null)
            assertEquals(1, p.changeCount())
        }
    }

    @Test
    fun `没有 module_prop 的包拒绝打包成模块`() {
        // 那样的 zip 刷进去 Magisk 会静默忽略，而用户会以为刷成功了
        val dir = tmpDir()
        ModuleProject.open(makeModule(dir, propText = null)).use { p ->
            assertNull(p.prop)
            val e = assertFailsWith<IllegalStateException> { p.packageTo(File(dir, "out.zip")) }
            assertTrue(e.message!!.contains("module.prop"), "要说清缺什么：${e.message}")
        }
    }

    @Test
    fun `撤销能回到原样`() {
        val dir = tmpDir()
        ModuleProject.open(makeModule(dir, goodProp)).use { p ->
            p.writeText("service.sh", "changed")
            assertEquals(1, p.changeCount())
            p.revert("service.sh")
            assertEquals(0, p.changeCount())
            assertTrue(assertNotNull(p.readText("service.sh")).contains("echo hello"))
        }
    }

    @Test
    fun `设备 ABI 对不上时给出警告`() {
        // 这条是「装上了但不生效、且不报错」的护栏。inspect 接受设备 ABI 列表，
        // 工程层拿到的是空列表（还没查设备），所以这里直接验纯函数那层
        val layout = ModuleLayouts.inspect(
            listOf("module.prop", "zygisk/x86.so"),
            deviceAbis = listOf("arm64-v8a"),
        )
        assertTrue(layout.warnings.any { it.contains("不会生效") })
    }
}
