package dev.smithy.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavBoundsTest {

    private val sandbox = "/storage/emulated/0/Android/data/dev.smithy/files"

    // ── 有 root：哪儿都能去 ────────────────────────────────

    @Test
    fun `有 root 时系统目录也能去`() {
        for (p in listOf("/", "/system", "/data/adb/modules", sandbox, "/sdcard/Download")) {
            assertTrue(NavBounds.canReach(p, root = true, allFilesAccess = false, sandboxDir = sandbox), p)
        }
    }

    // ── 只有沙盒：严格限制在自己目录里 ──────────────────────

    @Test
    fun `只有沙盒时自己目录能进 共享存储进不去`() {
        assertTrue(NavBounds.canReach(sandbox, root = false, allFilesAccess = false, sandboxDir = sandbox))
        assertTrue(NavBounds.canReach("$sandbox/sub", root = false, allFilesAccess = false, sandboxDir = sandbox))

        // 这几种都不该放行 —— 放行了用户点进去只会看到空目录，
        // 而不知道该给权限还是该切 root
        assertFalse(NavBounds.canReach("/sdcard", root = false, allFilesAccess = false, sandboxDir = sandbox))
        assertFalse(NavBounds.canReach("/", root = false, allFilesAccess = false, sandboxDir = sandbox))
        assertFalse(NavBounds.canReach("/data/adb", root = false, allFilesAccess = false, sandboxDir = sandbox))
    }

    @Test
    fun `沙盒边界不吃前缀相似的目录`() {
        // `/…/files-old` 不是 `/…/files` 的子目录。用 startsWith 不带斜杠就会误判，
        // 而这类误判是把「隔壁目录」当成「自己的子目录」，属于越权
        val sibling = "$sandbox-old"
        assertFalse(NavBounds.canReach(sibling, root = false, allFilesAccess = false, sandboxDir = sandbox))
        assertFalse(NavBounds.canReach("$sandbox-old/x", root = false, allFilesAccess = false, sandboxDir = sandbox))
    }

    // ── 有「所有文件访问」：共享存储那一支 ──────────────────

    @Test
    fun `有文件访问时共享存储都能去`() {
        for (p in listOf(
            "/",
            "/sdcard",
            "/sdcard/Download",
            "/storage/emulated/0",
            "/storage/emulated/0/DCIM",
            "/mnt/media_rw/xxxx",
        )) {
            assertTrue(NavBounds.canReach(p, root = false, allFilesAccess = true, sandboxDir = sandbox), p)
        }
    }

    @Test
    fun `有文件访问也进不去系统目录`() {
        // 这些没有 root 就是读不了。放行会让界面显示空目录，
        // 而用户分不清是「空」还是「没权限」—— 拦住并给出切 root 的提示更有用
        for (p in listOf("/data", "/data/adb/modules", "/system", "/system/etc")) {
            assertFalse(NavBounds.canReach(p, root = false, allFilesAccess = true, sandboxDir = sandbox), p)
        }
    }

    @Test
    fun `末尾斜杠和重复斜杠不影响判断`() {
        assertTrue(NavBounds.canReach("/sdcard/", root = false, allFilesAccess = true, sandboxDir = sandbox))
        assertTrue(NavBounds.canReach("/", root = false, allFilesAccess = true, sandboxDir = sandbox))
        assertTrue(NavBounds.canReach("", root = false, allFilesAccess = true, sandboxDir = sandbox))
    }

    // ── 上一级 ────────────────────────────────────────────

    @Test
    fun `上一级算得对`() {
        assertEquals("/sdcard", NavBounds.parentOf("/sdcard/Download"))
        assertEquals("/a/b", NavBounds.parentOf("/a/b/c"))
        // `/sdcard` 的上一级是 `/`，不是空串 ——
        // 原来的写法在这里算出 ""，`openDir("")` 什么也不做，又是一次静默失败
        assertEquals("/", NavBounds.parentOf("/sdcard"))
        assertEquals("/", NavBounds.parentOf("/sdcard/"))
    }

    @Test
    fun `到顶了返回 null`() {
        assertNull(NavBounds.parentOf("/"))
        assertNull(NavBounds.parentOf(""))
        assertNull(NavBounds.parentOf("//"))
    }
}
