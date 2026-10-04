package dev.smithy.engine.internal

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import dev.smithy.engine.ApkEntry
import dev.smithy.engine.ApkMeta
import dev.smithy.engine.ApkProject
import dev.smithy.engine.BuildProgress
import dev.smithy.engine.ComponentInfo
import dev.smithy.engine.DexQuery
import dev.smithy.engine.DexStat
import dev.smithy.engine.IconPlan
import dev.smithy.engine.IconRender
import dev.smithy.engine.IconTargets
import dev.smithy.engine.InstallChannel
import dev.smithy.engine.InstallResult
import dev.smithy.engine.InstallVia
import dev.smithy.engine.ManifestField
import dev.smithy.engine.PatchRecord
import dev.smithy.engine.PatchOrigin
import dev.smithy.engine.ReplaceScope
import dev.smithy.engine.ResourceEntry
import dev.smithy.engine.SignConfig
import dev.smithy.engine.SignatureInfo
import dev.smithy.engine.StringReplacement
import dev.smithy.engine.DexHit
import dev.smithy.engine.VerifyResult
import dev.smithy.engine.WorkspaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 新图标资源的固定名。用固定名而不是随机名：重复替换时会覆盖同一组资源，
 * 不会每换一次就往资源表里塞一整套新的（那会让包越来越大、资源表越来越脏）。
 */
private const val NEW_ICON_BASE = "smithy_icon"

/** 清单条目名。改图标要连它一起落盘。 */
private const val MANIFEST_ENTRY = "AndroidManifest.xml"

/** adaptive 前景的安全区比例：108dp 的画布里只有中间 72dp 保证可见，超出会被启动器裁掉。 */
private const val SAFE_NUM = 72
private const val SAFE_DEN = 108

/** 密度 → 两套尺寸（px）：传统图标边长、adaptive 画布边长。 */
private data class DensityCanvas(val legacy: Int, val adaptive: Int)

private val DENSITY_CANVAS = linkedMapOf(
    "mdpi" to DensityCanvas(48, 108),
    "hdpi" to DensityCanvas(72, 162),
    "xhdpi" to DensityCanvas(96, 216),
    "xxhdpi" to DensityCanvas(144, 324),
    "xxxhdpi" to DensityCanvas(192, 432),
)

/**
 * 基于 ARSCLib 的引擎实现（M0：只读）。
 *
 * 本类**不 import android.*** —— Android 的接缝（Context / SAF / Shizuku）在 app 与 feature 层。
 *
 * M0 只做「打开 + 解析」；写操作（改 smali / 资源 / 重打包 / 签名 / 装机）在 M1 实现，
 * 现在统一抛 [NotImplementedError]，避免出现"看起来能改但其实没落盘"的假实现。
 */
internal class ApkProjectImpl(
    override val id: String,
    val apkFile: File,
    private val module: ApkModule,
    private val zip: ZipFile,
    meta: ApkMeta,
    /**
     * 内置签名密钥的存放目录。
     *
     * 调用方**必须**给一个持久位置（App 私有目录）。用临时目录的话，每次重启都重新生成密钥，
     * 签名指纹一变，改过的包就装不上了（系统只报"应用未安装"）。
     */
    private val keystoreDir: File,
    /**
     * 装机通道。为 null 时 [install] 会明确报错而不是假装成功 ——
     * 引擎层不认识 Shizuku/Root，没有通道就等于「这台设备上装不了」，得让用户知道原因。
     */
    private val installChannel: InstallChannel? = null,
) : ApkProject {

    /**
     * 包的基本信息。
     *
     * 打开时算一次，但**清单改动之后必须重算**（[recomputeMeta]）：应用名、版本、
     * minSdk / targetSdk 都来自清单，不重算的话界面会一直显示打开时的快照 ——
     * 用户改成新名字、点了「应用」，看到的还是旧名字，只会以为操作没生效，然后再点一次。
     *
     * 只重算清单相关的部分（`copy` 那几项）：dex 统计与签名信息不会因为改清单而变化，
     * 没必要连带重新扫一遍目录。
     */
    override var meta: ApkMeta = meta
        private set

    override var state: WorkspaceState = WorkspaceState.UNPACKED
        private set

    private val _progress = MutableStateFlow(BuildProgress(BuildProgress.Stage.DONE, 100))
    override val progress: StateFlow<BuildProgress> = _progress

    override val patchCount: Int get() = workspaceRef?.patchCount ?: 0

    // ── M1：dex 索引、工作区与临时目录（都是懒建，M0 的解析路径不碰它们）─────

    /** 改动覆盖层：只存改过的条目，不预先解包整包 */
    @Volatile
    private var workspaceRef: Workspace? = null

    /** 上一次 rebuild 的产物。sign() 没有输入参数，靠它衔接打包与签名两步 */
    @Volatile
    private var lastBuilt: File? = null

    @Volatile
    private var lastSigned: File? = null

    private fun workspace(): Workspace = workspaceRef ?: synchronized(this) {
        workspaceRef ?: Workspace(tmpDir("ws"), id).also { workspaceRef = it }
    }

    @Volatile
    private var dexIndexRef: DexIndex? = null

    /** jadx 单类反编译（按 dex 缓存会话，见 [JadxBridge]） */
    @Volatile
    private var jadxRef: JadxBridge? = null

    @Volatile
    private var tmpRoot: File? = null

    /** dex 的 opcodes 按 minSdk 定：生成的 dex 必须能跑在目标设备上 */
    private val apiLevel: Int get() = meta.minSdk.takeIf { it in 21..99 } ?: 21

    private fun dexIndex(): DexIndex = dexIndexRef ?: synchronized(this) {
        dexIndexRef ?: DexIndex(
            apkFile,
            apiLevel,
            // 覆盖层优先：改完 dex 之后重建的索引必须读到改动，否则「改完再搜」看到旧内容
            overlayBytes = { path -> workspaceRef?.overlayFile(path)?.readBytes() },
        ).also { dexIndexRef = it }
    }

    /**
     * 让 dex 索引失效。
     *
     * **改了 dex 就必须调用**：`DexIndex` 持有的是原包里那份 dex 的句柄且带缓存 ——
     * 不失效的话，同一次会话里「改完再搜」会看到旧内容。
     * 而「改一下、搜一下确认」恰恰是最常用的操作（用户和 AI 都这么用），
     * 看到旧内容会让人以为改动没生效，进而重复改一遍。
     */
    private fun resetDexIndex() {
        synchronized(this) {
            dexIndexRef?.close()
            dexIndexRef = null
            // jadx 会话绑在 dex 上，索引换了它也得重建，否则反编译看到的是旧代码
            jadxRef = null
        }
    }

    /** jadx 会话懒建：不点开 Java 视图，就一个字节都不为它加载 */
    private fun jadx(): JadxBridge = jadxRef ?: synchronized(this) {
        jadxRef ?: JadxBridge(dexIndex()).also { jadxRef = it }
    }

    private fun tmpDir(prefix: String): File {
        val root = tmpRoot ?: synchronized(this) {
            tmpRoot ?: Files.createTempDirectory("smithy-ws-").toFile().also { tmpRoot = it }
        }
        return Files.createTempDirectory(root.toPath(), prefix).toFile()
    }

    override fun close(keepArtifacts: Boolean) {
        jadxRef?.close()
        jadxRef = null
        dexIndexRef?.close()
        dexIndexRef = null
        workspaceRef = null
        if (!keepArtifacts) runCatching { tmpRoot?.deleteRecursively() }
        tmpRoot = null
        runCatching { zip.close() }
        runCatching { module.close() }
        state = WorkspaceState.IDLE
    }

    // ── 条目读写（只读部分）─────────────────────────────────────

    override suspend fun list(path: String?): List<ApkEntry> = withContext(Dispatchers.IO) {
        val ws = workspaceRef
        zip.entries().asSequence()
            .filter { !(ws?.isDeleted(it.name) ?: false) }
            .filter { path == null || it.name.startsWith(path) }
            .map {
                // 改过的条目以覆盖层为准：否则 size 显示的是旧值，与 readEntry 读到的不一致
                val overlay = ws?.overlayFile(it.name)
                if (overlay != null) {
                    ApkEntry(it.name, overlay.length(), overlay.length(), it.isDirectory)
                } else {
                    ApkEntry(it.name, it.size.coerceAtLeast(0), it.compressedSize.coerceAtLeast(0), it.isDirectory)
                }
            }
            .sortedBy { it.path }
            .toList()
    }

    override suspend fun readEntry(path: String): InputStream = withContext(Dispatchers.IO) {
        workspaceRef?.overlayFile(path)?.let { return@withContext it.inputStream() }
        val entry = zip.getEntry(path) ?: throw NoSuchElementException("包内不存在: $path")
        zip.getInputStream(entry)
    }

    // ── 以下为 M1 起的写能力，M0 明确不实现 ──────────────────────

    private fun todo(m: String): Nothing =
        throw NotImplementedError("$m —— 计划在 M1 实现（见 docs/06-milestones.md）")

    // ── 条目写入：一切改动都落到工作区覆盖层，原包始终不动 ──────────

    override suspend fun writeEntry(path: String, data: InputStream): PatchRecord = withContext(Dispatchers.IO) {
        val tmp = File(tmpDir("put"), path.substringAfterLast('/'))
        data.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        stageEntry(path, tmp, PatchRecord.PatchKind.ENTRY_REPLACE, note = "写入条目")
    }

    override suspend fun deleteEntry(path: String): PatchRecord = withContext(Dispatchers.IO) {
        val record = workspace().markDeleted(path)
        state = WorkspaceState.DIRTY
        record
    }

    /**
     * 把某个条目记进覆盖层。
     *
     * 「改动前的内容」优先取覆盖层里已有的版本：同一个 entry 连续改两次时，
     * 第二次的 before 必须是第一次改完的样子，否则回退会跳步。
     */
    private fun stageEntry(
        entryPath: String,
        after: File,
        kind: PatchRecord.PatchKind,
        note: String? = null,
    ): PatchRecord {
        val ws = workspace()
        val before = ws.overlayFile(entryPath) ?: extractOriginal(entryPath)
        val record = ws.stage(entryPath, before, after, kind, PatchOrigin.User, note)
        state = WorkspaceState.DIRTY

        // dex 变了就让索引失效。放在这里而不是各调用点：所有 dex 改动都经过 stageEntry，
        // 这样不会漏掉某条路径（漏了的症状是「改完搜不到新值」）
        if (entryPath.endsWith(".dex")) resetDexIndex()

        return record
    }

    /** 从原包里解出条目内容，作为改动的基线。条目不存在时返回 null（表示是新增）。 */
    private fun extractOriginal(entryPath: String): File? {
        val entry = zip.getEntry(entryPath) ?: return null
        val out = File(tmpDir("orig"), entryPath.substringAfterLast('/'))
        zip.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
        return out
    }

    // ── 资源层改动落进覆盖层 ──────────────────────────────────

    /**
     * 把 ARSCLib 改过的资源表 / 清单落进覆盖层。
     *
     * 两个条目都检查一遍，但**只 stage 真的变了的那个**：`resources.arsc` 常有几 MB，
     * 改个应用名却把整张表塞进覆盖层是白费磁盘 —— 而且会让「改了哪些东西」变得看不清。
     */
    private fun stageArscChanges(note: String?, want: String? = null): List<PatchRecord> {
        val files = ArscBridge.writeAndExtract(module, ArscBridge.ENTRIES, tmpDir("arsc"))
        val out = mutableListOf<PatchRecord>()

        for ((name, after) in files) {
            if (want != null && name != want) continue
            if (isUnchanged(name, after)) continue
            out += stageEntry(
                entryPath = name,
                after = after,
                kind = if (name.endsWith(".arsc")) {
                    PatchRecord.PatchKind.ARSC
                } else {
                    PatchRecord.PatchKind.MANIFEST
                },
                note = note,
            )
        }
        // 改到清单就重算元信息。放在这里而不是每个调用点：所有「改清单」的路径
        // （改名/版本/minSdk、换图标、以及以后的任何清单写入）都会经过这个方法，
        // 一处覆盖，不会漏掉新加的功能。
        if (out.any { it.target == MANIFEST_ENTRY }) recomputeMeta()
        return out
    }

    /**
     * 从当前清单状态重算元信息。
     *
     * 只重算清单相关的那几项（`copy`）：dex 统计与签名信息不会因为改清单而变化，
     * 没必要连着把整个包重扫一遍。
     */
    private fun recomputeMeta() {
        val manifest = runCatching { module.getAndroidManifest() }.getOrNull()
        meta = meta.copy(
            packageName = manifest?.getPackageName() ?: UNKNOWN,
            versionName = manifest?.getVersionName() ?: UNKNOWN,
            versionCode = manifest?.getVersionCode()?.toLong() ?: 0L,
            minSdk = manifest?.getMinSdkVersion() ?: 0,
            targetSdk = manifest?.getTargetSdkVersion() ?: 0,
            appLabel = readAppLabel(module, manifest),
            permissions = manifest?.getUsesPermissions()?.toList().orEmpty(),
            components = readComponents(manifest),
        )
    }

    /** 字节是否与原包里那份完全一致 —— 一致就没必要进覆盖层。 */
    private fun isUnchanged(entryPath: String, after: File): Boolean {
        val entry = zip.getEntry(entryPath) ?: return false
        if (entry.size != after.length()) return false
        return runCatching {
            zip.getInputStream(entry).use { it.readBytes() }.contentEquals(after.readBytes())
        }.getOrDefault(false)
    }

    override suspend fun dexSearch(query: DexQuery): List<DexHit> = withContext(Dispatchers.IO) {
        DexSearch.run(dexIndex(), query)
    }

    /**
     * 单类反编译成 Java。
     *
     * Java 视图是给人读的（smali 是给机器改的）：先在 Java 里看懂目标逻辑，
     * 再切到 smali 精确改。两者是同一份 dex 的两个视图，不是两份真相。
     */
    override suspend fun decompileToJava(className: String): String = withContext(Dispatchers.IO) {
        val descriptor = DexIndex.descriptor(className)
        jadx().decompile(descriptor, tmpDir("jadx"))?.let { return@withContext it }

        // null 有两种含义，必须分开说：类根本不在包里 vs 在包里但反编译不出来。
        // 报错信息要给下一步 —— 见 docs/04 的错误约定。
        if (dexIndex().dexOfClass(descriptor) == null) {
            throw NoSuchElementException("类不在本包内: $className")
        }
        throw IllegalStateException(
            "jadx 反编译不出这个类（多见于加固过的包或畸形字节码）: $className；可改用 smali 视图，它一定能给出结果",
        )
    }

    override suspend fun readSmali(className: String): String = withContext(Dispatchers.IO) {
        val file = SmaliBridge.disassembleClass(dexIndex(), className, tmpDir("smali"))
            ?: throw NoSuchElementException("类不在本包内: $className")
        file.readText()
    }

    override suspend fun readSmaliMethod(className: String, methodSig: String): String =
        withContext(Dispatchers.IO) {
            val smali = readSmali(className)
            SmaliBridge.extractMethod(smali, methodSig)
                ?: throw NoSuchElementException("类 $className 内找不到方法: $methodSig")
        }
    /**
     * 改一处 smali。
     *
     * 与 [replaceString] 的分工：改**字符串常量**用 replaceString（走 dexlib2 的常量池改写，
     * 只动那一个 dex、毫秒级）；改**逻辑**（指令、寄存器、跳转）才用这个，
     * 它要走「整 dex 反汇编 → 改文本 → 整 dex 汇编」的往返，慢得多但能改的东西多得多。
     *
     * [methodSig] 非空时只在该方法体内替换：同一条指令往往在许多方法里都出现，
     * 不限定范围很容易改到别处去。
     */
    override suspend fun patchSmali(
        className: String, methodSig: String?, pattern: String, replacement: String, regex: Boolean,
    ): PatchRecord = withContext(Dispatchers.IO) {
        val outcome = SmaliEditor.patchClass(
            index = dexIndex(),
            descriptor = DexIndex.descriptor(className),
            methodSig = methodSig,
            pattern = pattern,
            replacement = replacement,
            regex = regex,
            workDir = tmpDir("smali-edit"),
        )
        when (outcome) {
            is SmaliEditor.Outcome.Ok -> stageEntry(
                entryPath = outcome.dexName,
                after = outcome.dexFile,
                kind = PatchRecord.PatchKind.SMALI,
                note = "smali 改写：${methodSig ?: className}（命中 ${outcome.hits} 处）",
            )

            SmaliEditor.Outcome.NoSuchClass ->
                throw NoSuchElementException("类不在本包内: $className")

            SmaliEditor.Outcome.NoSuchMethod ->
                throw NoSuchElementException("类 $className 里找不到方法: $methodSig")

            SmaliEditor.Outcome.NotFound -> throw NoSuchElementException(
                "在 ${methodSig?.let { "方法 $it" } ?: "整个类 $className"} 里没找到要替换的内容: $pattern" +
                    "（只想改字符串常量的话，用 replaceString 更快）",
            )
        }
    }


    override suspend fun resources(type: String?, filter: String?): List<ResourceEntry> =
        withContext(Dispatchers.IO) {
            ArscBridge.list(module, type, filter, RESOURCE_LIST_LIMIT)
        }

    /**
     * 改一条字符串资源，如 `@string/app_name`。
     *
     * 与 [setManifestField] 的分工：应用名写在 `@string/app_name` 里时改这个；
     * 清单里写死了字面量时改那个。
     */
    override suspend fun setResource(resName: String, value: String): PatchRecord =
        withContext(Dispatchers.IO) {
            if (!ArscBridge.setString(module, resName, value)) {
                throw NoSuchElementException(
                    "资源表里没有 $resName —— 名字要写成 @string/app_name 这种形式（先用 resources() 看一眼有哪些）",
                )
            }
            stageArscChanges(note = "资源改写：$resName = $value", want = "resources.arsc").firstOrNull()
                ?: throw IllegalStateException("资源改了但没产出可用的 resources.arsc，这个包的表可能不是标准格式")
        }

    override suspend fun replaceString(from: String, to: String, regex: Boolean): List<PatchRecord> =
        // 正则走单独一条路：批量接口收的是字面量对，多条正则没法合并成一次扫描
        if (regex) replaceByRegex(from, to) else replaceStrings(listOf(StringReplacement(from, to)))

    /**
     * 批量替换。**关键在「只落地一次」**：
     *
     * - dex 层：每个 dex 只重建成对象树一次（不是每组替换重建一次）
     * - 资源层：所有替换都做完，才让 ARSCLib 序列化一次资源表
     *
     * 单条也走这条路 —— 两条路径语义完全一致，才不会出现「单条能用、批量行为不同」这种坑。
     */
    override suspend fun replaceStrings(pairs: List<StringReplacement>, scope: ReplaceScope): List<PatchRecord> =
        withContext(Dispatchers.IO) {
            if (pairs.isEmpty()) return@withContext emptyList()
            val out = mutableListOf<PatchRecord>()

            // ── dex 层 ──
            // 代价要知道：每个命中的 dex 都要重建成对象树再写出，65k 方法的 dex 是秒级。
            // 实测 200 组规则命中 5 个 dex 要 25 秒 —— 所以只改文案时应当传 scope = ARSC
            // 把这一整段跳过去（见 ReplaceScope 的说明）。
            if (scope != ReplaceScope.ARSC) {
                val index = dexIndex()
                for (dexName in index.names) {
                    // DexEditor 内部会先数一遍，不含命中的 dex 直接短路，不为它白建对象树
                    val tmp = File(tmpDir("dex"), dexName)
                    val result = DexEditor.replaceStrings(index, dexName, pairs, tmp) ?: continue
                    out += stageEntry(
                        entryPath = dexName,
                        after = result.file,
                        kind = PatchRecord.PatchKind.ENTRY_REPLACE,
                        note = "字符串替换 ${pairs.size} 组（命中 ${result.replaced} 处）",
                    )
                }
            }

            // ── 资源层 ──
            // 同一个文案可能硬编码在 dex 里，也可能是 strings.xml 的一条资源 ——
            // 用户搜一个词时不该关心它躺在哪一层。
            if (scope != ReplaceScope.DEX) {
                val arscHits = runCatching { ArscBridge.replaceStrings(module, pairs) }.getOrDefault(0)
                if (arscHits > 0) {
                    out += stageArscChanges(note = "资源字符串替换 ${pairs.size} 组（命中 $arscHits 处）")
                }
            }
            out
        }

    /** 正则替换：只有单个入口。多条正则合并扫描的收益不值得那份复杂度。 */
    private suspend fun replaceByRegex(pattern: String, to: String): List<PatchRecord> =
        withContext(Dispatchers.IO) {
            val rx = Regex(pattern)
            val index = dexIndex()
            val out = mutableListOf<PatchRecord>()
            for (dexName in index.names) {
                val tmp = File(tmpDir("dex"), dexName)
                val result = DexEditor.replaceString(index, dexName, pattern, to, rx, tmp) ?: continue
                out += stageEntry(
                    entryPath = dexName,
                    after = result.file,
                    kind = PatchRecord.PatchKind.ENTRY_REPLACE,
                    note = "正则替换：$pattern → $to（命中 ${result.replaced} 处）",
                )
            }

            val arscHits = runCatching { ArscBridge.replaceStringsRegex(module, rx, to) }.getOrDefault(0)
            if (arscHits > 0) {
                out += stageArscChanges(note = "资源正则替换：$pattern → $to（命中 $arscHits 处）")
            }
            out
        }
    /**
     * 改清单字段。
     *
     * **改包名要慎重**：它连带影响组件名、权限、provider authority，而且装上去就是一个
     * 全新的应用，不会覆盖原应用（也就意味着「改包名绕过签名校验」这条路不存在）。
     */
    override suspend fun setManifestField(field: ManifestField, value: String): PatchRecord =
        withContext(Dispatchers.IO) {
            if (!ArscBridge.setManifestField(module, field, value)) {
                throw IllegalStateException("改 $field 失败：清单可能不是标准格式，或这个值不合法（$value）")
            }
            stageArscChanges(note = "清单改写：$field = $value", want = "AndroidManifest.xml").firstOrNull()
                ?: throw IllegalStateException("清单改了但没产出可用的 AndroidManifest.xml")
        }

    override suspend fun replaceIcon(source: String, densities: List<String>?): List<PatchRecord> = todo("replaceIcon")

    override suspend fun iconTargets(): IconTargets = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) paths += entries.nextElement().name

        IconResolver.resolve(
            manifest = runCatching { module.getAndroidManifest() }.getOrNull(),
            table = runCatching { module.getTableBlock() }.getOrNull(),
            allPaths = paths,
            readXml = { path -> XmlBridge.decode(module, path) },
        )
    }

    /** 已规划、等调用方把图画完传回来的方案。 */
    private var pendingIconPlan: IconPlan? = null

    override suspend fun planIconReplace(): IconPlan = withContext(Dispatchers.IO) {
        val targets = iconTargets()

        // ① 包里已有位图图层（传统图标，或 adaptive 的位图前景/背景）→ 直接覆盖。
        //    注意「只有背景是位图、前景是矢量」的怪包也存在，所以三个都要看
        if (targets.isReplaceable || targets.background.isNotEmpty()) {
            val renders = buildList {
                targets.legacy.forEach { (d, path) ->
                    DENSITY_CANVAS[d]?.let {
                        add(IconRender("legacy_$d", path, d, it.legacy, it.legacy, IconRender.Role.LEGACY_ICON))
                    }
                }
                targets.foreground.forEach { (d, path) ->
                    DENSITY_CANVAS[d]?.let {
                        add(
                            IconRender(
                                "fg_$d", path, d, it.adaptive,
                                it.adaptive * SAFE_NUM / SAFE_DEN, IconRender.Role.ADAPTIVE_FOREGROUND,
                            ),
                        )
                    }
                }
                targets.background.forEach { (d, path) ->
                    DENSITY_CANVAS[d]?.let {
                        add(
                            IconRender(
                                "bg_$d", path, d, it.adaptive,
                                it.adaptive, IconRender.Role.ADAPTIVE_BACKGROUND,
                            ),
                        )
                    }
                }
            }
            return@withContext IconPlan(
                mode = IconPlan.Mode.OVERLAY,
                renders = renders,
                declaredIcon = targets.declaredIcon,
                notes = listOf("包里已有位图图层，直接覆盖"),
            ).also { pendingIconPlan = it }
        }

        // ② 只有矢量图 / 纯色 → 新建位图资源，并把清单的图标指向它。
        //
        // **为什么不改 adaptive 声明**：ARSCLib 对普通 xml 是「读时解析、写时回放原始字节」，
        // 改内存里的对象不会落到输出上（`getResXmlDocument` 甚至每次返回不同对象）。
        // 而清单走的是 `AndroidManifestBlock` —— module 自己的对象，改得动。
        // 所以换个更简单也更可靠的做法：把清单的图标指向新建的位图资源，
        // 原来那份 adaptive 声明留在包里、不再被引用。
        val renders = DENSITY_CANVAS.map { (d, canvas) ->
            IconRender(
                key = "icon_$d",
                entryPath = "res/mipmap-$d-v4/$NEW_ICON_BASE.png",
                density = d,
                canvasSize = canvas.legacy,
                contentSize = canvas.legacy,
                role = IconRender.Role.LEGACY_ICON,
            )
        }

        return@withContext IconPlan(
            mode = IconPlan.Mode.NEW_RESOURCES,
            renders = renders,
            declaredIcon = targets.declaredIcon,
            adaptiveXml = targets.adaptiveXml,
            newResourceBase = NEW_ICON_BASE,
            notes = listOf(
                // 两种情况要分开说：本来就有图标（只是不是位图），和压根没声明图标
                if (targets.declaredIcon == null) {
                    "清单里没有声明图标（可能由主题指定），所以会新建一个"
                } else {
                    "包里有图标声明 ${targets.declaredIcon}，但它不是位图（矢量图或纯色）"
                },
                "会新建 mipmap/$NEW_ICON_BASE（5 个密度），并把清单的 android:icon 指向它。" +
                    "代价是图标从 adaptive 变成传统位图 —— 这是最稳的做法，" +
                    "改 adaptive 声明那条路 ARSCLib 走不通",
            ),
        ).also { pendingIconPlan = it }
    }

    override suspend fun applyIconReplace(rendered: Map<String, ByteArray>): List<PatchRecord> =
        withContext(Dispatchers.IO) {
            val plan = pendingIconPlan
                ?: throw IllegalStateException("先调 planIconReplace() 拿方案，再把画好的图传回来")
            val out = mutableListOf<PatchRecord>()

            // ① 写图。走覆盖层：新资源模式下这属于「新增条目」，覆盖模式下是替换
            val written = mutableListOf<IconRender>()
            for (r in plan.renders) {
                val bytes = rendered[r.key] ?: continue
                out += writeEntry(r.entryPath, bytes.inputStream())
                written += r
            }
            if (written.isEmpty()) throw IllegalArgumentException("没有传回来任何图（key 对不上？）")

            // ② 新资源模式：建资源表条目 + 把清单的图标指过来
            if (plan.mode == IconPlan.Mode.NEW_RESOURCES) {
                val base = plan.newResourceBase ?: error("方案里缺新资源名")

                val iconId = ArscBridge.addMipmapResource(
                    module, base, written.associate { it.density to it.entryPath },
                )

                // 改清单的 android:icon 指向新资源。清单是 AndroidManifestBlock
                // （module 自己的对象），所以这条改得动 —— 普通 xml 改不动，见上面的说明
                val manifest = module.getAndroidManifest()
                    ?: error("这个包没有 AndroidManifest.xml，换不了图标")
                manifest.setIconResourceId(iconId)
                println("── 清单图标已指向 @mipmap/$base（id=0x${iconId.toString(16)}）")

                // 资源表与清单一并落盘（清单这条路径在 M1 就验证过）
                val files = ArscBridge.writeAndExtract(
                    module, setOf(IconPlan.ARSC_ENTRY, MANIFEST_ENTRY), tmpDir("icon"),
                )
                files[IconPlan.ARSC_ENTRY]?.let {
                    out += stageEntry(
                        IconPlan.ARSC_ENTRY, it, PatchRecord.PatchKind.ARSC,
                        note = "新建图标资源 mipmap/$base（${written.size} 个密度）",
                    )
                }
                files[MANIFEST_ENTRY]?.let {
                    out += stageEntry(
                        MANIFEST_ENTRY, it, PatchRecord.PatchKind.AXML,
                        note = "清单 android:icon 指向 @mipmap/$base",
                    )
                }
            }

            pendingIconPlan = null
            out
        }

    // ── XML 层 ────────────────────────────────────────────────

    override suspend fun readXml(path: String): String = withContext(Dispatchers.IO) {
        XmlBridge.decode(module, path)
            ?: throw NoSuchElementException(
                "读不了 $path：条目不存在，或它不是二进制 XML" +
                    "（普通文本文件用 readEntry 读）",
            )
    }

    override suspend fun patchXml(
        path: String,
        elementPath: String,
        attr: String,
        value: String,
    ): PatchRecord = withContext(Dispatchers.IO) {
        if (!XmlBridge.patchAttribute(module, path, elementPath, attr, value)) {
            throw NoSuchElementException(
                "在 $path 里找不到元素路径「$elementPath」" +
                    "（写法是 application/activity，第二个用 activity[1]）",
            )
        }

        val files = ArscBridge.writeAndExtract(module, setOf(path), tmpDir("axml"))
        val after = files[path]
            ?: throw IllegalStateException("改完了却拿不到新的 $path，这个条目可能不是标准二进制 XML")

        if (isUnchanged(path, after)) {
            throw IllegalStateException("$path 一个字节都没变：那个属性的值可能本来就等于「$value」")
        }

        stageEntry(
            entryPath = path,
            after = after,
            kind = PatchRecord.PatchKind.AXML,
            note = "改 xml 属性：$elementPath 的 $attr = $value",
        )
    }

    /**
     * 重打包：把工作区覆盖层叠回原包。
     *
     * 未改动的条目从原 zip 直接搬运原始压缩字节，所以代价基本只是"复制一遍"，
     * 而不是把整包重新压一遍。`incremental = false` 强制走全量重压 ——
     * 留给"某个包增量路径产出坏包"时的排查手段。
     */
    override suspend fun rebuild(incremental: Boolean): File = withContext(Dispatchers.IO) {
        val ws = workspaceRef?.takeIf { it.patchCount > 0 }
            ?: throw IllegalStateException("没有改动，无需重打包")

        state = WorkspaceState.REBUILDING
        _progress.value = BuildProgress(BuildProgress.Stage.BUILD, 0)
        val out = File(tmpDir("out"), "${meta.packageName}-unsigned.apk")
        try {
            val stats = ZipRebuilder.rebuild(
                source = apkFile,
                outFile = out,
                overlay = { ws.overlayFile(it) },
                deleted = ws.deletedEntries(),
                onProgress = { name -> _progress.value = BuildProgress(BuildProgress.Stage.BUILD, 0, name) },
                forceFull = !incremental,
            )
            lastBuilt = out
            state = WorkspaceState.REBUILT
            _progress.value = BuildProgress(BuildProgress.Stage.DONE, 100)
            println(
                "[rebuild] 搬运=${stats.rawCopied} 重压=${stats.recompressed} 删除=${stats.deleted} " +
                    "全量兜底=${stats.fellBackToFull} → ${out.length() / 1024}KB 用时见日志",
            )
            out
        } catch (t: Throwable) {
            state = WorkspaceState.FAILED
            throw t
        }
    }

    /**
     * 签名。默认用内置 keystore：首次自动生成，之后复用（指纹必须稳定，见 [Keystores]）。
     *
     * 必须在 [rebuild] 之后调用 —— 接口里 sign 没有输入参数，签的就是上一次打包的产物。
     */
    override suspend fun sign(config: SignConfig): File = withContext(Dispatchers.IO) {
        val input = lastBuilt ?: throw IllegalStateException("还没有打包产物，先调用 rebuild()")
        if (state != WorkspaceState.REBUILT && state != WorkspaceState.SIGNED) {
            throw IllegalStateException("当前状态 $state 不允许签名")
        }

        // v1 分两种情况处理：
        //  - minSdk >= 24：不需要它。apksig 只签 v2/v3（它也签不了 v1 —— 一生成清单就 NPE）
        //  - minSdk < 24：**必须**有它，否则 Android 5/6 装不上。既然 apksig 那条路断了，
        //    就自己写（[V1Signer]，规范很短，实现已被 Android 官方 apksigner 验证通过）
        //
        // **顺序不能反**：`V1Signer` 会重写整个 zip（签名文件要进包），
        // 而 apksig 加 v2/v3 是在 zip 上附加一个签名块、不动条目 ——
        // 反过来做的话，重写 zip 会把 apksig 刚加的签名块整块丢掉。
        val needV1 = apiLevel < 24
        val schemes = config.schemes.filter { it != 1 }.toSet()
        require(schemes.isNotEmpty()) { "至少要保留一种签名方案（v2 或 v3）" }

        _progress.value = BuildProgress(BuildProgress.Stage.SIGN, 0)
        val material = Keystores.loadOrCreate(keystoreDir)
        val out = File(tmpDir("signed"), "${meta.packageName}-signed.apk")
        try {
            val forV2 = if (needV1) {
                val mid = File(tmpDir("signed"), "${meta.packageName}-v1.apk")
                V1Signer.sign(input, mid, material.privateKey, material.certificates)
                mid
            } else {
                input
            }
            Signer.sign(
                input = forV2,
                output = out,
                privateKey = material.privateKey,
                certificates = material.certificates,
                // 报 24 而不是真实值：apksig 在 minSdk < 24 时会坚持「必须有 v1」，
                // 而它的 v1 路径是坏的。v1 已经由 V1Signer 写好了，这里只让它加 v2/v3。
                minSdk = apiLevel.coerceAtLeast(24),
                schemes = schemes,
            )
            lastSigned = out
            state = WorkspaceState.SIGNED
            _progress.value = BuildProgress(BuildProgress.Stage.DONE, 100)
            println(
                "[sign] 密钥${if (material.created) "新生成" else "复用"}" +
                    " subject=${material.certificates.first().subjectX500Principal.name}" +
                    " 方案=${schemes.sorted()} → ${out.length() / 1024}KB",
            )
            out
        } catch (t: Throwable) {
            state = WorkspaceState.FAILED
            throw t
        }
    }

    override suspend fun verify(apk: File): VerifyResult = withContext(Dispatchers.IO) { Signer.verify(apk) }

    /**
     * 装机。按 `Shizuku → Root → 系统安装器` 逐级降级。
     *
     * 系统安装器（弹界面让用户点）**永远可用**，所以降级链一定有终点，
     * 不会出现「三档都试完还是没反应」。最终用了哪一档由 [InstallResult.via] 如实带回来 ——
     * 静默降级到手动安装却报告"已静默安装"是最误导人的行为。
     */
    override suspend fun install(apk: File, via: InstallVia): InstallResult = withContext(Dispatchers.IO) {
        require(apk.isFile) { "安装包不存在: ${apk.absolutePath}" }

        val channel = installChannel
            ?: return@withContext InstallResult(
                ok = false,
                via = via,
                message = "当前没有接入装机通道：引擎层未注册 InstallChannel。" +
                    "在 App 里运行时会由平台层提供（Shizuku/Root/系统安装器）",
            )

        val current = state
        if (current != WorkspaceState.SIGNED && current != WorkspaceState.REBUILT) {
            return@withContext InstallResult(
                ok = false,
                via = via,
                message = "当前状态是 $current，还不能装机：先重打包并签名（改完没打包的包装上去就是旧的）",
            )
        }

        val available = runCatching { channel.available() }.getOrDefault(listOf(InstallVia.INTENT))
        // InstallVia 的声明顺序就是降级顺序：SHIZUKU → ROOT → INTENT
        val chain = InstallVia.entries
            .dropWhile { it != via }
            .filter { it in available || it == InstallVia.INTENT }
        if (chain.isEmpty()) {
            return@withContext InstallResult(
                ok = false,
                via = via,
                message = "$via 在当前设备上不可用（未授权或未安装对应服务），且没有可降级的通道",
            )
        }

        var last: InstallResult? = null
        for (v in chain) {
            val r = runCatching { channel.install(apk, v) }.getOrElse { t ->
                InstallResult(false, v, t.message ?: t::class.java.simpleName)
            }
            if (r.ok) {
                state = WorkspaceState.INSTALLED
                println("[install] ${apk.name} 经 $v 装机成功")
                return@withContext r
            }
            println("[install] $v 失败：${r.message} → 降级")
            last = r
        }
        last ?: InstallResult(false, via, "装机链路走完了但没有拿到结果")
    }


    override suspend fun patches(): List<PatchRecord> = workspaceRef?.patches.orEmpty()

    override suspend fun revert(patchId: String) {
        val ws = workspaceRef ?: throw NoSuchElementException("当前没有可回退的改动")
        if (!ws.revert(patchId)) throw NoSuchElementException("找不到这条改动记录: $patchId")
        state = if (ws.patchCount == 0) WorkspaceState.UNPACKED else WorkspaceState.DIRTY
        // 回退清单改动后 meta 也要回到旧值，否则界面显示的还是改动后的名字
        recomputeMeta()
        // 回退同样要让 dex 索引失效 —— 与「改动后失效」一样的道理：
        // 索引缓存着覆盖层快照，回退后覆盖层变了，不失效就会继续读到回退前的内容
        // （症状：用户点了「回退」，再看那个 dex 却是新值 —— 会以为回退没生效）
        dexIndexRef?.close()
        dexIndexRef = null
    }

    companion object {

        /**
         * 打开一个 APK 并解析出 [ApkMeta]。
         *
         * 全程只读：不动原文件，所以不需要拷贝工作区（M1 起要改包时才会建 workspace）。
         * 单个 APK 的解析动作全部在这里完成，耗时集中在签名校验与 dex 头读取。
         */
        suspend fun open(
            workspaceId: String,
            apkFile: File,
            keystoreDir: File,
            installChannel: InstallChannel? = null,
        ): ApkProjectImpl = withContext(Dispatchers.IO) {
            require(apkFile.isFile) { "不是文件: ${apkFile.absolutePath}" }

            val zip = ZipFile(apkFile)
            val module = try {
                ApkModule.loadApkFile(apkFile)
            } catch (t: Throwable) {
                zip.close()
                throw t
            }

            val manifest = runCatching { module.getAndroidManifest() }.getOrNull()
            val dexStats = readDexStats(zip)
            val signatures = readSignatures(apkFile)

            val meta = ApkMeta(
                sourcePath = apkFile.absolutePath,
                packageName = manifest?.getPackageName() ?: UNKNOWN,
                versionName = manifest?.getVersionName() ?: UNKNOWN,
                versionCode = manifest?.getVersionCode()?.toLong() ?: 0L,
                minSdk = manifest?.getMinSdkVersion() ?: 0,
                targetSdk = manifest?.getTargetSdkVersion() ?: 0,
                appLabel = readAppLabel(module, manifest),
                permissions = manifest?.getUsesPermissions()?.toList().orEmpty(),
                components = readComponents(manifest),
                signatures = signatures,
                dexStats = dexStats,
                sizeBytes = apkFile.length(),
                isSplit = runCatching { manifest?.isSplit == true }.getOrDefault(false),
            )

            ApkProjectImpl(workspaceId, apkFile, module, zip, meta, keystoreDir, installChannel)
        }

        private const val UNKNOWN = "—"

        /** 资源列表一次最多给这么多条：正常 App 上万条资源，全量倒给 UI 既慢又没用 */
        private const val RESOURCE_LIST_LIMIT = 500

        /** classes.dex → 1, classes2.dex → 2 …，用于 dex 数字序排序 */
        private val DEX_INDEX = Regex("""classes(\d*)\.dex""")

        // ── DEX 统计：只读每个 dex 的前 112 字节 ──────────────────

        internal fun readDexStats(zip: ZipFile): List<DexStat> {
            val names = zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".dex") && !it.contains('/') }
                // classes.dex 是第 1 个、classes2.dex 是第 2 个 —— 按数字序，不是字典序
                // （字典序会把 classes10.dex 排到 classes2.dex 前面）
                .sortedBy { name ->
                    // "classes.dex" 的捕获组是空串，等价于 1
                    val n = DEX_INDEX.find(name)?.groupValues?.get(1).orEmpty()
                    if (n.isEmpty()) 1 else n.toIntOrNull() ?: Int.MAX_VALUE
                }
                .toList()

            return names.map { name ->
                val entry = zip.getEntry(name)!!
                val stats = runCatching {
                    zip.getInputStream(entry).use { DexHeader.read(it) }
                }.getOrDefault(DexHeader.Stats.INVALID)

                DexStat(
                    name = name,
                    classes = stats.classes,
                    methods = stats.methods,
                    strings = stats.strings,
                    sizeBytes = entry.size.coerceAtLeast(0),
                )
            }
        }

        // ── 签名：apksig 全量校验（v1/v2/v3 一起）─────────────────

        internal fun readSignatures(apkFile: File): List<SignatureInfo> {
            val result = runCatching {
                ApkVerifier.Builder(apkFile).build().verify()
            }.getOrNull() ?: return emptyList()

            val signers = result.signerCertificates ?: return emptyList()
            if (signers.isEmpty()) return emptyList()

            // 哪些方案验过了：用每个 signer 的证书覆盖范围判断过于绕，
            // 这里按 apksig 的结果字段直读（v1/v2/v3 各自的 successful 标记）。
            val schemes = buildList {
                if (result.isVerifiedUsingV1Scheme) add(1)
                if (result.isVerifiedUsingV2Scheme) add(2)
                if (result.isVerifiedUsingV3Scheme) add(3)
            }
            if (schemes.isEmpty()) return emptyList()

            val cert = signers.first()
            return listOf(
                SignatureInfo(
                    scheme = schemes.max(),
                    subject = cert.subjectDN.name,
                    issuer = cert.issuerDN.name,
                    md5 = cert.digest("MD5"),
                    sha1 = cert.digest("SHA-1"),
                    sha256 = cert.digest("SHA-256"),
                    isDebug = cert.subjectDN.name.contains("Android Debug", ignoreCase = true),
                )
            )
        }

        private fun java.security.cert.X509Certificate.digest(algorithm: String): String =
            MessageDigest.getInstance(algorithm).digest(encoded)
                .joinToString("") { "%02x".format(it) }

        // ── Manifest：应用名 / 组件 ───────────────────────────────

        /**
         * 应用名。
         *
         * 绝大多数 APK 的 android:label 是 @string 引用而不是字面量，所以拿到引用 id 后
         * 要去资源表里解。解不出来（资源表畸形 / 加固）就退回展示引用 id，不假装拿到了字符串。
         */
        private fun readAppLabel(module: ApkModule, manifest: AndroidManifestBlock?): String {
            if (manifest == null) return UNKNOWN

            runCatching { manifest.getApplicationLabelString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            val ref = runCatching { manifest.getApplicationLabelReference() }.getOrNull()
                ?: return UNKNOWN

            runCatching {
                module.getTableBlock()
                    ?.getResource(ref)
                    ?.getStringValues()
                    ?.asSequence()
                    ?.firstOrNull { it.isNotBlank() }
            }.getOrNull()?.let { return it }

            return "资源引用 #%08x".format(ref)
        }

        /**
         * 组件列表。
         *
         * M0 只读导出的 exported 属性；intent-filter 的判定（哪些组件是入口）留到 M0.5，
         * 因为那要遍历子元素，且 xml 里还有 <intent-filter> 的隐式匹配规则。
         */
        internal fun readComponents(manifest: AndroidManifestBlock?): List<ComponentInfo> {
            if (manifest == null) return emptyList()

            val kinds = listOf(
                "activity" to ComponentInfo.Kind.ACTIVITY,
                "service" to ComponentInfo.Kind.SERVICE,
                "receiver" to ComponentInfo.Kind.RECEIVER,
                "provider" to ComponentInfo.Kind.PROVIDER,
            )

            return kinds.flatMap { (tag, kind) ->
                runCatching { manifest.listApplicationElementsByTag(tag) }
                    .getOrNull()
                    .orEmpty()
                    .mapNotNull { el -> el.toComponent(kind) }
            }
        }

        private fun ResXmlElement.toComponent(kind: ComponentInfo.Kind): ComponentInfo? {
            val name = attrValue("name") ?: return null
            return ComponentInfo(
                kind = kind,
                name = name,
                exported = attrValue("exported")?.toBooleanStrictOrNull() ?: false,
                hasIntentFilter = false,   // TODO(M0.5): 遍历子元素判定入口组件
            )
        }

        private fun ResXmlElement.attrValue(localName: String): String? {
            val it = getAttributes()
            while (it.hasNext()) {
                val a: ResXmlAttribute = it.next()
                if (a.getName() == localName) {
                    runCatching { return a.getValueAsString() }
                    return null
                }
            }
            return null
        }
    }
}
