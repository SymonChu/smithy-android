package dev.smithy.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModulePropTest {

    private val sample = """
        id=my_module
        name=My Module
        version=v1.2.3
        versionCode=7
        author=someone
        description=It does things
    """.trimIndent()

    @Test
    fun `能解析官方样例那种 module_prop`() {
        val p = assertNotNull(ModuleProp.parse(sample))
        assertEquals("my_module", p.id)
        assertEquals("My Module", p.name)
        assertEquals("v1.2.3", p.version)
        assertEquals(7, p.versionCode)
        assertEquals("someone", p.author)
        assertEquals("It does things", p.description)
    }

    @Test
    fun `值里带等号不会把键切错`() {
        // 描述里常有等号，必须按第一个等号切
        val p = assertNotNull(
            ModuleProp.parse("id=x\nversionCode=1\ndescription=a=b=c\n"),
        )
        assertEquals("a=b=c", p.description)
    }

    @Test
    fun `不认识的自定义键会被保留`() {
        // 有的模块靠自定义键跟自己的脚本通信。我们重写 module.prop 时把它抹掉，
        // 那个模块就坏了 —— 而不认识不等于可以丢
        val p = assertNotNull(ModuleProp.parse("id=x\nversionCode=1\nmy_key=my_value\n"))
        assertEquals(listOf("my_key" to "my_value"), p.extras)
        assertTrue(p.toText().contains("my_key=my_value"))
    }

    @Test
    fun `缺 id 或 versionCode 不是整数时解析失败`() {
        // 这样的模块 Magisk 本来也不会加载，不该给它造一个看似正常的对象
        assertNull(ModuleProp.parse("name=no id\nversionCode=1\n"))
        assertNull(ModuleProp.parse("id=x\nversionCode=abc\n"))
        assertNull(ModuleProp.parse("id=x\nname=缺 versionCode\n"))
        assertNull(ModuleProp.parse(""))
    }

    @Test
    fun `注释和空行会被跳过`() {
        val p = assertNotNull(ModuleProp.parse("# 注释\n\nid=x\nversionCode=2\n\n# 尾注\n"))
        assertEquals("x", p.id)
        assertEquals(2, p.versionCode)
    }

    @Test
    fun `来回写一遍内容不丢`() {
        val p = assertNotNull(ModuleProp.parse(sample))
        val again = assertNotNull(ModuleProp.parse(p.toText()))
        assertEquals(p, again)
    }

    @Test
    fun `id 的非法写法都被挡住`() {
        assertNull(ModuleProp.validateId("my_module"))
        assertNull(ModuleProp.validateId("a.b-c_1"))

        assertNotNull(ModuleProp.validateId(""))
        assertNotNull(ModuleProp.validateId("."))
        assertNotNull(ModuleProp.validateId(".."))
        assertNotNull(ModuleProp.validateId("has space"))
        assertNotNull(ModuleProp.validateId("斜杠/"))
        assertNotNull(ModuleProp.validateId("中文"))
    }

    @Test
    fun `id 与目录名不一致要报出来`() {
        // Magisk 用目录名当模块标识。两者不一致时列表里显示的和实际装载的
        // 不是一回事，排查起来毫无线索
        val p = ModuleProp("foo", "Foo", "1", 1)
        assertNull(p.validate("foo"))

        val bad = assertNotNull(p.validate("bar"))
        assertTrue(bad.contains("目录名"), "原因要说清是目录名的问题：$bad")
    }

    @Test
    fun `缺 name 时用 id 顶上 且这不算错误`() {
        val p = assertNotNull(ModuleProp.parse("id=only_id\nversionCode=3\n"))
        assertEquals("only_id", p.name)
        // 解析时给了兜底值，所以这不构成「装不上或认不出」，不该拦住保存 ——
        // 用户可能正是在改别的字段，被一个无关的必填项挡住会很难受
        assertNull(p.validate())
    }
}

class ModuleLayoutTest {

    private val zygiskModule = listOf(
        "module.prop",
        "zygisk/arm64-v8a.so",
        "zygisk/armeabi-v7a.so",
        "service.sh",
        "post-fs-data.sh",
        "system.prop",
    )

    @Test
    fun `认得出模块与 Zygisk`() {
        val m = ModuleLayouts.inspect(zygiskModule, deviceAbis = listOf("arm64-v8a"))
        assertTrue(m.hasProp)
        assertTrue(m.isZygisk)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), m.zygiskAbis)
        assertEquals(listOf("service.sh", "post-fs-data.sh", "system.prop"), m.scripts)
        assertTrue(m.warnings.isEmpty(), "没问题的模块不该报警告：${m.warnings}")
    }

    @Test
    fun `lib 前缀的 so 不算有效 ABI 但会被警告`() {
        // Magisk 是按【文件名】匹配 ABI 的。写成 libfoo.so 的模块在设备上根本不会加载，
        // 所以它不能出现在「有效 ABI」里 —— 否则界面会显示「已支持 arm64-v8a」，那是假的
        val m = ModuleLayouts.inspect(
            listOf("module.prop", "zygisk/libfoo.so", "zygisk/arm64-v8a.so"),
            deviceAbis = listOf("arm64-v8a"),
        )
        assertEquals(listOf("arm64-v8a"), m.knownAbis)
        // 原始文件名列表里它还在 —— 那是「目录里实际有什么」
        assertEquals(listOf("libfoo", "arm64-v8a"), m.zygiskAbis)
        assertTrue(m.warnings.any { it.contains("libfoo") }, "该提醒这个文件不会被加载：${m.warnings}")
    }

    @Test
    fun `设备上没有对应 ABI 时要显式警告`() {
        // 这条最要紧：装上去「成功」了，但设备上不生效，而不会有任何报错
        val m = ModuleLayouts.inspect(
            listOf("module.prop", "zygisk/x86.so"),
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        val w = m.warnings.firstOrNull { it.contains("不会生效") }
        assertNotNull(w, "缺当前 ABI 必须显式说出来：${m.warnings}")
        assertTrue(w.contains("arm64-v8a"), "要说清设备支持什么：$w")
    }

    @Test
    fun `只有 module_prop 时提示可能套了一层目录`() {
        // 能装上但什么也不做，最常见的成因就是把文件放错了层级
        val m = ModuleLayouts.inspect(listOf("module.prop"))
        assertTrue(
            m.warnings.any { it.contains("套了一层目录") },
            "该提示层级问题：${m.warnings}",
        )
    }

    @Test
    fun `disable 与 remove 标记都会被说出来`() {
        val m = ModuleLayouts.inspect(
            listOf("module.prop", "service.sh", "disable", "remove"),
            deviceAbis = listOf("arm64-v8a"),
        )
        assertTrue(m.hasDisable)
        assertTrue(m.hasRemove)
        assertTrue(m.warnings.any { it.contains("停用") })
        assertTrue(m.warnings.any { it.contains("卸载") })
    }

    @Test
    fun `没有 module_prop 就不是模块`() {
        val m = ModuleLayouts.inspect(listOf("zygisk/arm64-v8a.so", "service.sh"))
        assertTrue(!m.hasProp)
    }

    @Test
    fun `zygisk 下再套一层目录不算 ABI`() {
        // zygisk/foo/arm64-v8a.so 不是 Magisk 认的布局，别把它算成支持 arm64
        val m = ModuleLayouts.inspect(
            listOf("module.prop", "zygisk/nested/arm64-v8a.so"),
            deviceAbis = listOf("arm64-v8a"),
        )
        assertTrue(m.zygiskAbis.isEmpty(), "嵌套目录里的 so 不该被当作 ABI：${m.zygiskAbis}")
    }

    @Test
    fun `条目名前面的斜杠不影响识别`() {
        val m = ModuleLayouts.inspect(listOf("/module.prop", "/zygisk/arm64-v8a.so"))
        assertTrue(m.hasProp)
        assertEquals(listOf("arm64-v8a"), m.zygiskAbis)
    }

    @Test
    fun `只有 system overlay 的模块不报层级问题`() {
        val m = ModuleLayouts.inspect(listOf("module.prop", "system/etc/hosts"))
        assertTrue(m.hasSystemOverlay)
        assertTrue(!m.warnings.any { it.contains("套了一层目录") })
    }
}
