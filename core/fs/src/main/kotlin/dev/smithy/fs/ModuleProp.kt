package dev.smithy.fs

/**
 * Magisk 模块的 `module.prop`。
 *
 * 格式是「一行一个 `key=value`」的纯文本，看着简单，但有几条硬约束，错了 Magisk
 * 会**静默跳过**这个模块 —— 模块列表里干脆不出现，没有任何提示。所以这里宁可判严：
 *
 * - **`id` 必须与模块所在目录名一致**。Magisk 用目录名当模块标识，两者不一致时
 *   列表里显示的和实际装载的不是一回事，排查起来毫无线索。
 * - **`id` 只允许字母、数字和 `._-`**，且不能是 `.` / `..`（那是路径本身）。
 * - **`versionCode` 必须是整数** —— Magisk 用它比大小决定要不要提示更新。
 */
data class ModuleProp(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String = "",
    val description: String = "",
    /**
     * 原始文本里其他不认识的键，**保序保留**。
     *
     * 不认识的键不等于可以丢：有的模块靠自定义键与自己的脚本通信，
     * 我们重写一遍 `module.prop` 时把它抹掉，那个模块就坏了。
     */
    val extras: List<Pair<String, String>> = emptyList(),
) {

    /** 按 Magisk 认的格式写回去。 */
    fun toText(): String = buildString {
        append("id=").append(id).append('\n')
        append("name=").append(name).append('\n')
        append("version=").append(version).append('\n')
        append("versionCode=").append(versionCode).append('\n')
        append("author=").append(author).append('\n')
        append("description=").append(description).append('\n')
        extras.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
    }

    /**
     * 校验「会让模块装不上或认不出」的问题，返回人话的原因（null = 没问题）。
     *
     * [dirName] 传模块所在目录名（null = 还不知道，跳过这条检查）。
     *
     * **刻意不拦 `name` / `version` 为空**：解析时 `name` 有 id 兜底，`version` 空着
     * Magisk 也就是在列表里显示空白。把它们当成错误会**挡住一次本来能成功的保存**，
     * 而用户改的可能是别的字段。
     */
    fun validate(dirName: String? = null): String? {
        validateId(id)?.let { return it }
        if (dirName != null && dirName != id) {
            return "id「$id」和目录名「$dirName」不一致。Magisk 用目录名当模块标识，" +
                "两者不一致时列表里显示的和实际装载的不是一回事，改 id 必须连目录一起改"
        }
        if (versionCode < 0) return "versionCode 不能是负数（Magisk 用它比大小）"
        return null
    }

    companion object {
        /** `id` 的合法字符。和 Magisk 的判定保持一致。 */
        private val ID_OK = Regex("^[A-Za-z0-9._-]+$")

        /**
         * 校验一个 id，返回原因或 null。
         *
         * 单独暴露是因为「新建模块」时还没有 [ModuleProp] 可校验。
         */
        fun validateId(id: String): String? = when {
            id.isBlank() -> "id 不能为空"
            id == "." || id == ".." -> "id 不能是「$id」（那是路径本身）"
            !ID_OK.matches(id) -> "id「$id」含非法字符。只允许字母、数字和 . _ -"
            else -> null
        }

        /**
         * 解析 `module.prop`。缺 `id` 或 `versionCode` 不是整数时返回 null ——
         * 那样的模块 Magisk 本来也不会加载，我们不该给它造出一个看似正常的对象。
         *
         * 值里允许有 `=`（描述里常有），所以按**第一个** `=` 切。
         */
        fun parse(text: String): ModuleProp? {
            val map = LinkedHashMap<String, String>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                // 后出现的同名键覆盖前面的（和常见 ini 解析一致）
                map[key] = value
            }

            val id = map["id"]?.takeIf { it.isNotBlank() } ?: return null
            val versionCode = map["versionCode"]?.toIntOrNull() ?: return null

            val known = setOf("id", "name", "version", "versionCode", "author", "description")
            return ModuleProp(
                id = id,
                name = map["name"].orEmpty().ifBlank { id },
                version = map["version"].orEmpty(),
                versionCode = versionCode,
                author = map["author"].orEmpty(),
                description = map["description"].orEmpty(),
                extras = map.filterKeys { it !in known }.toList(),
            )
        }
    }
}
