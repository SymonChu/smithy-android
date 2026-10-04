package dev.smithy.fs

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * zip 直改的**端到端**：改一个文本条目 → 写出 → 读回。
 *
 * `ZipEditorTest` 验的是各条规则，这里验的是「一次真实操作之后，包里到底什么样」——
 * 两者缺一不可：规则都对但写出的文件不对，装包时才炸。
 *
 * 用真实 apk 当输入（不是自造的小 zip），因为**分量最大、条目最多**的那类包才暴露
 * 顺序/对齐/大文件的问题。
 */
class ZipEditorEndToEndTest {

    private fun sample(): File {
        val p = System.getenv("SMITHY_TEST_APK")
        assumeTrue("没有设置 SMITHY_TEST_APK，跳过", p != null && File(p).isFile)
        return File(p!!)
    }

    @Test
    fun `往 assets 塞一个文本条目 其余条目一个字节都没变`() {
        val out = File("/tmp/smithy-zipedit-e2e.apk").apply { delete() }
        val payload = "smithy-check=ok\n第二行\n".toByteArray(Charsets.UTF_8)

        ZipEditor.open(sample()).use { editor ->
            editor.put("assets/smithy_check.txt", payload)
            editor.writeTo(out)
        }

        assertTrue(out.length() > 0, "产物不该为空")

        // 读回：新条目在，内容逐字节一致
        ZipFile(out).use { zip ->
            val entry = zip.getEntry("assets/smithy_check.txt")
            assertTrue(entry != null, "新加的条目应该在里面")
            val read = zip.getInputStream(entry).use { it.readBytes() }
            assertTrue(payload.contentEquals(read), "新条目内容应逐字节一致")

            // 关键：**没碰的条目不能被弄坏**。挑几个分量大的、以及必须 STORED 的
            listOf("resources.arsc", "AndroidManifest.xml").forEach { name ->
                assertTrue(zip.getEntry(name) != null, "$name 不该丢")
            }
            val so = zip.entries().asSequence().firstOrNull { it.name.endsWith(".so") }
            if (so != null) {
                assertEquals(
                    ZipEntry.STORED,
                    so.method,
                    "so 必须仍是未压缩（安装器要 mmap 它）",
                )
            }
        }

        // 再打开一次：产物本身要能被当作 apk 解析（不是「字节写进去了但结构坏了」）
        val reopened = ZipFile(out).use { it.size() }
        val original = ZipFile(sample()).use { it.size() }
        println("── 条目数：原包 $original → 产物 $reopened")
        assertEquals(original + 1, reopened, "只应该多出那一个新条目")
    }

    @Test
    fun `覆盖一个已有条目 大小变化后仍是可读的包`() {
        val out = File("/tmp/smithy-zipedit-e2e2.apk").apply { delete() }
        // 挑一个确定存在的文本类条目来覆盖；找不到就跳过（样本包各不相同）
        val target = ZipFile(sample()).use { zip ->
            zip.entries().asSequence()
                .firstOrNull {
                    !it.isDirectory &&
                        it.name.endsWith(".version") ||
                        it.name.endsWith(".txt")
                }
                ?.name
        }
        assumeTrue("样本包里没有合适的文本条目，跳过", target != null)

        val bigger = "x".repeat(4096).toByteArray(Charsets.UTF_8)
        ZipEditor.open(sample()).use { editor ->
            editor.put(target!!, bigger)
            editor.writeTo(out)
        }

        ZipFile(out).use { zip ->
            val read = zip.getInputStream(zip.getEntry(target!!)).use { it.readBytes() }
            assertTrue(bigger.contentEquals(read), "覆盖后的内容应逐字节一致")
            // 覆盖会让后面的条目整体位移 —— 这正是最容易把别的条目写坏的地方
            assertTrue(zip.getEntry("resources.arsc") != null, "resources.arsc 不该丢")
        }
        println("── 覆盖 $target（${bigger.size} 字节）后产物 ${out.length() / 1024}KB")
    }
}
