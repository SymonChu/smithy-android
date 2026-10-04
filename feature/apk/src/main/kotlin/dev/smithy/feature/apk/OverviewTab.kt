package dev.smithy.feature.apk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.smithy.engine.ApkMeta
import dev.smithy.engine.ComponentInfo
import dev.smithy.engine.DexStat

/** 「概览」标签：打开一个包之后立刻能看到的全部信息。 */
@Composable
internal fun OverviewTab(
    meta: ApkMeta,
    sourceName: String,
    entryCount: Int,
    onExportReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderCard(sourceName, entryCount, meta)
        SignatureCard(meta)
        DexCard(meta.dexStats)
        PermissionsCard(meta.permissions)
        ComponentsCard(meta.components)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onExportReport, modifier = Modifier.fillMaxWidth()) {
            Text("导出报告（Markdown）")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HeaderCard(sourceName: String, entryCount: Int, m: ApkMeta) {
    SectionCard("基本信息") {
        Text(m.appLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Mono(m.packageName)
        Spacer(Modifier.height(10.dp))
        KV("版本", "${m.versionName}  (${m.versionCode})")
        KV("SDK", "min ${m.minSdk}  ·  target ${m.targetSdk}")
        KV("大小", "%.1f MB".format(m.sizeBytes / 1024.0 / 1024.0))
        KV("条目", "$entryCount 个")
        KV("文件", sourceName)
        if (m.isSplit) KV("形态", "split APK")
    }
}

@Composable
private fun SignatureCard(m: ApkMeta) {
    SectionCard("签名") {
        if (m.signatures.isEmpty()) {
            Text(
                "未检测到有效签名（可能是无签名包，或用了非常规签名方案）",
                style = MaterialTheme.typography.bodySmall,
            )
            return@SectionCard
        }
        m.signatures.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "v${s.scheme}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (s.isDebug) "Debug 签名" else "正式签名",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            KV("主体", s.subject)
            if (s.issuer != s.subject) KV("颁发者", s.issuer)
            KV("SHA-256", s.sha256)
            KV("SHA-1", s.sha1)
        }
    }
}

@Composable
private fun DexCard(stats: List<DexStat>) {
    SectionCard("DEX 构成（${stats.size} 个）") {
        if (stats.isEmpty()) {
            Text("没有 dex —— 这不是正常的安装包", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Text(
            "合计 ${stats.sumOf { it.classes }} 个类 / ${stats.sumOf { it.methods }} 个方法",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        stats.forEach { d ->
            val near = d.methods >= (65_536 * 0.95).toInt()
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Mono(d.name, Modifier.weight(1f))
                    Text(
                        "%.1fMB".format(d.sizeBytes / 1024.0 / 1024.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "类 ${d.classes} · 方法 ${d.methods} · 字符串 ${d.strings}" +
                        if (near) "   ⚠ 接近 65536 上限" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (near) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionsCard(perms: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard("权限（${perms.size}）") {
        if (perms.isEmpty()) {
            Text("未声明任何权限", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val shown = if (expanded) perms else perms.take(6)
        shown.forEach {
            Mono(it.substringAfterLast('.'), Modifier.padding(vertical = 2.dp))
        }
        if (perms.size > 6) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) "收起" else "展开其余 ${perms.size - 6} 个",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun ComponentsCard(components: List<ComponentInfo>) {
    SectionCard("组件（${components.size}）") {
        if (components.isEmpty()) {
            Text("未解析到组件", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        components.groupBy { it.kind }.forEach { (kind, list) ->
            Text(
                "${kindLabel(kind)}  ${list.size}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            list.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Mono(c.name.substringAfterLast('.'), Modifier.weight(1f))
                    if (c.exported) {
                        Text(
                            "exported",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun kindLabel(kind: ComponentInfo.Kind) = when (kind) {
    ComponentInfo.Kind.ACTIVITY -> "Activity"
    ComponentInfo.Kind.SERVICE -> "Service"
    ComponentInfo.Kind.RECEIVER -> "Receiver"
    ComponentInfo.Kind.PROVIDER -> "Provider"
}
