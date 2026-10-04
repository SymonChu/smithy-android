package dev.smithy.engine.internal

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.model.ResourceEntry as ArscResourceEntry
import com.reandroid.arsc.value.ResConfig
import dev.smithy.engine.ManifestField
import dev.smithy.engine.StringReplacement
import dev.smithy.engine.ResourceEntry as SmithyResourceEntry
import java.io.File
import java.util.zip.ZipFile

/**
 * 资源层：读 / 改资源表与清单。
 *
 * ── 改动怎么落地 ──
 *
 * ARSCLib 的 `ApkModule` 内部维护一张「改过的块」的表，改完之后由它统一序列化。
 * 我们不直接使用它的 `writeApk` 产物当作最终包（那会把整包重写一遍、丢掉增量回编的优势），
 * 而是让它写出一个临时包，**只把 `resources.arsc` 与 `AndroidManifest.xml` 两个条目抽出来**
 * 交给工作区覆盖层 —— 其余条目照旧从原包搬运（见 [ZipRebuilder]）。
 *
 * 这样做的代价是改资源时要多写一次临时整包（26MB 的包约一两秒），
 * 换来的是 **arsc / 二进制 XML 的序列化完全交给 ARSCLib** ——
 * 这两块的格式细节（字符串池排序、配置变体、对齐）自己写几乎必然出错。
 */
internal object ArscBridge {

    /** 改资源会波及的条目。改完之后要逐一看有没有真的变化。 */
    val ENTRIES = setOf("resources.arsc", "AndroidManifest.xml")

    // ── 读 ──────────────────────────────────────────────────

    /**
     * 列资源。[type] 如 "string" / "drawable"；[filter] 对资源名与值做子串匹配。
     *
     * [limit] 是必须的：一个正常 App 有上万条资源，全量倒给 UI 既慢又没用。
     */
    fun list(
        module: ApkModule,
        type: String?,
        filter: String?,
        limit: Int,
    ): List<SmithyResourceEntry> {
        val table = runCatching { module.getTableBlock() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SmithyResourceEntry>()

        for (pkg in table.getPackages()) {
            val it = pkg.getResources()
            while (it.hasNext()) {
                val res = it.next()
                val t = res.getType()
                if (type != null && t != type) continue

                val name = res.getName() ?: continue
                val default = res.get() ?: res.any()
                val value = default?.getValueAsString()

                if (filter != null && !name.contains(filter) && value?.contains(filter) != true) continue

                out += SmithyResourceEntry(
                    // 包内引用形式，与 XML 里写的一致
                    resName = "@$t/$name",
                    type = t,
                    value = value,
                    isComplex = runCatching { default?.isComplex == true }.getOrDefault(false),
                )
                // 资源多到一定程度就没必要再列了 —— 让用户先缩小条件
                if (out.size >= limit) return out
            }
        }
        return out
    }

    // ── 改资源 ───────────────────────────────────────────────

    /** 改一条字符串资源。[resName] 接受 `@string/app_name` 或 `string/app_name`。 */
    fun setString(module: ApkModule, resName: String, value: String): Boolean {
        val (type, name) = parseResName(resName) ?: return false
        val table = runCatching { module.getTableBlock() }.getOrNull() ?: return false

        for (pkg in table.getPackages()) {
            val res = pkg.getResource(type, name) ?: continue
            // 默认配置（无 -en / -xxhdpi 等后缀）那一份，改它才是「改默认文案」
            val entry = res.get() ?: res.any() ?: continue
            entry.setValueAsString(value)
            return true
        }
        return false
    }

    /**
     * 批量替换字符串资源的值。
     *
     * **遍历所有配置变体**，而不是只改默认那份 —— 否则会出现「默认语言改了、
     * 英文/日文那份还是旧的」这种半吊子结果，用户很难发现。
     * 复杂条目（样式、数组、plurals）跳过：它们的值不是单个字符串，硬替换会改坏结构。
     *
     * 匹配策略：**按列表顺序，一条值只应用第一个命中的规则**，不做连锁替换。
     */
    fun replaceStrings(module: ApkModule, pairs: List<StringReplacement>): Int {
        if (pairs.isEmpty()) return 0
        val table = runCatching { module.getTableBlock() }.getOrNull() ?: return 0
        var hits = 0

        for (pkg in table.getPackages()) {
            val it = pkg.getResources()
            while (it.hasNext()) {
                for (entry in it.next()) {
                    if (runCatching { entry.isComplex }.getOrDefault(true)) continue
                    val old = runCatching { entry.getValueAsString() }.getOrNull() ?: continue

                    var new = old
                    for (p in pairs) {
                        if (new.contains(p.from)) {
                            new = new.replace(p.from, p.to)
                            break
                        }
                    }
                    if (new == old) continue

                    runCatching {
                        entry.setValueAsString(new)
                        hits++
                    }
                }
            }
        }
        return hits
    }

    /** 正则版：只有一条规则，所以不必走批量（正则没法批次合并成一次扫描的收益不值得）。 */
    fun replaceStringsRegex(module: ApkModule, regex: Regex, to: String): Int {
        val table = runCatching { module.getTableBlock() }.getOrNull() ?: return 0
        var hits = 0

        for (pkg in table.getPackages()) {
            val it = pkg.getResources()
            while (it.hasNext()) {
                for (entry in it.next()) {
                    if (runCatching { entry.isComplex }.getOrDefault(true)) continue
                    val old = runCatching { entry.getValueAsString() }.getOrNull() ?: continue
                    val new = regex.replace(old, to)
                    if (new == old) continue
                    runCatching {
                        entry.setValueAsString(new)
                        hits++
                    }
                }
            }
        }
        return hits
    }

    // ── 改清单 ───────────────────────────────────────────────

    fun setManifestField(module: ApkModule, field: ManifestField, value: String): Boolean {
        val m = runCatching { module.getAndroidManifest() }.getOrNull() ?: return false
        return runCatching {
            when (field) {
                ManifestField.APP_LABEL -> m.setApplicationLabel(value)
                ManifestField.PACKAGE_NAME -> m.setPackageName(value)
                ManifestField.VERSION_NAME -> m.setVersionName(value)
                ManifestField.VERSION_CODE -> {
                    val n = value.toIntOrNull() ?: return false
                    m.setVersionCode(n)
                }
                ManifestField.DEBUGGABLE -> {
                    val b = value.toBooleanStrictOrNull() ?: return false
                    m.setDebuggable(b)
                }
                ManifestField.ICON -> {
                    // 空值 = 移除这个声明
                    if (value.isBlank()) {
                        m.setIconResourceId(0)
                    } else {
                        val id = findResourceId(module, value)
                            ?: error("资源表里找不到 ${value} —— 包里有这个图标资源吗？")
                        m.setIconResourceId(id)
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 按 `@mipmap/xxx`（或 `mipmap/xxx`）查出资源 id，查不到返回 null。
     *
     * 清单里存的是**引用**（资源 id）而不是名字，所以「按名字设图标」必须先查 id。
     */
    fun findResourceId(module: ApkModule, resName: String): Int? {
        val clean = resName.removePrefix("@").removePrefix("+")
        val slash = clean.indexOf('/')
        if (slash <= 0) return null
        val type = clean.substring(0, slash)
        val name = clean.substring(slash + 1)
        val pkg = runCatching { module.getTableBlock()?.pickOne() }.getOrNull() ?: return null
        val entry = runCatching { pkg.getResource(type, name) }.getOrNull() ?: return null
        return runCatching { entry.resourceId }.getOrNull()?.takeIf { it != 0 }
    }

    // ── 取出改动后的字节 ──────────────────────────────────────

    /**
     * 新建一个 mipmap 资源：每种密度一个条目，值指向包内文件路径。
     *
     * 返回新资源的 **id** —— 调用方要拿它去改引用它的地方（二进制 XML 里
     * `@mipmap/xxx` 存的是资源 id，不是名字）。
     *
     * **为什么必须新建**：现代包的图标常常只有「矢量前景 + 纯色背景」的 adaptive 声明，
     * 一个位图都没有。这时往包里塞 PNG 是没用的 —— 资源表里没有条目，系统找不到它，
     * 图标会显示成默认的。要让位图生效，就得连资源表一起建。
     */
    fun addMipmapResource(
        module: ApkModule,
        name: String,
        filesByDensity: Map<String, String>,
    ): Int {
        val table = module.getTableBlock() ?: error("这个包没有资源表，无法新建图标资源")
        val pkg = table.pickOne() ?: error("资源表里没有 package 块")

        var resourceId = 0
        for ((density, path) in filesByDensity) {
            val dpi = DENSITY_DPI[density] ?: continue
            val config = ResConfig().apply { setDensity(dpi) }
            val typeBlock = pkg.getOrCreateTypeBlock(config, "mipmap")
            val entry = typeBlock.getOrCreateEntry(name)
            entry.setValueAsString(path)
            if (resourceId == 0) resourceId = entry.resourceId
        }
        check(resourceId != 0) { "没能建出 mipmap/$name（密度名都对不上？）" }
        return resourceId
    }

    /** 密度名 → dpi 值。 */
    private val DENSITY_DPI = linkedMapOf(
        "mdpi" to 160,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
    )

    /**
     * 让 ARSCLib 写出临时整包，再从里面抽出 [names] 指定的条目。
     *
     * 只抽要的那几个，别的一律不用 —— 其余条目会由 [ZipRebuilder] 从原包原样搬运，
     * 这样既不用信任 ARSCLib 对全部条目的重写结果，
     * 也保住了「未改动条目字节一致」这条我们已经验证过的性质。
     */
    fun writeAndExtract(module: ApkModule, names: Set<String>, workDir: File): Map<String, File> {
        val tmpApk = File(workDir, "arsc-patched.apk")
        module.writeApk(tmpApk)

        val out = mutableMapOf<String, File>()
        ZipFile(tmpApk).use { zip ->
            for (name in names) {
                val entry = zip.getEntry(name) ?: continue
                val f = File(workDir, name.replace('/', '_'))
                zip.getInputStream(entry).use { ins -> f.outputStream().use { ins.copyTo(it) } }
                out[name] = f
            }
        }
        runCatching { tmpApk.delete() }
        return out
    }

    // ── 小工具 ──────────────────────────────────────────────

    /** `@string/app_name` / `string/app_name` → ("string", "app_name") */
    fun parseResName(resName: String): Pair<String, String>? {
        val s = resName.removePrefix("@")
        val slash = s.indexOf('/')
        if (slash <= 0 || slash == s.length - 1) return null
        return s.substring(0, slash) to s.substring(slash + 1)
    }
}
