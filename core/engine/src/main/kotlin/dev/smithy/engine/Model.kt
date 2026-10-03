package dev.smithy.engine

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────
// 领域模型（只读快照，由引擎在 open() 时填充）
// 注意：这些类型是 UI 与 AI 共用的契约，改动会影响两侧，谨慎演化。
// ─────────────────────────────────────────────────────────────

@Serializable
data class ApkMeta(
    val sourcePath: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val appLabel: String,
    val permissions: List<String>,
    val components: List<ComponentInfo>,
    val signatures: List<SignatureInfo>,
    val dexStats: List<DexStat>,
    val sizeBytes: Long,
    val isSplit: Boolean,
    val packerGuess: PackerGuess? = null,
)

@Serializable
data class ComponentInfo(
    val kind: Kind,          // ACTIVITY / SERVICE / RECEIVER / PROVIDER
    val name: String,
    val exported: Boolean,
    val hasIntentFilter: Boolean,
) { enum class Kind { ACTIVITY, SERVICE, RECEIVER, PROVIDER } }

@Serializable
data class SignatureInfo(
    val scheme: Int,         // 1 / 2 / 3 / 4
    val subject: String,
    val issuer: String,
    val md5: String,
    val sha1: String,
    val sha256: String,
    val isDebug: Boolean,
)

@Serializable
data class DexStat(
    val name: String,
    val classes: Int,
    val methods: Int,        // > 65536 需预警
    val strings: Int,
    val sizeBytes: Long,
)

@Serializable
data class PackerGuess(val name: String, val confidence: Float, val evidence: List<String>)

/** APK 内部的一个条目（文件）。path 一律用包内相对路径，如 "res/layout/main.xml"。 */
@Serializable
data class ApkEntry(
    val path: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
)

// ─────────────────────────────────────────────────────────────
// 改动记录：撤销 / AI 可解释性 / 配方重放 三者都挂在它上面
// ─────────────────────────────────────────────────────────────

@Serializable
data class PatchRecord(
    val id: String,
    val workspaceId: String,
    val ts: Long,
    val kind: PatchKind,
    val target: String,          // 如 "smali/com/a/B.smali#foo()V"
    val beforeHash: String?,
    val afterHash: String?,
    val backupPath: String?,     // 原文件备份，供 revert
    val origin: PatchOrigin,     // 谁改的：用户 or AI（含 sessionId/toolCallId）
    val note: String?,           // AI 给出的修改理由，展示在 diff 里
) {
    enum class PatchKind { SMALI, ARSC, AXML, MANIFEST, ENTRY_ADD, ENTRY_DEL, ENTRY_REPLACE }
}

@Serializable
sealed interface PatchOrigin {
    data object User : PatchOrigin
    data class Ai(val sessionId: String, val toolCallId: String) : PatchOrigin
}

// ─────────────────────────────────────────────────────────────
// 工作区状态机 —— UI 与 AI 都读它决定"现在能做什么"
// ─────────────────────────────────────────────────────────────

enum class WorkspaceState {
    IDLE,        // 刚创建，尚未解包
    UNPACKED,    // 已解包，可读
    DIRTY,       // 有未打包的改动 → 才允许 rebuild
    REBUILDING,  // 正在回编（不可并发操作）
    REBUILT,     // 有 unsigned.apk
    SIGNED,      // 有 signed.apk，可安装
    INSTALLED,   // 已装到设备
    FAILED,      // 上一步失败，附原因
    ;

    /** 该状态下允许执行的动作，供 UI 置灰与 AI 约束共用。 */
    fun canRebuild() = this == DIRTY
    fun canSign() = this == REBUILT || this == SIGNED
    fun canInstall() = this == SIGNED || this == REBUILT
}
