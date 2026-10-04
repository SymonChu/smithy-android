package dev.smithy.fs

/**
 * 一个模块 zip 的结构识别结果。**纯函数产物**，不碰文件系统。
 *
 * 认的是 Magisk 的目录约定（见官方样例工程）：
 *
 * ```
 * <module_id>/
 * ├── module.prop          纯文本：id / name / version / versionCode / author / description
 * └── zygisk/
 *     ├── arm64-v8a.so     ← Magisk 按【文件名】匹配 ABI，不是 lib<name>.so
 *     └── armeabi-v7a.so
 * ```
 *
 * 另有一些可选件：开机脚本、`system/` overlay、`system.prop`、`sepolicy.rule`、
 * `customize.sh`；目录里放一个空文件 `disable` 即停用、`remove` 即下次重启卸载。
 */
data class ModuleLayout(
    /** 有 `module.prop` 才算模块。 */
    val hasProp: Boolean,
    /**
     * `zygisk/` 下那些 `.so` 的**原始文件名**（去掉 `.so`）。
     *
     * 这是「目录里实际有什么」，**不等于「Magisk 认的 ABI」** —— 写成 `libfoo.so` 的
     * 文件也在里面，但它在设备上根本不会加载。要判断有效覆盖看 [knownAbis]。
     */
    val zygiskAbis: List<String>,
    /** 存在的开机 / 安装脚本与配置文件。 */
    val scripts: List<String>,
    /** 有 `system/` overlay。 */
    val hasSystemOverlay: Boolean,
    /** 有 `disable` 标记（已停用）。 */
    val hasDisable: Boolean,
    /** 有 `remove` 标记（下次重启卸载）。 */
    val hasRemove: Boolean,
    /** 需要让人看见的问题。空 = 没发现问题。 */
    val warnings: List<String>,
) {
    /** 是不是 Zygisk 模块（有 `zygisk/` 下的 `.so`）。 */
    val isZygisk: Boolean get() = zygiskAbis.isNotEmpty()

    /**
     * 真正会被加载的 ABI —— [zygiskAbis] 里 Magisk 认得的那部分。
     *
     * 界面上的「ABI 覆盖表」该用这个；[zygiskAbis] 里多出来的那些是放错了名字的文件，
     * 它们只该出现在警告里。
     */
    val knownAbis: List<String> get() = zygiskAbis.filter { it in ModuleLayouts.KNOWN_ABIS }
}

object ModuleLayouts {

    /** Magisk 认的 ABI 名字。也是 `zygisk/` 下 `.so` 的文件名（不含扩展名）。 */
    val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64")

    private val SCRIPT_NAMES = listOf(
        "service.sh",
        "post-fs-data.sh",
        "boot-completed.sh",
        "customize.sh",
        "system.prop",
        "sepolicy.rule",
    )

    /**
     * 从 zip 的条目名列表识别结构。
     *
     * [entryNames] 用 `/` 分层的**相对**路径（模块 zip 的条目就在根上，不套一层目录）；
     * [deviceAbis] 传当前设备的 ABI 列表（`Build.SUPPORTED_ABIS`），由调用方传进来 ——
     * 这样这一层保持纯逻辑，能脱离设备单测。
     */
    fun inspect(entryNames: List<String>, deviceAbis: List<String> = emptyList()): ModuleLayout {
        val names = entryNames.map { it.trimStart('/') }

        val hasProp = names.any { it == "module.prop" }
        val hasDisable = names.any { it == "disable" }
        val hasRemove = names.any { it == "remove" }
        val hasSystem = names.any { it.startsWith("system/") }

        val scripts = SCRIPT_NAMES.filter { n -> names.any { it == n } }

        // zygisk/ 下一层的 .so，文件名就是 ABI。**不认 lib<name>.so 那种写法** ——
        // Magisk 是按文件名匹配的，写成 libfoo.so 的模块根本不会在设备上加载
        val zygiskAbis = names
            .filter { it.startsWith("zygisk/") && it.endsWith(".so") }
            .map { it.removePrefix("zygisk/").removeSuffix(".so") }
            .filter { it.isNotEmpty() && !it.contains('/') }

        val warnings = ArrayList<String>()
        if (hasProp && zygiskAbis.isEmpty() && !hasSystem && scripts.isEmpty()) {
            // 只有 module.prop 别的什么都没有：能装上但什么也不做，
            // 通常是把文件放错了层级（套了一层目录）
            warnings += "这个模块除了 module.prop 什么都没有。常见原因是文件套了一层目录 —— " +
                "模块 zip 的条目应该直接在根上，里面再放 zygisk/、service.sh"
        }
        zygiskAbis.filter { it !in KNOWN_ABIS }.forEach {
            warnings += "zygisk/$it.so 的 ABI 名不在 Magisk 认识的列表里（$KNOWN_ABIS）—— 这个文件不会被加载"
        }
        val known = zygiskAbis.filter { it in KNOWN_ABIS }
        if (deviceAbis.isNotEmpty() && known.isNotEmpty() && known.none { it in deviceAbis }) {
            // 这条最要紧：装上去「成功」了，但设备上不生效，而不会有任何报错
            warnings += "zygisk 下没有这台设备用的 ABI（设备支持 ${deviceAbis.joinToString("/")}，" +
                "模块只有 ${known.joinToString("/")}）—— 模块能装上但不会生效"
        }
        if (hasDisable) {
            warnings += "这个模块当前是停用状态（有 disable 标记）"
        }
        if (hasRemove) {
            warnings += "这个模块标了 remove —— 下次重启会被卸载"
        }

        return ModuleLayout(
            hasProp = hasProp,
            zygiskAbis = zygiskAbis,
            scripts = scripts,
            hasSystemOverlay = hasSystem,
            hasDisable = hasDisable,
            hasRemove = hasRemove,
            warnings = warnings,
        )
    }
}
