package dev.smithy.feature.apk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyCard
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySectionTitle
import dev.smithy.design.SmithySpacing
import dev.smithy.design.smithyEmphasis
import dev.smithy.engine.ApkHealth
import dev.smithy.engine.CheckLevel
import dev.smithy.engine.ProductReport

/**
 * 工作台里的两张「结论卡」：体检（打开时）与产物验证（打包签名后）。
 *
 * ## 为什么是卡而不是一行提示
 *
 * 这两件事都是**多条结论**（加固/签名/结构/ABI/模块特征、清单/资源表/签名/对齐/装机），
 * 压成一行就得靠用户自己拼；而它们恰好决定「要不要动手」「能不能装」。
 * 所以给它们卡片 + 逐条 + 每条的等级色，扫一眼就知道哪条是拦路的。
 *
 * 结论行的语气只用三档：**OK = 主色对勾、WARN = 中性圆点、BAD = 错色警告**。
 * 不给 WARN 用黄色：这套界面里主色已经足够安静，多一个颜色只会和错误色抢注意力。
 */
@Composable
internal fun HealthCard(health: ApkHealth, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SmithySectionTitle("体检")
        Spacer(Modifier.height(SmithySpacing.gap))
        SmithyCard(Modifier.padding(horizontal = SmithySpacing.gutter)) {
            Column(Modifier.padding(SmithySpacing.cardPadding)) {
                // 结论先给：它是这一整张卡存在的理由
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (health.level) {
                            CheckLevel.OK -> SmithyIcons.Check
                            CheckLevel.WARN -> SmithyIcons.Info
                            CheckLevel.BAD -> SmithyIcons.Warning
                        },
                        contentDescription = null,
                        modifier = Modifier.size(SmithySpacing.iconSize),
                        tint = when (health.level) {
                            CheckLevel.BAD -> MaterialTheme.colorScheme.error
                            CheckLevel.OK -> MaterialTheme.colorScheme.primary
                            CheckLevel.WARN -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(SmithySpacing.iconGap))
                    Text(
                        smithyEmphasis(health.headline),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (health.level == CheckLevel.BAD) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                health.detail?.let {
                    Spacer(Modifier.height(SmithySpacing.gap))
                    Text(
                        smithyEmphasis(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(SmithySpacing.gap + 2.dp))
                health.items.forEach { item -> InfoLine(item.group, item.text, item.level) }

                health.next?.let {
                    Spacer(Modifier.height(SmithySpacing.gap))
                    Text(
                        it,
                        style = SmithyRowMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 产物验证卡。
 *
 * **不通过的项排在最前**：一条 BAD 会把整张卡的结论改掉（不该装），
 * 而用户先看到的应该是它。原先按固定顺序列（清单/资源表/签名/对齐），
 * 出问题时最要紧的那条可能被压在最后一行。
 */
@Composable
internal fun ProductCard(report: ProductReport, modifier: Modifier = Modifier) {
    val ordered = report.checks.sortedByDescending { it.level == CheckLevel.BAD }
    Column(modifier.fillMaxWidth()) {
        SmithySectionTitle("验证", trailing = if (report.canInstall) "产物自洽" else "有问题")
        Spacer(Modifier.height(SmithySpacing.gap))
        SmithyCard(Modifier.padding(horizontal = SmithySpacing.gutter)) {
            Column(Modifier.padding(SmithySpacing.cardPadding)) {
                ordered.forEach { c -> InfoLine(c.label, c.detail, c.level) }
            }
        }
    }
}

/** 产物本身：文件名 / 大小 / SHA-256（指纹对得上才说明传过去的和这里是同一个包）。 */
@Composable
internal fun ArtifactCard(
    label: String,
    path: String,
    sizeBytes: Long,
    sha256: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SmithySectionTitle("产物")
        Spacer(Modifier.height(SmithySpacing.gap))
        SmithyCard(Modifier.padding(horizontal = SmithySpacing.gutter)) {
            Column(Modifier.padding(SmithySpacing.cardPadding)) {
                InfoLine("文件", path.substringAfterLast('/'), CheckLevel.OK, showIcon = false)
                InfoLine("大小", "${"%.1f".format(sizeBytes / 1024.0 / 1024.0)} MB", CheckLevel.OK, showIcon = false)
                InfoLine("指纹", "${sha256.take(16)}…（$label）", CheckLevel.OK, showIcon = false)
            }
        }
    }
}

/** 一条「标签 + 内容」的结论行。标签固定宽度，多行内容也首行对齐。 */
@Composable
private fun InfoLine(
    label: String,
    text: String,
    level: CheckLevel,
    showIcon: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 26.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showIcon) {
            Icon(
                imageVector = level.icon(),
                contentDescription = null,
                modifier = Modifier.size(16.dp).padding(top = 2.dp),
                tint = level.tint(),
            )
            Spacer(Modifier.width(SmithySpacing.gap))
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Text(
            label,
            style = SmithyRowMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Spacer(Modifier.width(SmithySpacing.gap))
        Text(
            smithyEmphasis(text),
            style = MaterialTheme.typography.bodySmall,
            color = if (level == CheckLevel.BAD) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CheckLevel.tint() = when (this) {
    CheckLevel.OK -> MaterialTheme.colorScheme.primary
    CheckLevel.WARN -> MaterialTheme.colorScheme.onSurfaceVariant
    CheckLevel.BAD -> MaterialTheme.colorScheme.error
}

private fun CheckLevel.icon(): ImageVector = when (this) {
    CheckLevel.OK -> SmithyIcons.Check
    CheckLevel.WARN -> SmithyIcons.Info
    CheckLevel.BAD -> SmithyIcons.Warning
}
