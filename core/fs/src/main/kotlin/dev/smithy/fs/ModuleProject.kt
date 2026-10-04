package dev.smithy.fs

import java.io.File

/**
 * 打开的 Magisk 模块工程。
 *
 * **为什么在 `core:fs` 而不是 `core:engine`**：模块就是一个 zip，改的是纯文本
 * （`module.prop` 和几个脚本），没有二进制资源表要解析 —— 不需要 ARSCLib 那一套。
 * 放在这一层单测跑得快，也不会被二进制资源的复杂度牵连。
 *
 * **归档能力复用 [ZipEditor]**，不另造一套 `archive.*`：模块 zip 和 APK 共用一个实现，
 * 以后修 zip 的对齐 / 存储方式时两边一起受益，不会出现「APK 修好了、模块还是坏的」。
 *
 * 一个模块 zip 的条目**就在根上**（不套一层 `<id>/` 目录）：
 *
 * ```
 * module.prop
 * zygisk/arm64-v8a.so
 * service.sh
 * ```
 */
class ModuleProject private constructor(
    val source: File,
    private val editor: ZipEditor,
) : AutoCloseable {

    /** 条目名（`/` 分层，相对根）。 */
    fun entryNames(): List<String> = editor.entries().map { it.path }

    /** 解析出来的 `module.prop`；null = 这个 zip 不是模块，或者 prop 不合法。 */
    val prop: ModuleProp? by lazy {
        readText(ModulePropFile)?.let { ModuleProp.parse(it) }
    }

    /** 结构识别结果。 */
    val layout: ModuleLayout by lazy {
        ModuleLayouts.inspect(entryNames(), deviceAbis = emptyList())
    }

    /** 按文本读一个条目。不存在或不是文本时返回 null。 */
    fun readText(path: String): String? = runCatching {
        editor.read(path).toString(Charsets.UTF_8)
    }.getOrNull()

    /** 按字节读一个条目（查内容用）。不存在时返回 null。 */
    fun readBytes(path: String): ByteArray? = runCatching { editor.read(path) }.getOrNull()

    /**
     * 写一个文本条目。
     *
     * 只用于模块自己的脚本与配置 —— 它们是纯文本。二进制条目走 [writeBytes]，
     * 分开是为了让调用方在类型上就知道自己在写什么。
     */
    fun writeText(path: String, text: String) {
        editor.put(path, text.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(path: String, bytes: ByteArray) {
        editor.put(path, bytes)
    }

    fun deleteEntry(path: String) {
        editor.delete(path)
    }

    /**
     * 改 `module.prop`。
     *
     * [dirNameOrNull] 传模块**已安装**时的目录名（比如从 `/data/adb/modules/<id>` 打开的
     * 时候就是那个 `<id>`）。从 zip 打开时传 null —— **不要拿 zip 文件名去猜**：
     * 打包出来常常叫 `my_module-v1.2.zip`，照文件名判会误报一堆「id 不一致」。
     *
     * 校验不通过时抛 [IllegalArgumentException]，消息可以直接给用户看。
     */
    fun updateProp(newProp: ModuleProp, dirNameOrNull: String? = null) {
        newProp.validate(dirNameOrNull)?.let { throw IllegalArgumentException(it) }
        writeText(ModulePropFile, newProp.toText())
    }

    /** 当前未落盘的改动数。0 = 没改过。 */
    fun changeCount(): Int = editor.changeCount()

    /** 撤销某个条目的改动。 */
    fun revert(path: String) = editor.revert(path)

    /** 撤销全部改动。 */
    fun revertAll() = editor.revertAll()

    /**
     * 按模块规范重打包成可刷 zip。
     *
     * 缺 `module.prop` 时直接拒绝 —— 那样的 zip 刷进去 Magisk 会静默忽略，
     * 而用户会以为刷成功了。
     */
    fun packageTo(target: File): ZipWriteReport {
        if (entryNames().none { it == ModulePropFile }) {
            throw IllegalStateException(
                "这个包里没有 module.prop，刷进去 Magisk 不会认。模块 zip 的 entry 要在根上，" +
                    "不能套一层目录",
            )
        }
        return editor.writeTo(target)
    }

    override fun close() = editor.close()

    companion object {
        const val ModulePropFile = "module.prop"

        /** 打开一个模块 zip。 */
        fun open(zip: File): ModuleProject =
            ModuleProject(zip, ZipEditor.open(zip))
    }
}
