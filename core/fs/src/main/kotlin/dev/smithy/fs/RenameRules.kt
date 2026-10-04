package dev.smithy.fs

/**
 * 批量改名的规则。
 *
 * 规则是**纯函数**（旧名 → 新名），刻意不碰文件系统 —— 批量改名出错的代价很高
 * （一次改几十个、且不可撤销），所以这套逻辑必须能用「干跑」反复验证，
 * 而不是拿真文件去试。
 *
 * 只作用于**文件名**，不碰目录部分；扩展名单独处理，免得「替换文本」把 `.png`
 * 里的字母也换掉（那种错误看起来毫无规律，极难排查）。
 */
data class RenameRules(
    /** 把名字（不含扩展名）里的这段文字换成那段。 */
    val find: String = "",
    val replaceWith: String = "",
    /** 加在名字前后的固定文字。 */
    val prefix: String = "",
    val suffix: String = "",
    /** 新扩展名（不含点）。空串 = 不改。 */
    val newExtension: String = "",
    /** 从该数字开始编号并附在末尾（形如 `_1`）。null = 不编号。 */
    val numberFrom: Int? = null,
) {

    /** 有没有实际会生效的规则。 */
    val isEffective: Boolean
        get() = (find.isNotEmpty() && find != replaceWith) ||
            prefix.isNotEmpty() || suffix.isNotEmpty() ||
            newExtension.isNotEmpty() || numberFrom != null

    /**
     * 算一个新名字。
     *
     * [index] 是它在**本次选择里的序号**（从 0 开始），编号规则用它。
     */
    fun apply(name: String, index: Int, isDir: Boolean): String {
        val dot = name.lastIndexOf('.')
        // 开头的点属于「隐藏文件的名字」而不是扩展名（`.gitignore` 的扩展名是空）
        val hasExtension = dot > 0 && !isDir
        var stem = if (hasExtension) name.substring(0, dot) else name
        var extension = if (hasExtension) name.substring(dot + 1) else ""

        if (find.isNotEmpty()) stem = stem.replace(find, replaceWith)
        stem = prefix + stem + suffix
        if (newExtension.isNotEmpty() && !isDir) extension = newExtension.removePrefix(".")
        if (numberFrom != null) stem = "${stem}_${numberFrom + index}"

        return if (extension.isEmpty()) stem else "$stem.$extension"
    }
}

/** 计划里的一项。 */
data class RenameItem(val from: String, val to: String, val isDir: Boolean) {
    val changed: Boolean get() = from != to
}

/**
 * 批量改名的计划（执行前给用户看的那个「改前 → 改后」列表）。
 *
 * [conflicts] 里是**改完之后会撞名**的目标名。必须挡住：在同一个目录里两个条目
 * 改到同一个名字，后一个会覆盖前一个 —— 而文件系统不一定报错，于是丢的文件
 * 要等用户下次找它的时候才发现。
 */
data class RenamePlan(
    val items: List<RenameItem>,
    val conflicts: Set<String>,
) {
    val changed: List<RenameItem> get() = items.filter { it.changed }
    val hasChanges: Boolean get() = changed.isNotEmpty()
    val canApply: Boolean get() = hasChanges && conflicts.isEmpty()
}

/**
 * 算出计划。纯函数 —— [existing] 是目录里现有的其它名字（用来判断撞名）。
 */
fun planRename(
    names: List<String>,
    rules: RenameRules,
    existing: Set<String>,
    isDir: (String) -> Boolean = { false },
): RenamePlan {
    val items = names.mapIndexed { i, name ->
        RenameItem(name, rules.apply(name, i, isDir(name)), isDir(name))
    }

    val conflicts = mutableSetOf<String>()
    // 改完之后的重名：只看「改动过的」之间，以及与目录里「没被选的」条目之间。
    // 被选中的条目自己占着的名字不算冲突（它会让位）
    val selectedNames = names.toSet()
    val afterNames = items.filter { it.changed }.map { it.to }
    afterNames.groupingBy { it }.eachCount().forEach { (name, count) ->
        if (count > 1) conflicts += name
    }
    afterNames.forEach { if (it in existing && it !in selectedNames) conflicts += it }

    return RenamePlan(items, conflicts)
}
