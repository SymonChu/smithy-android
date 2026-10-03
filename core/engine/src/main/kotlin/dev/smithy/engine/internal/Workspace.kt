package dev.smithy.engine.internal

import dev.smithy.engine.PatchOrigin
import dev.smithy.engine.PatchRecord
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 工作区的**改动覆盖层**。
 *
 * 设计取舍：不预先解包整个 APK。改动过的条目按包内路径写进 `overlay/`，原包保持只读。
 * 于是「改一个字符串」的代价 = 一个 dex 的体积，而不是整包复制一份
 * （26MB 的包复制一遍在手机上要好几秒，而且白占一份存储）。
 *
 * `backup/` 里存每次改动前的内容，`revert(patchId)` 靠它精确回退到那一步之前。
 */
internal class Workspace(private val root: File, private val workspaceId: String) {

    val overlayDir = File(root, "overlay").apply { mkdirs() }
    private val backupDir = File(root, "backup").apply { mkdirs() }
    private val deletedFile = File(root, "deleted.txt")

    private val _patches = mutableListOf<PatchRecord>()

    private val deleted: MutableSet<String> = runCatching {
        if (deletedFile.isFile) deletedFile.readLines().filter { it.isNotBlank() }.toMutableSet()
        else mutableSetOf()
    }.getOrDefault(mutableSetOf())

    val patches: List<PatchRecord> get() = synchronized(_patches) { _patches.toList() }
    val patchCount: Int get() = synchronized(_patches) { _patches.size }

    // ── 覆盖层读取 ────────────────────────────────────────────

    fun overlayFile(entryPath: String): File? = File(overlayDir, entryPath).takeIf { it.isFile }

    fun isDeleted(entryPath: String): Boolean = synchronized(deleted) { entryPath in deleted }

    /** 有覆盖内容的条目（包内相对路径）。 */
    fun changedEntries(): Set<String> {
        val base = overlayDir.absolutePath
        return overlayDir.walkTopDown()
            .filter { it.isFile }
            .map { it.absolutePath.removePrefix(base).trimStart(File.separatorChar).replace(File.separatorChar, '/') }
            .toSet()
    }

    fun deletedEntries(): Set<String> = synchronized(deleted) { deleted.toSet() }

    // ── 记录改动 ──────────────────────────────────────────────

    /**
     * 记录一次条目改动。
     *
     * @param before 改动前的内容；null 表示该条目原本不存在（新增）
     */
    fun stage(
        entryPath: String,
        before: File?,
        after: File,
        kind: PatchRecord.PatchKind,
        origin: PatchOrigin,
        note: String?,
    ): PatchRecord {
        val target = File(overlayDir, entryPath)
        target.parentFile?.mkdirs()
        after.copyTo(target, overwrite = true)

        val backup = if (before != null && before.isFile) {
            File(backupDir, UUID.randomUUID().toString()).apply { before.copyTo(this, overwrite = true) }
        } else {
            null
        }

        val record = PatchRecord(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            ts = System.currentTimeMillis(),
            kind = kind,
            target = entryPath,
            beforeHash = before?.let { sha256(it) },
            afterHash = sha256(target),
            backupPath = backup?.absolutePath,
            origin = origin,
            note = note,
        )
        synchronized(_patches) { _patches += record }
        return record
    }

    /** 标记条目为删除（rebuild 时跳过）。 */
    fun markDeleted(entryPath: String): PatchRecord {
        synchronized(deleted) {
            deleted += entryPath
            deletedFile.writeText(deleted.joinToString("\n"))
        }
        return PatchRecord(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            ts = System.currentTimeMillis(),
            kind = PatchRecord.PatchKind.ENTRY_DEL,
            target = entryPath,
            beforeHash = null,
            afterHash = null,
            backupPath = null,
            origin = PatchOrigin.User,
            note = "删除条目",
        ).also { r -> synchronized(_patches) { _patches += r } }
    }

    /**
     * 精确回退一次改动：把条目恢复成这条记录之前的样子。
     *
     * 注意：只回退目标条目。若同一 entry 之后还有更新的改动，调用方应先回退那些
     * （UI 按倒序回退，`patch.list` 的顺序即回退顺序）。
     */
    fun revert(patchId: String): Boolean {
        val p = synchronized(_patches) { _patches.find { it.id == patchId } } ?: return false

        if (p.kind == PatchRecord.PatchKind.ENTRY_DEL) {
            synchronized(deleted) {
                deleted -= p.target
                deletedFile.writeText(deleted.joinToString("\n"))
            }
            synchronized(_patches) { _patches.remove(p) }
            return true
        }

        val target = File(overlayDir, p.target)
        val backup = p.backupPath?.let(::File)
        if (backup != null && backup.isFile) {
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
        } else {
            target.delete()          // 该条目是新增的，撤销即删除
        }
        synchronized(_patches) { _patches.remove(p) }
        return true
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
}
