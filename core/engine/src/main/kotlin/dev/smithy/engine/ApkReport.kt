package dev.smithy.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把分析结果渲染成 Markdown。
 *
 * **为什么放在引擎层**：纯字符串处理、不依赖 Android，因此可以单测 ——
 * 而「导出的报告少了哪一段」是最容易在重构里悄悄坏掉、又没人立刻发现的东西。
 *
 * 报告里**不出现任何第三方工具名**，只描述这台工具自己看到的事实。
 */
object ApkReport {

    private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun toMarkdown(
        meta: ApkMeta,
        entryCount: Int,
        sourceName: String,
        now: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("# ${meta.appLabel} · 分析报告")
        appendLine()
        appendLine("> 包名 `${meta.packageName}` ｜ 版本 ${meta.versionName} (${meta.versionCode})")
        appendLine("> 来源 `$sourceName` ｜ 生成时间 ${TIMESTAMP.format(Date(now))}")
        appendLine()

        // ── 1 基本信息 ──
        appendLine("## 1. 基本信息")
        appendLine()
        appendLine("| 项 | 值 |")
        appendLine("|---|---|")
        appendLine("| 应用名 | ${meta.appLabel} |")
        appendLine("| 包名 | `${meta.packageName}` |")
        appendLine("| 版本名 | ${meta.versionName} |")
        appendLine("| 版本码 | ${meta.versionCode} |")
        appendLine("| minSdk / targetSdk | ${meta.minSdk} / ${meta.targetSdk} |")
        appendLine("| 包大小 | ${humanSize(meta.sizeBytes)} |")
        appendLine("| 条目数 | $entryCount |")
        appendLine("| 形态 | ${if (meta.isSplit) "split APK" else "单包"} |")
        meta.packerGuess?.let {
            appendLine("| 加固特征 | ${it.name}（置信度 ${"%.0f".format(it.confidence * 100)}%） |")
        }
        appendLine()

        // ── 2 签名 ──
        appendLine("## 2. 签名")
        appendLine()
        if (meta.signatures.isEmpty()) {
            appendLine("未检测到有效签名。无签名的包无法安装，除非设备已关闭签名校验。")
        } else {
            appendLine("| 方案 | 类型 | 主体 | 颁发者 |")
            appendLine("|---|---|---|---|")
            for (s in meta.signatures) {
                appendLine(
                    "| v${s.scheme} | ${if (s.isDebug) "Debug" else "正式"} | " +
                        "${escape(s.subject)} | ${escape(s.issuer)} |",
                )
            }
            appendLine()
            appendLine("| 方案 | SHA-256 | SHA-1 | MD5 |")
            appendLine("|---|---|---|---|")
            for (s in meta.signatures) {
                appendLine("| v${s.scheme} | `${s.sha256}` | `${s.sha1}` | `${s.md5}` |")
            }
        }
        appendLine()

        // ── 3 权限 ──
        appendLine("## 3. 权限（${meta.permissions.size}）")
        appendLine()
        if (meta.permissions.isEmpty()) {
            appendLine("未声明任何权限。")
        } else {
            // 按前缀分组更好读：android.permission 是系统的，其余是自定义/第三方
            val (system, other) = meta.permissions.partition { it.startsWith("android.permission.") }
            if (system.isNotEmpty()) {
                appendLine("### 系统权限（${system.size}）")
                appendLine()
                system.sorted().forEach { appendLine("- `${it.removePrefix("android.permission.")}`") }
                appendLine()
            }
            if (other.isNotEmpty()) {
                appendLine("### 其他权限（${other.size}）")
                appendLine()
                other.sorted().forEach { appendLine("- `$it`") }
                appendLine()
            }
        }

        // ── 4 组件 ──
        appendLine("## 4. 组件（${meta.components.size}）")
        appendLine()
        if (meta.components.isEmpty()) {
            appendLine("未解析到组件。")
        } else {
            meta.components.groupBy { it.kind }.forEach { (kind, list) ->
                appendLine("### ${kindLabel(kind)}（${list.size}）")
                appendLine()
                appendLine("| 名称 | 导出 |")
                appendLine("|---|---|")
                list.sortedBy { it.name }.forEach { c ->
                    val exported = if (c.exported) "**是**" else "否"
                    appendLine("| `${c.name}` | $exported |")
                }
                appendLine()
            }
        }

        // ── 5 DEX ──
        appendLine("## 5. DEX 构成（${meta.dexStats.size}）")
        appendLine()
        if (meta.dexStats.isEmpty()) {
            appendLine("包里没有 dex —— 这不是一个正常的安装包。")
        } else {
            appendLine("合计 **${meta.dexStats.sumOf { it.classes }}** 个类 / **${meta.dexStats.sumOf { it.methods }}** 个方法。")
            appendLine()
            appendLine("| 文件 | 类 | 方法 | 字符串 | 大小 |")
            appendLine("|---|---|---|---|---|")
            meta.dexStats.forEach { d ->
                val near = if (d.methods >= 65_536 * 0.95) " ⚠" else ""
                appendLine("| ${d.name}$near | ${d.classes} | ${d.methods} | ${d.strings} | ${humanSize(d.sizeBytes)} |")
            }
            val nearLimit = meta.dexStats.filter { it.methods >= 65_536 * 0.95 }
            if (nearLimit.isNotEmpty()) {
                appendLine()
                appendLine(
                    "⚠ ${nearLimit.joinToString("、") { it.name }} 接近 65536 方法上限。" +
                        "再往这些 dex 里加方法会被打包工具拒绝，需要先做 dex 拆分。",
                )
            }
        }

        appendLine()
        appendLine("---")
        appendLine()
        appendLine("*本报告由 Smithy 在设备上离线生成。请只对你自己有权分析的包使用。*")
    }

    private fun kindLabel(kind: ComponentInfo.Kind) = when (kind) {
        ComponentInfo.Kind.ACTIVITY -> "Activity"
        ComponentInfo.Kind.SERVICE -> "Service"
        ComponentInfo.Kind.RECEIVER -> "Receiver"
        ComponentInfo.Kind.PROVIDER -> "Provider"
    }

    /** Markdown 表格里的 `|` 会把单元格切开，必须转义。 */
    private fun escape(s: String) = s.replace("|", "\\|").replace("\n", " ")

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
