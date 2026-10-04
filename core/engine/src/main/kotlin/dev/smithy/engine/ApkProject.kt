package dev.smithy.engine

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream

/**
 * APK 工程：一个已打开的工作区。
 *
 * 实现可替换（ARSCLib+APKEditor 原生实现 / 未来的 rootfs+apktool 兜底实现），
 * UI 与 AI **只**通过这个接口操作 APK，不得直接依赖任何引擎库。
 */
interface ApkProject : AutoCloseable {
    val id: String
    val state: WorkspaceState
    val meta: ApkMeta
    val patchCount: Int

    /** 耗时阶段的进度（解包/汇编/打包/对齐/签名），UI 用来画进度条。 */
    val progress: Flow<BuildProgress>

    fun close(keepArtifacts: Boolean)

    /** 让调用方能写 `use { }`。默认不保留中间产物（签名后的包会另行导出）。 */
    override fun close() = close(keepArtifacts = false)

    // ── 条目读写 ──────────────────────────────────────────────
    suspend fun list(path: String? = null): List<ApkEntry>
    suspend fun readEntry(path: String): InputStream
    suspend fun writeEntry(path: String, data: InputStream): PatchRecord
    suspend fun deleteEntry(path: String): PatchRecord

    // ── 代码层 ────────────────────────────────────────────────
    suspend fun dexSearch(query: DexQuery): List<DexHit>
    suspend fun decompileToJava(className: String): String
    suspend fun readSmali(className: String): String
    suspend fun readSmaliMethod(className: String, methodSig: String): String
    suspend fun patchSmali(
        className: String,
        methodSig: String?,
        pattern: String,
        replacement: String,
        regex: Boolean = false,
    ): PatchRecord

    // ── 资源层 ────────────────────────────────────────────────
    suspend fun resources(type: String? = null, filter: String? = null): List<ResourceEntry>
    suspend fun setResource(resName: String, value: String): PatchRecord
    suspend fun replaceString(from: String, to: String, regex: Boolean = false): List<PatchRecord>

    /**
     * 一次替换多组字符串（dex 常量 + 资源表两层一起）。
     *
     * **为什么必须有批量入口**：[replaceString] 每调用一次就要让 ARSCLib 把资源表
     * 序列化一遍（它得写出临时整包才能拿到新的 `resources.arsc`）。实测单次改资源
     * 的端到端代价在秒级，连着替换 200 条文案就是十分钟 ——
     * 而 M2 的验收要求是 30 秒内。
     *
     * 批量把「所有替换都在内存里做完，最后只序列化一次」，
     * dex 层同理：每个 dex 只重建成对象树一次。
     *
     * 只在需要正则时用 [replaceString]：批量走的是字面量匹配。
     */
    suspend fun replaceStrings(
        pairs: List<StringReplacement>,
        scope: ReplaceScope = ReplaceScope.BOTH,
    ): List<PatchRecord>

    suspend fun setManifestField(field: ManifestField, value: String): PatchRecord
    suspend fun replaceIcon(source: String, densities: List<String>? = null): List<PatchRecord>

    /**
     * 解析这个包的图标由哪些条目组成。
     *
     * **不按名字猜**（`ic_launcher` 只是 Android Studio 模板的默认名，实际项目里
     * `app_icon`、`launcher_logo` 都很常见），而是顺着清单的 `android:icon` 引用走：
     * 引用 → 资源名 → 该资源的所有密度变体；指向 adaptive icon 的 xml 时再顺着
     * 里面的 drawable 引用找前景 / 背景。
     *
     * 找不到时 [IconTargets.notes] 会说明**为什么**（清单没声明 / 指向纯色 / 名字对不上），
     * 这比一句「换不了」有用得多。
     */
    suspend fun iconTargets(): IconTargets

    /**
     * 规划一次图标替换：告诉调用方要画哪些图、多大、内容画在哪个范围内。
     *
     * **本方法不画画、也不改包**。画图要用 Android 的 Bitmap，而引擎是纯 JVM ——
     * 所以切成「规划 → 画图 → [applyIconReplace]」三步，职责在两边各自清楚。
     */
    suspend fun planIconReplace(): IconPlan

    /**
     * 应用画好的图。[rendered] 的 key 是 [IconRender.key]，值是 PNG 字节。
     *
     * 包里本来就有位图图层时是直接覆盖；只有矢量图/纯色时，会**新建 mipmap 资源**
     * （各密度）并把 adaptive 声明指过来 —— 只塞 PNG 是没用的，资源表里没有条目，
     * 系统找不到那张图。
     */
    suspend fun applyIconReplace(rendered: Map<String, ByteArray>): List<PatchRecord>

    // ── XML 层 ────────────────────────────────────────────────
    /**
     * 把包内二进制 XML 解码成可读文本。
     *
     * 读不了（条目不存在、或它不是二进制 XML）要明确报错 ——
     * 普通文本文件请走 [readEntry]，别让人对着空字符串猜。
     */
    suspend fun readXml(path: String): String

    /**
     * 改二进制 XML 里某个元素的属性。
     *
     * [elementPath] 是简化路径：`application/activity` 是 application 下第一个 activity，
     * `activity[1]` 是第二个。属性不存在就新建。
     *
     * 与 [setManifestField] 的分工：常用清单字段优先用后者（ARSCLib 对它们有专门处理，
     * 比如改应用名会连带处理资源引用）；这里改的是**任意** XML 的任意属性。
     *
     * 路径从文档的**直接子元素**开始 —— 清单的根是 `manifest`，所以 `application`
     * 要写成 `manifest/application`；同名节点的第二个用 `activity[1]` 取。
     */
    suspend fun patchXml(path: String, elementPath: String, attr: String, value: String): PatchRecord

    // ── 打包链路 ──────────────────────────────────────────────
    suspend fun rebuild(incremental: Boolean = true): File        // → unsigned.apk
    suspend fun sign(config: SignConfig): File                    // → signed.apk
    suspend fun verify(apk: File): VerifyResult
    suspend fun install(apk: File, via: InstallVia): InstallResult

    // ── 改动管理 ──────────────────────────────────────────────
    suspend fun patches(): List<PatchRecord>
    suspend fun revert(patchId: String)
}

// ── 配套类型 ────────────────────────────────────────────────

/**
 * 字符串替换的作用域。
 *
 * **为什么要这个参数**：两层的代价差两个数量级。资源层是「内存里改表、序列化一次」，
 * 200 组约两秒；dex 层每个命中的 dex 都要重建成对象树再写出，65k 方法的 dex 是秒级，
 * 实测 200 组规则命中 5 个 dex 要 25 秒。
 *
 * 而「改文案」这个需求绝大多数时候只需要动资源表 —— 硬编码在代码里的文案本来就少，
 * 用户想批量本地化时更不该为此等半分钟。
 */
enum class ReplaceScope {
    /** 只改 dex 里的字符串常量（硬编码文案） */
    DEX,

    /** 只改资源表里的字符串（改文案的主力路径） */
    ARSC,

    /** 两层都改（搜一个词、不确定它躺在哪层时用） */
    BOTH,
}

/**
 * 一组字符串替换。
 *
 * 改文案时几乎总是「一次改很多条」（本地化、统一改名），所以接口直接收列表 ——
 * 一条一条调的话，每条都要把资源表序列化一遍。
 */
data class StringReplacement(val from: String, val to: String)

data class DexQuery(
    val text: String,
    val scope: Scope = Scope.STRING,
    val regex: Boolean = false,
    val limit: Int = 200,
    val dexName: String? = null,
) { enum class Scope { CLASS, METHOD, STRING, FIELD } }

data class DexHit(
    val className: String,
    val methodSig: String?,
    val fieldName: String?,
    val snippet: String,
    val dexName: String,
)

data class ResourceEntry(
    val resName: String,          // "@string/app_name"
    val type: String,
    val value: String?,
    val isComplex: Boolean,
)

/**
 * 能直接改的清单字段。
 *
 * [ICON] 的值形如 `@mipmap/xxx`（也可以给 `mipmap/xxx`）—— 引擎会按名字查出资源 id 再写进去，
 * 因为清单里存的是**引用**而不是名字。给空字符串表示**移除**这个声明。
 */
enum class ManifestField {
    APP_LABEL, PACKAGE_NAME, VERSION_NAME, VERSION_CODE, DEBUGGABLE, ICON,

    /**
     * 最低支持的 Android 版本（API 级别）。
     *
     * **提高它的代价要在界面上说清楚**：包会声明「只支持更新的系统」，也就是**放弃老设备**。
     * 常见用法是提到 24 —— 那样就不再需要 v1 签名（v2/v3 更快、防篡改更强）。
     * 不过 v1 已经能自己签了，所以这不再是「必须提」，纯粹是取舍。
     */
    MIN_SDK,

    /** 目标版本（API 级别）。影响系统的兼容行为，一般与 minSdk 一起调。 */
    TARGET_SDK,
}

data class SignConfig(
    val keystoreRef: String? = null,   // null = 用内置自动生成的 keystore
    val alias: String = "smithy",
    val schemes: Set<Int> = setOf(1, 2, 3),
    val v1Enabled: Boolean = true,
)

data class VerifyResult(val valid: Boolean, val schemes: List<Int>, val messages: List<String>)

enum class InstallVia { SHIZUKU, ROOT, INTENT }

data class InstallResult(val ok: Boolean, val via: InstallVia, val message: String?)

data class BuildProgress(
    val stage: Stage,
    val percent: Int,
    val detail: String? = null,
) { enum class Stage { UNPACK, ASSEMBLE_SMALI, PACK_RES, BUILD, ALIGN, SIGN, DONE } }
