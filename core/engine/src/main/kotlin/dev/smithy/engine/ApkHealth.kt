package dev.smithy.engine

/**
 * 打开一个包之后的「体检」。
 *
 * ## 为什么要有这一层
 *
 * 原来的流程是「打开 → 直接进编辑 → 打包 → 签名 → 装机」，能不能改、
 * 改完能不能装，全靠装到手机上那一刻才知道。而「装不上」的原因**在打开这个包的时候
 * 就已经确定**了：
 *
 * - 被加固的包，dex 是假的 —— 改 dex 类的东西全白做
 * - Xposed / Zygisk 模块，入口类名写在 native 库或纯文本入口清单里 —— 改包名会让注入失效
 * - 只有 armeabi-v7a 的包，在 64 位专用系统上装不上
 * - minSdk < 24 的包，必须带 v1 签名
 *
 * 这些都能只靠**读文件**判断（不解包、不联网、不调模型），所以做成一次本地扫描，
 * 结论直接摆在界面上。**这是「一次改对」和「改完才发现白改」之间的差别。**
 *
 * 扫描只看两类输入：[ApkMeta]（引擎解析清单/dex/签名的结果）+ 条目名列表。
 * 纯函数，不碰文件系统 —— 判断规则要能被单测钉住（加固壳的名单会过时，规则得能改）。
 */
enum class CheckLevel { OK, WARN, BAD }

data class HealthItem(
    /** 分组名（界面上做行首标签）：加固 / 签名 / 结构 / ABI / 模块特征 / 系统版本 */
    val group: String,
    val text: String,
    val level: CheckLevel = CheckLevel.OK,
)

data class ApkHealth(
    /** 一句话结论，界面上大字显示。 */
    val headline: String,
    /** 结论的补充（为什么 / 什么条件下不成立）。 */
    val detail: String?,
    val level: CheckLevel,
    val items: List<HealthItem>,
    /** 下一步建议；null = 没什么特别要提醒的。 */
    val next: String?,
)

object ApkHealthCheck {

    /**
     * Xposed / Zygisk 模块的入口声明。
     *
     * 这几个名字**必须**与真实实现对齐（见 WeKite 侧的 `assets/xposed_init`、
     * `META-INF/xposed/java_init.list`）。判断它们的价值在于：改包名之前就告诉用户
     * 「这不是普通 App」——否则用户会以为「改个 applicationId 而已」。
     */
    private val XPOSED_MARKERS = listOf(
        "assets/xposed_init",
        "meta-inf/xposed/java_init.list",
        "meta-inf/xposed/module.prop",
        "meta-inf/xposed/scope.list",
    )

    fun of(
        meta: ApkMeta,
        entries: List<String>,
        /**
         * 手机上已装的那个包的证书 SHA-256。给了就能判断「装机会不会要求先卸载」——
         * 这是用户装机时唯一会撞的墙。引擎是纯 JVM，拿不到 PackageManager，
         * 所以由平台层注入。
         */
        installedCertSha256: String? = null,
    ): ApkHealth {
        val names = entries.map { it.lowercase() }
        val items = mutableListOf<HealthItem>()

        // ── 加固 ────────────────────────────────────────────────
        val packer = meta.packerGuess
        if (packer != null) {
            items += HealthItem(
                group = "加固",
                text = "检测到「${packer.name}」（${packer.evidence.joinToString("、")}）—— " +
                    "壳里的 dex 多半是假的，改字符串/类通常不生效",
                level = CheckLevel.BAD,
            )
        } else {
            items += HealthItem("加固", "未检测到常见的加固壳")
        }

        // ── 签名 ────────────────────────────────────────────────
        val cert = meta.signatures.firstOrNull()
        if (meta.signatures.isEmpty()) {
            items += HealthItem("签名", "没有读到有效签名（包被破坏过，或者本来就是未签名的产物）", CheckLevel.WARN)
        } else {
            val schemes = meta.signatures.map { it.scheme }.distinct().sorted().joinToString("+") { "v$it" }
            val debug = if (meta.signatures.any { it.isDebug }) "（debug 证书）" else ""
            val mismatch = installedCertSha256 != null &&
                cert != null &&
                !installedCertSha256.equals(cert.sha256, ignoreCase = true)
            items += when {
                mismatch -> HealthItem(
                    group = "签名",
                    text = "$schemes$debug · 与机上已装版本**不是同一把证书** → 装机会要求先卸载",
                    level = CheckLevel.WARN,
                )
                else -> HealthItem("签名", "$schemes$debug · 自签名（重打包必然换签名）")
            }
        }

        // ── 结构 ────────────────────────────────────────────────
        if (meta.packageName == UNKNOWN) {
            items += HealthItem("结构", "清单读不出来 —— 这不是标准 APK", CheckLevel.BAD)
        } else {
            val dex = meta.dexStats.size
            val dexText = if (dex == 0) "没有 dex（纯资源包？）" else "$dex 个 dex"
            val splitText = if (meta.isSplit) " · split 包" else " · 单包"
            val arsc = if (names.any { it == "resources.arsc" }) " · 资源表在" else " · **没有资源表**"
            items += HealthItem("结构", "$dexText$splitText$arsc")
        }

        // ── ABI ─────────────────────────────────────────────────
        val abis = entries.asSequence()
            .filter { it.startsWith("lib/") }
            .map { it.split('/').getOrNull(1).orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
        items += when {
            abis.isEmpty() -> HealthItem("ABI", "没有 native 库（纯 Java/Kotlin）")
            abis.none { it.startsWith("arm64") } -> HealthItem(
                group = "ABI",
                text = "${abis.joinToString("、")} —— **没有 arm64-v8a**：64 位专用系统上装不上",
                level = CheckLevel.WARN,
            )
            else -> HealthItem("ABI", abis.joinToString(" · "))
        }

        // ── 模块特征 ────────────────────────────────────────────
        val hasXposed = XPOSED_MARKERS.any { it in names }
        val hasMagisk = names.any { it == "module.prop" } && names.any { it.startsWith("zygisk/") }
        val isModule = hasXposed || hasMagisk
        if (hasXposed) {
            items += HealthItem(
                group = "模块特征",
                text = "Xposed 模块：入口类名写在 assets/xposed_init（或 META-INF/xposed/）里，" +
                    "**改包名会让注入失效** —— 名字对不上，框架找不到入口",
                level = CheckLevel.WARN,
            )
        }
        if (hasMagisk) {
            items += HealthItem(
                group = "模块特征",
                text = "Magisk / Zygisk 模块：脚本与 WebUI 常写死 `/data/adb/modules/<id>`，" +
                    "**改 module.prop 的 id 会让它找不到自己**",
                level = CheckLevel.WARN,
            )
        }

        // ── 系统版本 ────────────────────────────────────────────
        val minSdk = meta.minSdk
        val targetSdk = meta.targetSdk
        items += HealthItem("系统版本", "minSdk $minSdk · targetSdk $targetSdk")
        if (minSdk in 1..23) {
            items += HealthItem(
                group = "系统版本",
                text = "minSdk 低于 24：必须带 v1 签名才装得上（本工具会自动带上）",
                level = CheckLevel.WARN,
            )
        }

        // ── 结论 ────────────────────────────────────────────────
        val bad = items.firstOrNull { it.level == CheckLevel.BAD }
        val warns = items.filter { it.level == CheckLevel.WARN }

        val (headline, detail, level) = when {
            bad != null && bad.group == "加固" -> Triple(
                "这个包被加固了（${packer?.name ?: "未知壳"}）：改 dex 多半无效",
                "改资源、清单、图标这类**不碰代码**的东西仍然可能生效；要改代码得先脱壳",
                CheckLevel.BAD,
            )
            bad != null -> Triple("这个包改不了：${bad.text}", null, CheckLevel.BAD)
            // 模块那一条最该顶到结论里：它的代价（注入失效）比别的提醒都大，
            // 而且用户最容易在这里以为「只是改个名字」
            isModule -> Triple(
                "可以改。但**改包名**会让注入失效",
                "要改名就得整链改：入口清单 + native 里的类名一起改；改文案/图标不受影响",
                CheckLevel.WARN,
            )
            warns.isNotEmpty() -> Triple("可以改，有 ${warns.size} 处要留意", warns.first().text, CheckLevel.WARN)
            else -> Triple("可以放心改", null, CheckLevel.OK)
        }

        return ApkHealth(
            headline = headline,
            detail = detail,
            level = level,
            items = items,
            next = when {
                bad != null -> "先别动手：把上面标红的那条处理掉，或者换一个思路（改资源而不是改代码）"
                isModule -> "改文案 / 换图标随便改；要改包名先看上面那条"
                else -> null
            },
        )
    }

    private const val UNKNOWN = "—"
}

/**
 * 加固壳的识别。
 *
 * **按特征文件名匹配，不做行为分析**：壳的 so 名是它们最稳定的指纹
 * （改包名容易，改 so 名要同步改加载逻辑，壳厂商一般不动）。名单一定会过时，
 * 所以规则写成表，加一条就是加一行；认不出来时**不猜**（宁可说「未检测到」，
 * 也不要给一个假的名字让用户按错误的前提动手）。
 */
object PackerDetect {

    private data class Rule(val name: String, val marks: List<String>)

    private val RULES = listOf(
        Rule("360 加固", listOf("libjiagu.so", "libjiagu_art.so", "libjiagu_x86.so", "libjiagu_a64.so")),
        Rule(
            "梆梆加固",
            listOf("libdexhelper.so", "libdexhelper-x86.so", "libsecshell.so", "libsecexe.so", "libsecmain.so"),
        ),
        Rule(
            "腾讯乐固 / 御安全",
            listOf("libshell.so", "libshellx.so", "libshella.so", "libtosprotection.so", "libtosprotection.x86.so"),
        ),
        Rule("阿里聚安全", listOf("libmobisec.so", "libsgmain.so", "libsgsecuritybody.so")),
        Rule("爱加密", listOf("libexec.so", "libexecmain.so", "ijiami.dat")),
        Rule("网易易盾", listOf("libnesec.so", "libnqshield.so")),
        Rule("百度加固", listOf("libbaiduprotect.so")),
        Rule("瑞星 / 其他商业壳", listOf("libkwscmm.so", "libkwscr.so")),
    )

    /** assets 下藏 dex 是「把真实 dex 挪走」的通用手法，认得出来但认不出是谁。 */
    private val SUSPICIOUS_DEX_IN_ASSETS = Regex("""assets/(classes\d*\.dex|.*\.jar)""")

    fun guess(entries: List<String>): PackerGuess? {
        val bases = entries.map { it.substringAfterLast('/').lowercase() }

        RULES.forEach { rule ->
            val hit = rule.marks.filter { it in bases }
            if (hit.isNotEmpty()) {
                return PackerGuess(
                    name = rule.name,
                    // 一条命中就足以怀疑；多条才是「确定」
                    confidence = if (hit.size >= 2) 1f else 0.6f,
                    evidence = hit,
                )
            }
        }

        val odd = entries.filter { SUSPICIOUS_DEX_IN_ASSETS.matches(it.lowercase()) }
        if (odd.isNotEmpty()) {
            return PackerGuess(
                name = "未知壳（dex 藏在 assets 里）",
                confidence = 0.4f,
                evidence = odd.take(3),
            )
        }
        return null
    }
}
