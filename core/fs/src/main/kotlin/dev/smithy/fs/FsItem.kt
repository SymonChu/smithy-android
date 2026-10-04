package dev.smithy.fs

/**
 * 文件列表里的一项。
 *
 * 放在 `core:fs` 而不是 `feature/files`：它就是个文件系统的值对象，谁都可能用到
 * （搜索遍历、压缩包浏览、模块工程），不该和某一个界面的 ViewModel 绑在一起。
 *
 * 刻意**不含**任何界面用的派生属性（比如「能不能当压缩包打开」）—— 那种判断
 * 依赖各模块自己的扩展名表，放在这里会把 core 层拽上一堆业务常量。
 */
data class FsItem(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long,
    val modified: Long,
)
