package dev.smithy.fs

import org.junit.Assume
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 对**真实文件**的验收：把 `SMITHY_TEST_MODULE` 指向一个真模块 zip 时，
 * 确认工程能打开、能认出结构、能改、能重打包。
 *
 * 和 [ModuleProjectTest] 的分工：那边用代码现场造 zip（验逻辑分支），
 * 这边读盘上的真文件（验「我们产出的/用户给的东西真的能被读」）。
 * 两者都要有 —— 只造不读的话，格式上细微的差别（压缩方式、额外字段、
 * 目录前缀）永远不会暴露。
 *
 * 没设环境变量就跳过（和 APK 那条端到端测试一个套路）。
 */
class ModuleFixtureTest {

    private fun fixture(): File {
        val f = System.getenv("SMITHY_TEST_MODULE")?.let { File(it) }?.takeIf { it.isFile }
        // 用 assume 而不是直接 return：直接 return 会算「通过」，
        // 于是用例总数看起来覆盖了它其实没跑的东西 —— 那是自欺
        Assume.assumeTrue("需要 SMITHY_TEST_MODULE 指向一个真模块 zip", f != null)
        return f!!
    }

    @Test
    fun `真模块 zip 能打开 结构认得出来`() {
        val zip = fixture()
        ModuleProject.open(zip).use { p ->
            val prop = assertNotNull(p.prop, "读不出 module.prop：${zip.name}")
            assertTrue(prop.id.isNotBlank(), "id 不该为空")
            assertTrue(prop.version.isNotBlank(), "version 不该为空")

            val layout = p.layout
            assertTrue(layout.hasProp, "有 module.prop 就该认出是模块")
            assertTrue(
                layout.warnings.none { it.contains("不在 zip 根") },
                "module.prop 必须在 zip 根上，否则 Magisk 会静默跳过：${layout.warnings}",
            )
            assertTrue(p.entryNames().contains("module.prop"), "条目名该是根上的 module.prop")
            println("  · 模块 ${prop.id} / ${prop.name} / ${prop.version}(${prop.versionCode})")
            println("  · 条目 ${p.entryNames()}")
        }
    }

    @Test
    fun `真模块 zip 改了版本 能重打包 再读出来是新值`() {
        val zip = fixture()
        val out = File.createTempFile("smithy-mod-fixture", ".zip")
        out.deleteOnExit()

        val before = ModuleProject.open(zip).use { assertNotNull(it.prop) }
        ModuleProject.open(zip).use { p ->
            val bumped = before.copy(
                version = "v9.9-test",
                versionCode = before.versionCode + 1,
                description = "改过了：${before.description}",
            )
            assertNotNull(p.updateProp(bumped), "改 module.prop 该成功")
            p.packageTo(out)
        }

        ModuleProject.open(out).use { p ->
            val after = assertNotNull(p.prop)
            assertEquals("v9.9-test", after.version)
            assertEquals(before.versionCode + 1, after.versionCode)
            assertEquals(before.id, after.id, "id 不该被动过")
            assertTrue(after.description.startsWith("改过了："), "描述该被改掉")
            assertTrue(
                p.entryNames().contains("customize.sh"),
                "其他条目不该在重打包时丢掉：${p.entryNames()}",
            )
        }
    }
}
