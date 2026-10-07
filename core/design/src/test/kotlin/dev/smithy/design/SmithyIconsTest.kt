package dev.smithy.design

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标集的**结构**测试。
 *
 * 这些图标由 `.devenv/gen-smithy-icons.py` 生成（形状取自 Material Symbols 官方
 * path 数据）。编译通过只说明语法对，**说明不了画出来是不是空的**：官方 24px 变体用的是
 * `viewBox="0 -960 960 960"`，图摆在 y 的负半轴，靠一层 `translationY(960)` 搬回
 * 正坐标系。这层平移一旦丢了（比如 `addGroup` 写成别的形式、或者被「顺手」删掉），
 * 编译和运行都不报错，只在屏幕上表现为「一片空白」—— 而这种退化最容易发生在
 * 重新生成图标之后。这条测试把它钉死。
 *
 * `ImageVector.root` 在 Kotlin 里是 internal（在 JVM 字节码上是 public `getRoot()`），
 * 所以这里用反射取 —— 不为了绕封装，而是因为「组里有路径、组带平移」这件事没有
 * 公开的读法，而它正是唯一值得断言的东西。
 */
class SmithyIconsTest {

    /** 反射取 root：见类注释。取不到就直接失败，不静默跳过。 */
    private fun rootOf(icon: ImageVector): VectorGroup {
        val getter = ImageVector::class.java.getMethod("getRoot")
        return getter.invoke(icon) as VectorGroup
    }

    @Test
    fun `每个图标都是一条带平移的组包着一条非空路径`() {
        assertTrue("图标集不该是空的", SmithyIcons.all.isNotEmpty())
        SmithyIcons.all.forEach { (name, icon) ->
            val root = rootOf(icon)
            assertEquals("$name：根组下应当只有一个节点", 1, root.size)
            val group = root[0] as? VectorGroup
                ?: error("$name：根下第一个节点不是组 —— 平移层丢了")
            assertEquals(
                "$name：平移量必须是 960（官方网格是 0 -960 960 960）",
                960f,
                group.translationY,
            )
            assertEquals("$name：组下应当只有一条路径", 1, group.size)
            val path = group[0] as? VectorPath ?: error("$name：组下第一个节点不是路径")
            assertTrue("$name：路径是空的", path.pathData.isNotEmpty())
        }
    }

    @Test
    fun `视口是 960 网格、默认尺寸 24dp`() {
        SmithyIcons.all.forEach { (name, icon) ->
            assertEquals("$name：视口宽", 960f, icon.viewportWidth)
            assertEquals("$name：视口高", 960f, icon.viewportHeight)
            assertEquals("$name：默认宽", 24f, icon.defaultWidth.value)
            assertEquals("$name：默认高", 24f, icon.defaultHeight.value)
        }
    }

    @Test
    fun `属性名不重复`() {
        val names = SmithyIcons.all.map { it.first }
        assertEquals("属性名有重复：$names", names.size, names.toSet().size)
    }
}
