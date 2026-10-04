package dev.smithy.fs

/**
 * 非 root 模式下「够不够得着某个路径」。
 *
 * ## 为什么要有这么一层
 *
 * 这个判断原先散在 ViewModel 里，用 `rootDir`（应用私有目录）当边界 ——
 * 那是「浏览只能待在自己目录里」那个设计的遗留。后来起始目录改成了 `/sdcard`，
 * 边界却没跟着改，于是：**点 ↑ 报「已经到最上层了」，点路径里的上一级报
 * 「这个位置需要 root」，两个都不动。** 用户看到的就是「返回没有用」。
 *
 * 抽出来的直接好处是能单测：单纯的边界判断，不该靠真机上点一遍才知道对不对。
 *
 * ## 三档
 *
 * | 情况 | 能走的地方 |
 * |---|---|
 * | 有 root | 整个文件系统 |
 * | 没有 root、但有「所有文件访问」 | 共享存储那一支（`/sdcard`、`/storage`、`/mnt`），以及 `/` 本身 |
 * | 只有沙盒 | 应用私有目录以内 |
 *
 * `/` 本身放行是刻意的：从 `/sdcard` 往上应该能到 `/` 看一眼（看得见多少是另一回事），
 * 而不是卡在原地让人以为坏了。
 */
object NavBounds {

    /**
     * 共享存储可能出现的几个挂载点。
     *
     * 三个都列上是因为同一条路径在不同设备/不同 API 上会以不同形式出现：
     * `/sdcard` 通常是指向 `/storage/emulated/0` 的软链，而 `/mnt` 下也常见一份。
     * 只认其中一个，用户从另一种形式进来的路径就会被判成不可达。
     */
    val SHARED_ROOTS = listOf("/sdcard", "/storage", "/mnt")

    /**
     * [path] 在这个权限组合下够不够得着。
     *
     * [root] 有 root；[allFilesAccess] 有「所有文件访问」；[sandboxDir] 是应用私有目录
     * （只有沙盒权限时唯一能走的地方）。
     */
    fun canReach(path: String, root: Boolean, allFilesAccess: Boolean, sandboxDir: String): Boolean {
        if (root) return true

        val p = path.trimEnd('/').ifEmpty { "/" }

        if (!allFilesAccess) {
            // 只有沙盒：严格限制在自己目录里。
            // 放行自己也算「够得着」，否则进都进不去
            val sandbox = sandboxDir.trimEnd('/')
            return p == sandbox || p.startsWith("$sandbox/")
        }

        if (p == "/") return true
        return SHARED_ROOTS.any { p == it || p.startsWith("$it/") }
    }

    /**
     * 从 [dir] 往上走一层。到顶了返回 null。
     *
     * 单独抽出来是因为原来的写法对 `/sdcard` 算出 `""` 而不是 `/`，
     * 结果「上一级」变成一个空路径，`openDir("")` 什么也不做 —— 又是一次静默失败。
     */
    fun parentOf(dir: String): String? {
        val d = dir.trimEnd('/')
        if (d.isEmpty() || d == "/") return null
        val idx = d.lastIndexOf('/')
        return if (idx <= 0) "/" else d.substring(0, idx)
    }
}
