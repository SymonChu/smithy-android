package dev.smithy.toolkit

import dev.smithy.engine.ApkProject

/**
 * 当前打开的工作区。
 *
 * **为什么需要一个全局持有者**：AI 对话与工作台是两个界面，但它们要操作**同一个包**。
 * 「用户在工作台打开了包 → 让 AI 改它」这条路径要求两边看到同一个工程实例 ——
 * 各自 `open` 一次会得到两份互不相干的改动层，AI 改的东西用户在工作台的改动列表里
 * 看不到（反之亦然），而两边都会觉得自己是对的。
 *
 * 所以由工作台在打开/关闭时设置它，对话层只读。
 */
object WorkspaceHolder {

    @Volatile
    var current: ApkProject? = null
        private set

    /** 打开的文件名。对话里要能告诉用户「现在操作的是哪个包」。 */
    @Volatile
    var currentName: String? = null
        private set

    fun set(project: ApkProject?, name: String?) {
        current = project
        currentName = name
    }

    fun clear() = set(null, null)
}
