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
interface ApkProject {
    val id: String
    val state: WorkspaceState
    val meta: ApkMeta
    val patchCount: Int

    /** 耗时阶段的进度（解包/汇编/打包/对齐/签名），UI 用来画进度条。 */
    val progress: Flow<BuildProgress>

    fun close(keepArtifacts: Boolean)

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
    suspend fun setManifestField(field: ManifestField, value: String): PatchRecord
    suspend fun replaceIcon(source: String, densities: List<String>? = null): List<PatchRecord>

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

enum class ManifestField { APP_LABEL, PACKAGE_NAME, VERSION_NAME, VERSION_CODE, DEBUGGABLE }

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
