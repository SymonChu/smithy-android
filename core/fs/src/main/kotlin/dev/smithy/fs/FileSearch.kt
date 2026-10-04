package dev.smithy.fs

/**
 * 递归搜索的遍历逻辑。
 *
 * **「列目录」由调用方注入**（[walk] 的 `listDir`）：普通模式走 `File` API、
 * root 模式走 shell，而遍历本身不该关心这件事。注入的另一个好处是能单测 ——
 * 拿一棵假目录树就能验上限、深度、取消、跳过隐藏项这些分支，不用真机试。
 *
 * ## 三条硬约束（都是「扫到一半出事」的护栏）
 *
 * 1. **有上限**：`/` 下面几十万条，不设上限就是一次 OOM 或者卡到用户杀进程。
 * 2. **有深度上限**：软链和绑定挂载能造出环，`/sdcard` 本身就是软链。
 * 3. **可取消**：用户输错一个字就想停，停下来必须是真的停（而不是等它扫完）。
 */
object FileSearch {

    /** 命中上限。够了就停，并告诉用户「被截断了」。 */
    const val CAP = 300

    /**
     * 目录深度上限。
     *
     * 不是防「太深」，是防**环**：软链指回祖先的目录树会让遍历永不结束。
     * 12 层足够覆盖正常的存储结构，又能保证环一定会被截断。
     */
    const val MAX_DEPTH = 12

    data class Result(
        val hits: List<FsItem>,
        /** 一共看了多少条 —— 界面要显示这个，否则扫描过程像卡死了。 */
        val scanned: Int,
        /** 因为到上限/到深度而停（不是用户取消）。 */
        val truncated: Boolean,
        val cancelled: Boolean,
    )

    /**
     * 从 [root] 开始找名字里含 [query] 的条目（忽略大小写）。
     *
     * [recursive] 为 false 时只看 [root] 这一层 —— 那是「筛选」的行为，保留它是因为
     * 「我大概知道东西就在这一层」时它快得多，而两种用法共用同一个输入框。
     *
     * 命中**只看名字**，不搜内容 —— 内容搜索是另一个量级的事，
     * 混在一起会让人以为「搜索」很慢。
     */
    fun walk(
        root: String,
        query: String,
        listDir: (String) -> List<FsItem>,
        recursive: Boolean,
        showHidden: Boolean,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        val hits = ArrayList<FsItem>()
        var scanned = 0
        var truncated = false
        var cancelled = false

        // 显式栈而不是递归：深度上限能挡住环，但递归还会把栈吃光
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += root to 0

        loop@ while (queue.isNotEmpty()) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            if (hits.size >= CAP) {
                truncated = true
                break
            }

            val (dir, depth) = queue.removeFirst()
            val entries = runCatching { listDir(dir) }.getOrDefault(emptyList())

            for (item in entries) {
                // 上限**每一条都查**：它只是一次整数比较，而「最多 300 条」是给用户的
                // 承诺 —— 加上节流就变成「说 300、实际 320」
                if (hits.size >= CAP) {
                    truncated = true
                    break@loop
                }
                // 取消则每 64 条查一次：回调可能有开销，而 64 条的延迟用户感觉不到。
                // **不能只在换目录时查** —— 一个目录里可能有几十万条，
                // 那样按「停止」要等这一层扫完才生效，按钮看起来就是坏的
                if (scanned % 64 == 0 && isCancelled()) {
                    cancelled = true
                    break@loop
                }
                scanned++
                // 隐藏项：不显示的一般也不该被搜出来（.thumbnails 那种会把结果淹掉）
                if (!showHidden && item.name.startsWith(".")) continue
                if (item.name.contains(query, ignoreCase = true)) hits += item
                if (recursive && item.dir && depth + 1 < MAX_DEPTH) queue += item.path to (depth + 1)
            }
            onProgress(scanned)
        }

        return Result(hits = hits, scanned = scanned, truncated = truncated, cancelled = cancelled)
    }
}
