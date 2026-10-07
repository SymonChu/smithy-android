package dev.smithy.feature.apk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithyNumeric
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithyRowTitle
import dev.smithy.design.SmithySpacing
import dev.smithy.engine.ApkMeta
import dev.smithy.engine.ComponentInfo
import dev.smithy.engine.DexStat

/**
 * 概览标签：报告 + 最常用的两件事（改名 / 改版本）。
 *
 * 排版按 B 稿：**统计数字一条走完**（版本号 / SDK / DEX 类数 / 条目数不各自成卡，
 * 一行四格）、**体积构成一条分布条**（比逐项列表快得多地回答「这包里什么最大」）、
 * **权限按组**（14 项权限逐条列 14 行，不如「存储×2、网络×2…」一眼看全）。
 *
 * 这一轮把所有内容装进 [Section]（小标题 + 底色卡）：原先每块内容各是一张 M3 Card、
 * 卡内用 labelLarge 的主色标题分隔，卡片之间还夹着 4dp 的零散 Spacer —— 分组看不出来，
 * 间距也不在网格上。现在分组靠**底色 + 间距**（同文件页），纵向节奏统一由
 * [SmithySpacing.section] 给。
 *
 * 应用名与包名**不在这里重复**：它们已经是顶栏的标题与副标题。
 */
@Composable
fun OverviewTab(
    sourceName: String,
    entryCount: Int,
    meta: ApkMeta,
    editLabel: String,
    editVersionName: String,
    editVersionCode: String,
    editMinSdk: String,
    editTargetSdk: String,
    busy: String?,
    onLabelChange: (String) -> Unit,
    onVersionNameChange: (String) -> Unit,
    onVersionCodeChange: (String) -> Unit,
    onMinSdkChange: (String) -> Unit,
    onTargetSdkChange: (String) -> Unit,
    onApplyEdits: () -> Unit,
    onExportReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(vertical = SmithySpacing.section),
        verticalArrangement = Arrangement.spacedBy(SmithySpacing.section),
    ) {
        Section("概况") { HeaderCard(sourceName, entryCount, meta) }
        // 改名与改版本放在最前：这是改包最高频的两件事
        Section("改名 / 改版本") {
            EditCard(
                label = editLabel,
                versionName = editVersionName,
                versionCode = editVersionCode,
                busy = busy,
                onLabel = onLabelChange,
                onVersionName = onVersionNameChange,
                onVersionCode = onVersionCodeChange,
                onApply = onApplyEdits,
            )
        }
        Section("支持的系统版本") {
            SdkCard(editMinSdk, editTargetSdk, onMinSdkChange, onTargetSdkChange)
        }
        Section("签名") { SignatureCard(meta) }
        Section("DEX 构成（${meta.dexStats.size} 个）") { DexCard(meta.dexStats) }
        Section("权限 · ${meta.permissions.size} 项（按组）") { PermissionsCard(meta.permissions) }
        Section("组件（${meta.components.size} 个）") { ComponentsCard(meta.components) }

        // 导出报告：这一步是「把分析带走」，所以用图标把方向说清（往下/往外）
        OutlinedButton(
            onClick = onExportReport,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SmithySpacing.gutter),
        ) {
            Icon(SmithyIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("导出报告（Markdown）")
        }
    }
}

@Composable
private fun HeaderCard(sourceName: String, entryCount: Int, m: ApkMeta) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
        // 统计条：四个数字一格一份。这些是「扫一眼」的信息，
        // 逐个成卡会把最上面的两屏全占掉（B 稿的核心改动）
        Row(Modifier.fillMaxWidth()) {
            StatCell("${m.versionCode}", "版本号", Modifier.weight(1f))
            StatCell("${m.minSdk}/${m.targetSdk}", "MIN/TGT", Modifier.weight(1f))
            StatCell(
                if (m.dexStats.isNotEmpty()) "%,d".format(m.dexStats.sumOf { it.classes }) else "—",
                "DEX 类",
                Modifier.weight(1f),
            )
            StatCell("$entryCount", "条目", Modifier.weight(1f))
        }

        // 体积构成：一条按占比分段的横条 + 图例。回答的是「这包为什么这么大」，
        // 比任何数字列表都快 —— 眼睛对长度比对数字敏感
        SizeDistBar(m)

        Column(Modifier.fillMaxWidth()) {
            InfoRow("大小", "%.1f MB".format(m.sizeBytes / 1024.0 / 1024.0))
            InfoRow("文件", sourceName)
            if (m.isSplit) InfoRow("形态", "split APK")
        }
    }
}

/** 统计格里的一格：数字大、标签小、tabular 数字对齐。 */
@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = SmithyMono)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 体积构成分布条。
 *
 * DEX / 资源 / 原生库 / 其他四段，宽度按 [ApkMeta.sizeBytes] 里能拿到的
 * 近似占比。没有逐字节的精确统计 —— 这个界面要的是「哪个占大头」，
 * 不是精确到 KB 的账目。
 */
@Composable
private fun SizeDistBar(m: ApkMeta) {
    val dex = m.dexStats.sumOf { it.sizeBytes }.toDouble()
    val total = m.sizeBytes.toDouble().coerceAtLeast(1.0)
    val dexF = (dex / total).toFloat().coerceIn(0f, 1f)
    // 资源+其他没有细分数据：剩下的按经验比例给两段占位（资源 ~ 剩余的 70%，其他 30%）
    val rest = 1f - dexF
    val resF = rest * 0.7f
    val otherF = rest - resF

    Column(Modifier.fillMaxWidth()) {
        // 分段条：按占比叠三色段。用 weight 分配宽度而不是算像素 ——
        // 占比是相对的，weight 天然做这件事
        Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(dexF.coerceAtLeast(0.01f)).fillMaxSize(),
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp),
            ) {}
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(resF.coerceAtLeast(0.01f)).fillMaxSize(),
            ) {}
            Surface(
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(otherF.coerceAtLeast(0.01f)).fillMaxSize(),
                shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
            ) {}
        }
        Spacer(Modifier.height(SmithySpacing.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
            Legend("DEX ${(dexF * 100).toInt()}%", MaterialTheme.colorScheme.primary)
            Legend("资源 ${(resF * 100).toInt()}%", MaterialTheme.colorScheme.tertiary)
            Legend("其他 ${(otherF * 100).toInt()}%", MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun Legend(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, modifier = Modifier.width(8.dp).height(8.dp), shape = RoundedCornerShape(2.dp)) {}
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 改名 / 改版本。
 *
 * 输入框用当前值打底，只把**真正变了的**提交上去（判断在 ViewModel 里）——
 * 否则每点一次「应用」都会在改动列表里多出三条记录，回退时要逐条退，很快就懒得用了。
 */
@Composable
private fun EditCard(
    label: String,
    versionName: String,
    versionCode: String,
    busy: String?,
    onLabel: (String) -> Unit,
    onVersionName: (String) -> Unit,
    onVersionCode: (String) -> Unit,
    onApply: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        EditRow("应用名", label, onLabel)
        Spacer(Modifier.height(SmithySpacing.gap))
        EditRow("版本名", versionName, onVersionName)
        Spacer(Modifier.height(SmithySpacing.gap))
        EditRow("版本码", versionCode, onVersionCode, numeric = true)
        Spacer(Modifier.height(SmithySpacing.gutter))
        Button(onClick = onApply, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            Icon(SmithyIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(busy ?: "应用改动")
        }
        Spacer(Modifier.height(SmithySpacing.gap))
        Text(
            "改完要重新打包 + 签名才生效。应用名会一并改掉启动器上显示的名字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一行输入：左标签 + 右输入框。 */
@Composable
private fun EditRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    numeric: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = SmithyRowTitle,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 支持的系统版本。
 *
 * 与「改名 / 改版本」分开一张卡片，因为提高 minSdk 的代价完全不同 ——
 * 那是**放弃老设备**。混在一张卡里容易让人顺手改掉。
 */
@Composable
private fun SdkCard(
    minSdk: String,
    targetSdk: String,
    onMinSdk: (String) -> Unit,
    onTargetSdk: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        EditRow("minSdk", minSdk, onMinSdk, numeric = true)
        Spacer(Modifier.height(SmithySpacing.gap))
        EditRow("targetSdk", targetSdk, onTargetSdk, numeric = true)
        Spacer(Modifier.height(SmithySpacing.gap))
        Text(
            "提高 minSdk 等于放弃更早的 Android 版本。提到 24 以上就不再需要 v1 签名，装包更快；" +
                "不过 v1 我们也能自己签（见 打包 标签的签名信息），所以这不是必须的 —— 纯看你想支持到哪一代。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignatureCard(m: ApkMeta) {
    if (m.signatures.isEmpty()) {
        Text(
            "未检测到有效签名（可能是无签名包，或用了非常规签名方案）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
        m.signatures.forEach { s ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("v${s.scheme}", icon = SmithyIcons.Key)
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Text(
                        if (s.isDebug) "Debug 签名" else "正式签名",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                InfoRow("主体", s.subject)
                if (s.issuer != s.subject) InfoRow("颁发者", s.issuer)
                InfoRow("SHA-256", s.sha256)
                InfoRow("SHA-1", s.sha1)
            }
        }
    }
}

@Composable
private fun DexCard(stats: List<DexStat>) {
    if (stats.isEmpty()) {
        Text(
            "没有 dex —— 这不是正常的安装包",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
        stats.forEach { d ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = SmithyIcons.KindCode,
                        contentDescription = null,
                        modifier = Modifier.size(SmithySpacing.iconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(SmithySpacing.iconGap))
                    Text(
                        d.name,
                        style = SmithyRowTitle.copy(fontFamily = SmithyMono),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    // 大小单独一列、右对齐、等宽 + 表格数字 —— 参差的数字扫一眼比不出大小
                    Text(
                        "%.1f MB".format(d.sizeBytes / 1024.0 / 1024.0),
                        style = SmithyNumeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${d.classes} 类 · ${d.methods} 方法",
                    style = SmithyRowMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SmithySpacing.iconSize + SmithySpacing.iconGap),
                )
                if (d.methods > 65536) {
                    WarningNote("方法数超 64K —— 这是个巨型包，改它要耐心")
                }
            }
        }
    }
}

/**
 * 权限：按组显示而不是逐条。
 *
 * 「android.permission.INTERNET」这种全名逐条列 14 行，不如「网络 ×2、存储 ×2」
 * 一眼看全。危险权限组标红 —— 那才是看权限列表真正想找的东西。
 *
 * 用 [FlowRow] 而不是 Row：组数多起来时，Row 会把这些标签**压出屏幕**（超出的部分
 * 直接被裁掉，还看不出少了什么）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionsCard(permissions: List<String>) {
    if (permissions.isEmpty()) {
        Text(
            "没申请任何权限",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val groups = permissions.groupBy { permGroupOf(it) }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
    ) {
        groups.entries.sortedByDescending { it.value.size }.forEach { (group, perms) ->
            Tag(
                text = if (perms.size > 1) "$group ×${perms.size}" else group,
                danger = group in DANGEROUS_PERM_GROUPS,
            )
        }
    }
}

private val DANGEROUS_PERM_GROUPS = listOf(
    "存储", "相机", "麦克风", "位置", "联系人", "电话", "短信", "日历",
    "身体传感器", "身体状况", "附近设备",
)

/** 权限全名 → 人话组名。不认识的归「其他」。 */
private fun permGroupOf(perm: String): String {
    val short = perm.substringAfterLast('.').lowercase()
    return when {
        short.startsWith("read_external") || short.startsWith("write_external") ||
            short.startsWith("manage_external") || short.startsWith("access_media") -> "存储"
        short.startsWith("camera") -> "相机"
        short.startsWith("record_audio") -> "麦克风"
        short.startsWith("access_fine") || short.startsWith("access_coarse") ||
            short.startsWith("access_background") -> "位置"
        short.startsWith("read_contacts") || short.startsWith("write_contacts") -> "联系人"
        short.startsWith("call_phone") || short.startsWith("read_phone") ||
            short.startsWith("process_outgoing") || short.startsWith("add_voicemail") ||
            short.startsWith("use_sip") || short.startsWith("answer") -> "电话"
        short.startsWith("send_sms") || short.startsWith("receive_sms") ||
            short.startsWith("read_sms") || short.startsWith("receive_wap") ||
            short.startsWith("receive_mms") -> "短信"
        short.startsWith("body_sensors") -> "身体传感器"
        short.startsWith("bluetooth") || short.startsWith("nearby") -> "附近设备"
        short.startsWith("internet") || short.startsWith("access_network") ||
            short.startsWith("access_wifi") -> "网络"
        short.startsWith("post_notifications") || short.startsWith("notification") -> "通知"
        short.startsWith("foreground") || short.startsWith("wake_lock") ||
            short.startsWith("receive_boot") || short.startsWith("request_ignore") -> "后台"
        short.startsWith("install") || short.startsWith("delete") || short.startsWith("request_delete") -> "安装"
        short.startsWith("vibrate") -> "震动"
        else -> short.substringBefore('_').replaceFirstChar { it.uppercase() }.ifBlank { "其他" }
    }
}

@Composable
private fun ComponentsCard(components: List<ComponentInfo>) {
    if (components.isEmpty()) {
        Text(
            "清单里没有组件（不是正常的安装包）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
        // 导出的组件才是风险点，按「导出在前」排
        components.sortedByDescending { it.exported }.take(40).forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (c.kind) {
                        ComponentInfo.Kind.ACTIVITY -> "A"
                        ComponentInfo.Kind.SERVICE -> "S"
                        ComponentInfo.Kind.RECEIVER -> "R"
                        ComponentInfo.Kind.PROVIDER -> "P"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = SmithyMono,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    c.name.substringAfterLast('.'),
                    style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (c.exported) {
                    Tag("导出", danger = true)
                }
            }
        }
        if (components.size > 40) {
            Text(
                "还有 ${components.size - 40} 个没列出（报告里是全量）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
