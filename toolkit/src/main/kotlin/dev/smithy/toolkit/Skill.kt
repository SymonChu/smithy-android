package dev.smithy.toolkit

/**
 * 「技能」= 一套固定的流程（侦查 → 改动 → 验证），而不是一句提示词。
 *
 * ## 为什么要有它
 *
 * 之前用户面对的是一个空输入框 + 49 个工具：想改个应用名，得先想清楚该怎么说，
 * 而模型得自己在 49 个工具里挑。这条路能走通，但**每次都要重新走一遍判断**。
 * 同类工具（Android 逆向的 skill 包）成熟做法正相反：把常见意图写成固定流程，
 * 用户只选「要做哪件事」，流程里的步骤、要用的工具、什么时候停都写死。
 *
 * 所以每条技能带三样东西：
 * 1. [prompt] —— 流程本身（先干什么、看什么判据、说完什么才算完）
 * 2. [tools] —— **只挂这条流程要用的工具**（收窄工具面比在提示里反复叮嘱有效）
 * 3. [readOnly] —— 这条流程会不会改文件（界面上要标出来）
 *
 * [tools] 里的名字必须是注册表里真实存在的工具：写错一个，模型就会看到一个
 * 永远调不动的名字。`SkillTest` 把这件事钉死了 —— 技能的步骤必须是**可执行的**，
 * 而不是一段读起来很顺的说明。
 */
data class Skill(
    val id: String,
    val title: String,
    /** 一句话说明「这条技能会做什么」（界面上是卡片副行）。 */
    val summary: String,
    val readOnly: Boolean,
    /** 这条技能挂哪些工具；空 = 纯引导型（[guide] 说明为什么要在别处做）。 */
    val tools: List<String> = emptyList(),
    /** 流程说明，作为用户消息注入这一轮。引导型为空。 */
    val prompt: String = "",
    /** 引导型技能的理由：为什么不在这里做、要去哪里做。 */
    val guide: String? = null,
)

object Skills {

    val all: List<Skill> = listOf(
        Skill(
            id = "inspect",
            title = "体检",
            summary = "只读地看清这个包：壳 / 签名 / 结构 / 模块特征，给「能不能改、改完能不能装」的结论",
            readOnly = true,
            tools = listOf(
                "workspace.state", "apk.meta", "apk.signatures", "apk.permissions", "apk.components",
                "entry.list", "arsc.list", "elf.inspect", "elf.strings", "module.inspect",
            ),
            prompt = """
                对这个包做一次只读体检，然后给结论。**不要修改任何东西**（这条流程是只读的）。

                要覆盖：
                1. 有没有加固壳（看 lib/ 下的特征 so 与 assets 里的 dex）
                2. 签名方案（v1/v2/v3）与是不是 debug 证书
                3. 结构：几个 dex、是不是 split、资源表能不能读
                4. native 库的 ABI（只有 32 位的话在 64 位系统上装不上）
                5. 是不是 Xposed / Zygisk 模块（若是，明确说明「改包名会让注入失效」）
                6. minSdk / targetSdk（minSdk 低于 24 必须带 v1 签名）

                结论要一句话说清「能不能改、改完能不能装」，再列风险。
            """.trimIndent(),
        ),

        Skill(
            id = "text",
            title = "改文案",
            summary = "搜到界面上那句话、改掉，并说清改了几处",
            readOnly = false,
            tools = listOf(
                "workspace.state", "apk.meta", "arsc.list", "arsc.set", "arsc.replace_string",
                "dex.search", "dex.replace_string", "patch.list", "patch.revert",
            ),
            prompt = """
                改这个包里的界面文案。

                流程：
                1. 先用 arsc.list 找到那条资源（先确认它真的存在、当前值是什么）
                2. 用 arsc.set 或 arsc.replace_string 改（**优先资源表**：dex 那条路慢 87 倍，
                   只在确认文案是硬编码在代码里时才用 dex.replace_string）
                3. 报告改了哪几条、从什么改成什么

                只要改用户点名的那几条，**不要顺手改别的文案**。改完告诉用户下一步可以「打包并装机」。
            """.trimIndent(),
        ),

        Skill(
            id = "rename",
            title = "改包名",
            summary = "整链改：清单 + 资源引用 + dex 引用（模块还要改入口清单）",
            readOnly = false,
            tools = listOf(
                "workspace.state", "apk.meta", "entry.list", "entry.read", "axml.decode",
                "manifest.set", "arsc.list", "arsc.replace_string", "dex.search", "dex.replace_string",
                "patch.list", "patch.revert",
            ),
            prompt = """
                要把这个包改成另一个包名。**先说清代价，再动手。**

                流程：
                1. 先判断这个包是不是 Xposed / Zygisk 模块（entry.list 看 assets/xposed_init、
                   META-INF/xposed/、module.prop）。**如果是，必须先告诉用户**：
                   入口类名写在入口清单和 native 库里，只改 manifest 的包名会让注入失效，
                   而 native 里的类名在手机上改不了 —— 让用户决定要不要继续。
                2. 用 manifest.set 改包名
                3. 用 entry.read 把入口清单（assets/xposed_init、META-INF/xposed/*.list）读出来，
                   如果里面写着旧包名，一并改掉
                4. 报告：改了哪几处、还有哪些地方**没改**（例如 native 里的字符串）

                不要只改了 manifest 就宣布完成。
            """.trimIndent(),
        ),

        Skill(
            id = "localize",
            title = "汉化",
            summary = "抽字符串 → 逐条翻译 → 写回资源表，保留改动记录",
            readOnly = false,
            tools = listOf(
                "workspace.state", "apk.meta", "arsc.list", "arsc.replace_string", "arsc.set",
                "patch.list", "patch.revert",
            ),
            prompt = """
                把这个包的界面文案汉化。

                流程：
                1. 先用 arsc.list 列出 string 资源，看清哪些还是英文的（**先看再动**：不要凭印象
                   直接批量替换，英文原文抄错一个字符就白改一条）
                2. 一次给一批（arsc.replace_string 支持多条，200 条约 300 毫秒），
                   不要一条一条调（每条都要重新序列化一次资源表）
                3. 报告改了多少条，并**列出你改了但可能翻译得不好的几条**让用户过目

                不要动代码里的字符串常量（那是另一条路，代价大得多），除非用户明确要求。
            """.trimIndent(),
        ),

        Skill(
            id = "killverify",
            title = "去签名校验",
            summary = "定位签名校验调用点并绕过 —— 只在 dex 可改、非加固包上可行",
            readOnly = false,
            tools = listOf(
                "workspace.state", "apk.meta", "dex.search", "jadx.decompile_class",
                "smali.read_class", "smali.read_method", "smali.patch", "patch.list", "patch.revert",
            ),
            prompt = """
                这个包重打包后会因为签名变了而拒绝工作，要处理它的签名校验。

                流程：
                1. **先确认可行性**：如果体检显示有加固壳（libjiagu / libDexHelper 之类），
                   直接说明这条路走不通（壳里的 dex 是假的，改了也不生效），然后停下。
                2. 用 dex.search 找签名校验的调用点（关键线索：getPackageInfo、signatures、
                   signature、GET_SIGNATURES、checkSignature、以及自研的校验方法名）
                3. 用 jadx.decompile_class / smali.read_method 看清那段逻辑在比什么，
                   **说清你判断的根据**（哪一行、拿什么和什么比）
                4. 用 smali.patch 改掉（保持寄存器与方法签名不变，改动越小越安全）
                5. 报告改了哪个类、哪个方法、依据是什么

                只做「让自己改过的包能跑起来」这件事。如果用户的目标是破解别人的商业软件、
                绕过付费或授权，直接说明你不做，并停下。
            """.trimIndent(),
        ),

        Skill(
            id = "ship",
            title = "装机验证",
            summary = "把当前改动打包 → 签名 → 验签 → 装机，并如实报告每一步结果",
            readOnly = false,
            tools = listOf(
                "workspace.state", "patch.list", "apk.rebuild", "apk.sign", "apk.verify", "apk.install",
            ),
            prompt = """
                把当前工作区的改动打包、签名、验签、装机。

                流程：
                1. 先用 patch.list 确认有哪些改动（没有改动就直说，别白跑一遍打包）
                2. apk.rebuild → apk.sign → apk.verify → apk.install，顺序不能颠倒
                3. **每一步的结果都要如实报告**：验签给出方案（v1/v2/v3）；装机要说明走的哪条通道、
                   是不是只是「交给系统安装器」（那还需要用户手动确认一次）
                4. 装机失败时把原因说清（签名冲突要在结论里写明「需要先卸载」），不要重试同样的调用
            """.trimIndent(),
        ),

        Skill(
            id = "icon",
            title = "换图标",
            summary = "按密度替换图标图层（需要在工作台里选图，所以这里只做引导）",
            readOnly = false,
            guide = "换图标要选一张图，并把它按 5 个密度渲染后写回资源表 —— 这一步在工作台里做：" +
                "工作台 → 概览 → 换图标。这里做不了「你替我选张图」这件事。",
        ),
    )

    /** 对话页技能条上直接给的那几个（常用的排前面）。 */
    val quickPicks: List<Skill> = listOf("inspect", "text", "rename", "icon", "ship")
        .mapNotNull { id -> all.firstOrNull { it.id == id } }

    fun byId(id: String): Skill? = all.firstOrNull { it.id == id }
}
