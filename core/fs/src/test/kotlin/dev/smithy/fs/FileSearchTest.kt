package dev.smithy.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSearchTest {

    private fun file(name: String, dir: String) = FsItem(name, "$dir/$name", false, 0, 0)
    private fun folder(name: String, dir: String) = FsItem(name, "$dir/$name", true, 0, 0)

    /** 用一张假目录表当「列目录」。 */
    private fun listing(vararg pairs: Pair<String, List<FsItem>>): (String) -> List<FsItem> {
        val map = pairs.toMap()
        return { path -> map[path].orEmpty() }
    }

    // ── 递归 vs 只一层 ──────────────────────────────────────

    @Test
    fun `不递归时只看一层`() {
        val list = listing(
            "/r" to listOf(file("target.txt", "/r"), folder("sub", "/r")),
            "/r/sub" to listOf(file("target-deep.txt", "/r/sub")),
        )
        val r = FileSearch.walk("/r", "target", list, recursive = false, showHidden = false)
        assertEquals(listOf("/r/target.txt"), r.hits.map { it.path })
    }

    @Test
    fun `递归时进子目录`() {
        val list = listing(
            "/r" to listOf(folder("sub", "/r")),
            "/r/sub" to listOf(file("target-deep.txt", "/r/sub")),
        )
        val r = FileSearch.walk("/r", "target", list, recursive = true, showHidden = false)
        assertEquals(listOf("/r/sub/target-deep.txt"), r.hits.map { it.path })
    }

    @Test
    fun `忽略大小写`() {
        val list = listing("/r" to listOf(file("TARGET.TXT", "/r")))
        val r = FileSearch.walk("/r", "target", list, recursive = false, showHidden = false)
        assertEquals(1, r.hits.size)
    }

    @Test
    fun `按名字匹配 不搜内容`() {
        // 名字不含 query 的，即使在目录里也不该命中 ——
        // 内容搜索是另一个量级的事，混进来会让人以为「搜索」很慢
        val list = listing("/r" to listOf(file("note.txt", "/r")))
        val r = FileSearch.walk("/r", "里面写的东西", list, recursive = false, showHidden = false)
        assertTrue(r.hits.isEmpty())
    }

    // ── 隐藏项 ─────────────────────────────────────────────

    @Test
    fun `不显示隐藏项时 隐藏项既不出现在结果里 也不会被递归进去`() {
        val list = listing(
            "/r" to listOf(folder(".cache", "/r"), file(".hidden-target", "/r")),
            "/r/.cache" to listOf(file("target-inside.txt", "/r/.cache")),
        )
        val r = FileSearch.walk("/r", "target", list, recursive = true, showHidden = false)
        assertTrue(r.hits.isEmpty(), "隐藏项不该命中，也不该被钻进去：${r.hits.map { it.path }}")
    }

    @Test
    fun `显示隐藏项时能搜到`() {
        val list = listing(
            "/r" to listOf(file(".hidden-target", "/r")),
        )
        val r = FileSearch.walk("/r", "target", list, recursive = true, showHidden = true)
        assertEquals(listOf("/r/.hidden-target"), r.hits.map { it.path })
    }

    // ── 三条护栏 ───────────────────────────────────────────

    @Test
    fun `命中数到上限就停 并标记截断`() {
        // 不设上限的话，`/` 下面几十万条就是一次 OOM 或卡到用户杀进程
        val many = (1..FileSearch.CAP + 120).map { file("target-$it.txt", "/r") }
        val r = FileSearch.walk("/r", "target", listing("/r" to many), recursive = false, showHidden = false)
        assertEquals(FileSearch.CAP, r.hits.size)
        assertTrue(r.truncated)
    }

    @Test
    fun `层数超过上限就停 不会顺着环一直跑`() {
        // 软链指回祖先的目录树会造出环，而 /sdcard 本身就是软链
        val chain = (0 until FileSearch.MAX_DEPTH + 8).associate { i ->
            "/r/d$i" to listOf(folder("d${i + 1}", "/r/d$i"), file("target-$i.txt", "/r/d$i"))
        } + ("/r" to listOf(folder("d0", "/r")))
        val r = FileSearch.walk("/r", "target", listing(*chain.toList().toTypedArray()),
            recursive = true, showHidden = false)
        assertTrue(r.scanned > 0, "得真的扫过东西，否则这条测试什么也没验")
        assertTrue(r.hits.size < FileSearch.MAX_DEPTH + 8, "不该一路挖到底：${r.hits.size}")
    }

    @Test
    fun `自指的目录不会死循环`() {
        // /r 的列表里有 /r 自己
        val r = FileSearch.walk(
            "/r", "anything",
            { p -> if (p == "/r") listOf(folder("r", "/")) else emptyList() },
            recursive = true, showHidden = false,
        )
        assertTrue(r.scanned < 100, "自指目录必须被深度上限截断，实际看了 ${r.scanned} 条")
    }

    @Test
    fun `取消之后立刻停`() {
        val many = (1..50).map { file("target-$it.txt", "/r") }
        val r = FileSearch.walk(
            "/r", "target", listing("/r" to many),
            recursive = false, showHidden = false,
            isCancelled = { true },
        )
        assertTrue(r.cancelled)
        assertFalse(r.truncated, "取消和截断是两回事：取消不该被说成「到了上限」")
    }

    @Test
    fun `大目录扫到一半也能停 不用等这一层扫完`() {
        // 用户按「停止」必须真的停。只在换目录时检查的话，一个几万条的目录
        // 会让按钮像坏的 —— 得等那一层扫完才生效
        val many = (1..20_000).map { file("f$it", "/r") }
        var checks = 0
        val r = FileSearch.walk(
            "/r", "target", listing("/r" to many),
            recursive = false, showHidden = false,
            isCancelled = { checks++; checks > 2 },
        )
        assertTrue(r.cancelled)
        assertTrue(r.scanned < 20_000, "不该把整层扫完才停：看了 ${r.scanned} 条")
    }

    @Test
    fun `某个目录读不了 不影响其他目录`() {
        // 权限不足会抛异常。整次遍历因为一个子目录失败而中断，用户会以为「搜不到」
        val r = FileSearch.walk(
            "/r", "target",
            { p ->
                when (p) {
                    "/r" -> listOf(folder("denied", "/r"), file("target.txt", "/r"))
                    else -> throw SecurityException("Permission denied")
                }
            },
            recursive = true, showHidden = false,
        )
        assertEquals(listOf("/r/target.txt"), r.hits.map { it.path })
    }

    // ── 进度 ───────────────────────────────────────────────

    @Test
    fun `回调会报出已看条目数`() {
        // 扫描过程中得有个东西在动，否则长扫描看起来像卡死
        val many = (1..30).map { file("f$it", "/r") }
        val seen = mutableListOf<Int>()
        FileSearch.walk("/r", "target", listing("/r" to many),
            recursive = false, showHidden = false, onProgress = { seen += it })
        assertTrue(seen.isNotEmpty())
        assertEquals(30, seen.last())
    }
}
