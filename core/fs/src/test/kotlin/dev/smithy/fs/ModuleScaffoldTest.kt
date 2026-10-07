package dev.smithy.fs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 模块骨架。
 *
 * 这组用例盯的是**Magisk 会静默跳过的那些结构问题** —— 条目套了一层目录、id 里带了
 * 非法字符、zygisk 的 so 名字不对，现象都是「刷进去成功、什么都没发生」，
 * 在设备上极难查。生成出来的东西结构对不对，得在单元测试里钉死。
 *
 * 剩下只有「刷入 + 软重启 + 模块真的生效」要真机。
 */
class ModuleScaffoldTest {

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smithy-scaffold-${System.nanoTime()}").apply { mkdirs() }

    private fun spec(
        id: String = "example",
        flavour: ModuleSkeletonSpec.Flavour = ModuleSkeletonSpec.Flavour.SHELL,
    ) = ModuleSkeletonSpec(
        id = id,
        name = "示例模块",
        version = "v2.1",
        versionCode = 21,
        author = "smithy",
        description = "演示用",
        flavour = flavour,
    )

    @Test
    fun `shell 骨架是一个能被认出来的模块`() {
        val zip = File(tmpDir(), "example.zip")
        ModuleScaffold.write(spec(), zip)

        ModuleProject.open(zip).use { p ->
            val prop = assertNotNull(p.prop, "module.prop 得能解析出来 —— 解析不出来 Magisk 就当它不是模块")
            assertEquals("example", prop.id)
            assertEquals(21, prop.versionCode)
            assertEquals("示例模块", prop.name)

            val names = p.entryNames()
            assertTrue(ModuleProject.ModulePropFile in names)
            // 条目必须直接在根上：套一层 <id>/ 目录，Magisk 就找不到 module.prop 了
            assertFalse(names.any { it.startsWith("example/") }, "不该套一层目录：$names")

            val layout = p.layout
            assertTrue(layout.hasProp)
            assertFalse(layout.isZygisk, "shell 档不该有 zygisk")
            assertTrue(
                layout.scripts.containsAll(listOf("customize.sh", "service.sh", "post-fs-data.sh", "system.prop")),
                "脚本没被认出来：${layout.scripts}",
            )
            // 骨架不该自带「装上了但不生效」那类结构问题
            assertEquals(emptyList(), layout.warnings)
        }
    }

    @Test
    fun `zygisk 骨架带源码但不放假的 so`() {
        val zip = File(tmpDir(), "zygisk_demo.zip")
        ModuleScaffold.write(spec(id = "zygisk_demo", flavour = ModuleSkeletonSpec.Flavour.ZYGISK), zip)

        ModuleProject.open(zip).use { p ->
            val names = p.entryNames()
            assertTrue("jni/module.cpp" in names)
            assertTrue("jni/CMakeLists.txt" in names)
            assertTrue("jni/build.sh" in names)
            // 关键一条：**不放占位的 .so**。放了的话「装上了但模块没生效」会变得极难排查，
            // 宁可让它明确地停在「还没编出来」
            assertFalse(names.any { it.startsWith("zygisk/") }, "不能放占位 so：$names")
            // 源码里要写清目标进程这个可改的点，否则「定制」无从下手
            val src = assertNotNull(p.readText("jni/module.cpp"))
            assertTrue(src.contains("kTargetProcess"))
            assertTrue(src.contains("nice_name"), "要指出按进程名筛目标应用的位置")
        }
    }

    @Test
    fun `id 不合法或版本号为负时直接拒绝`() {
        listOf("", "  ", "有 空格", "a/b", "..", "带中文的id").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("id「$bad」应该被拒绝") {
                ModuleScaffold.entries(spec(id = bad))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ModuleScaffold.write(spec().copy(versionCode = -1), File(tmpDir(), "x.zip"))
        }
    }

    @Test
    fun `同样的输入生成同样的字节`() {
        val a = File(tmpDir(), "a.zip")
        val b = File(tmpDir(), "b.zip")
        ModuleScaffold.write(spec(), a)
        ModuleScaffold.write(spec(), b)
        assertTrue(
            a.readBytes().contentEquals(b.readBytes()),
            "生成要确定：否则「这个包和上次那个是不是一样」只能靠猜",
        )
    }

    @Test
    fun `名字留空时用 id 顶替`() {
        val entries = ModuleScaffold.entries(spec().copy(name = ""))
        val prop = assertNotNull(ModuleProp.parse(entries.getValue(ModuleProject.ModulePropFile)))
        assertEquals("example", prop.name)
    }
}
