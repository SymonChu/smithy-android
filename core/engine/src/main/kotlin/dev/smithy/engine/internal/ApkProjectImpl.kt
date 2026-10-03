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
import dev.smithy.engine.DexStat
import dev.smithy.engine.InstallResult
import dev.smithy.engine.InstallVia
import dev.smithy.engine.ManifestField
import dev.smithy.engine.PatchRecord
import dev.smithy.engine.PatchOrigin
import dev.smithy.engine.ResourceEntry
import dev.smithy.engine.SignConfig
import dev.smithy.engine.SignatureInfo
import dev.smithy.engine.VerifyResult
import dev.smithy.engine.WorkspaceState
import dev.smithy.engine.DexHit
import dev.smithy.engine.DexQuery
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
    override val meta: ApkMeta,
) : ApkProject {

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

    private fun workspace(): Workspace = workspaceRef ?: synchronized(this) {
        workspaceRef ?: Workspace(tmpDir("ws"), id).also { workspaceRef = it }
    }

    @Volatile
    private var dexIndexRef: DexIndex? = null

    @Volatile
    private var tmpRoot: File? = null

    /** dex 的 opcodes 按 minSdk 定：生成的 dex 必须能跑在目标设备上 */
    private val apiLevel: Int get() = meta.minSdk.takeIf { it in 21..99 } ?: 21

    private fun dexIndex(): DexIndex = dexIndexRef ?: synchronized(this) {
        dexIndexRef ?: DexIndex(apkFile, apiLevel).also { dexIndexRef = it }
    }

    private fun tmpDir(prefix: String): File {
        val root = tmpRoot ?: synchronized(this) {
            tmpRoot ?: Files.createTempDirectory("smithy-ws-").toFile().also { tmpRoot = it }
        }
        return Files.createTempDirectory(root.toPath(), prefix).toFile()
    }

    override fun close(keepArtifacts: Boolean) {
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
        return record
    }

    /** 从原包里解出条目内容，作为改动的基线。条目不存在时返回 null（表示是新增）。 */
    private fun extractOriginal(entryPath: String): File? {
        val entry = zip.getEntry(entryPath) ?: return null
        val out = File(tmpDir("orig"), entryPath.substringAfterLast('/'))
        zip.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
        return out
    }
    override suspend fun dexSearch(query: DexQuery): List<DexHit> = withContext(Dispatchers.IO) {
        DexSearch.run(dexIndex(), query)
    }

    override suspend fun decompileToJava(className: String): String = todo("decompileToJava")

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
    override suspend fun patchSmali(
        className: String, methodSig: String?, pattern: String, replacement: String, regex: Boolean,
    ): PatchRecord = todo("patchSmali")

    override suspend fun resources(type: String?, filter: String?): List<ResourceEntry> = todo("resources")
    override suspend fun setResource(resName: String, value: String): PatchRecord = todo("setResource")
    /**
     * 批量替换 dex 里的字符串常量。
     *
     * 只对「确实含目标字符串」的 dex 动手：rewrite 会重建整个 dex 的对象树，
     * 一个包十几个 dex 全量重写既慢又费内存（见 [DexEditor] 的说明）。
     */
    override suspend fun replaceString(from: String, to: String, regex: Boolean): List<PatchRecord> =
        withContext(Dispatchers.IO) {
            val rx = if (regex) Regex(from) else null
            val index = dexIndex()
            val out = mutableListOf<PatchRecord>()

            for (dexName in index.names) {
                // replaceString 内部会先数一遍，不含命中的 dex 直接短路，
                // 不会为它白建对象树（见 DexEditor.replaceString）
                val tmp = File(tmpDir("dex"), dexName)
                val result = DexEditor.replaceString(index, dexName, from, to, rx, tmp) ?: continue
                out += stageEntry(
                    entryPath = dexName,
                    after = result.file,
                    kind = PatchRecord.PatchKind.ENTRY_REPLACE,
                    note = "字符串替换：$from → $to（命中 ${result.replaced} 处）",
                )
            }
            out
        }
    override suspend fun setManifestField(field: ManifestField, value: String): PatchRecord = todo("setManifestField")
    override suspend fun replaceIcon(source: String, densities: List<String>?): List<PatchRecord> = todo("replaceIcon")

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

    override suspend fun sign(config: SignConfig): File = todo("sign")
    override suspend fun verify(apk: File): VerifyResult = todo("verify")
    override suspend fun install(apk: File, via: InstallVia): InstallResult = todo("install")

    override suspend fun patches(): List<PatchRecord> = workspaceRef?.patches.orEmpty()

    override suspend fun revert(patchId: String) {
        val ws = workspaceRef ?: throw NoSuchElementException("当前没有可回退的改动")
        if (!ws.revert(patchId)) throw NoSuchElementException("找不到这条改动记录: $patchId")
        state = if (ws.patchCount == 0) WorkspaceState.UNPACKED else WorkspaceState.DIRTY
    }

    companion object {

        /**
         * 打开一个 APK 并解析出 [ApkMeta]。
         *
         * 全程只读：不动原文件，所以不需要拷贝工作区（M1 起要改包时才会建 workspace）。
         * 单个 APK 的解析动作全部在这里完成，耗时集中在签名校验与 dex 头读取。
         */
        suspend fun open(workspaceId: String, apkFile: File): ApkProjectImpl = withContext(Dispatchers.IO) {
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

            ApkProjectImpl(workspaceId, apkFile, module, zip, meta)
        }

        private const val UNKNOWN = "—"

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
