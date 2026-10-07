package dev.smithy.feature.apk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyNumeric
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithyRowTitle
import dev.smithy.design.SmithySectionTitle
import dev.smithy.design.SmithySpacing
import dev.smithy.engine.PatchRecord
import dev.smithy.engine.WorkspaceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「改动」标签。
 *
 * 这里要讲清一件容易误解的事：**代码视图读的是原包，改动只在工作区覆盖层里**。
 * 不说清楚的话，用户改完字符串回到代码标签，会发现「怎么还是旧的」，
 * 然后以为工具坏了 —— 实际上改动要重打包才合并进包。
 *
 * 排版：说明收进一张卡（不再是一张卡 + 一堆满宽分割线），工作区状态做成
 * **只读标签**（原先是一行正文，扫不出来）；改动行给行首类型图标 + 动作药丸，
 * 时间走等宽数字列。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PatchesTab(
    state: WorkbenchUiState,
    onRevert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Section("说明", Modifier.padding(top = SmithySpacing.section)) {
            Text(
                "所有改动都记在这里，原包一个字节都没动。点「重打包」才会合并成新包；" +
                    "代码标签看到的是原包内容，所以在打包之前那里不会显示你的改动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SmithySpacing.gap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "工作区状态",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(SmithySpacing.gap))
                Tag(state.workspaceState.label(), icon = SmithyIcons.Rule)
            }
        }

        Spacer(Modifier.height(SmithySpacing.section))

        if (state.patches.isEmpty()) {
            SmithyEmptyState(
                icon = SmithyIcons.Patches,
                title = "还没有改动",
                hint = "改名、换文案、删条目都会记在这里，每条都能单独回退",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        SmithySectionTitle("改动记录（${state.patches.size}）")
        Spacer(Modifier.height(SmithySpacing.gap))

        // 最新的在最上面，回退也按这个顺序（同一 entry 连续改多次时，先退后改的那次）
        val ordered = state.patches.reversed()
        LazyColumn(Modifier.fillMaxSize()) {
            items(ordered.size) { i ->
                val p = ordered[i]
                Row(
                    Modifier.fillMaxWidth()
                        .animateItem()
                        .defaultMinSize(minHeight = SmithySpacing.rowHeight)
                        .padding(
                            horizontal = SmithySpacing.rowHorizontal,
                            vertical = SmithySpacing.rowVertical,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = p.kind.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(SmithySpacing.iconGap))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${p.kind.label()}  ${p.target}",
                            style = SmithyRowTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        p.note?.let {
                            Text(
                                it,
                                style = SmithyRowMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 时间是「这一条是什么时候改的」，走等宽数字列：一列时间对得齐才好扫
                        Text(
                            timeFormat.format(Date(p.ts)),
                            style = SmithyNumeric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Pill("回退", { onRevert(p.id) }, icon = SmithyIcons.Undo)
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** 改动类型 → 图标：一眼看出这条动的是代码、资源表还是清单。 */
private fun PatchRecord.PatchKind.icon(): ImageVector = when (this) {
    PatchRecord.PatchKind.SMALI -> SmithyIcons.KindCode
    PatchRecord.PatchKind.ARSC -> SmithyIcons.Resources
    PatchRecord.PatchKind.AXML, PatchRecord.PatchKind.MANIFEST -> SmithyIcons.Rule
    PatchRecord.PatchKind.ENTRY_ADD -> SmithyIcons.Plus
    PatchRecord.PatchKind.ENTRY_DEL -> SmithyIcons.Delete
    PatchRecord.PatchKind.ENTRY_REPLACE -> SmithyIcons.Rename
}

private fun PatchRecord.PatchKind.label() = when (this) {
    PatchRecord.PatchKind.SMALI -> "smali"
    PatchRecord.PatchKind.ARSC -> "资源表"
    PatchRecord.PatchKind.AXML -> "清单 xml"
    PatchRecord.PatchKind.MANIFEST -> "清单"
    PatchRecord.PatchKind.ENTRY_ADD -> "新增"
    PatchRecord.PatchKind.ENTRY_DEL -> "删除"
    PatchRecord.PatchKind.ENTRY_REPLACE -> "替换条目"
}

private fun WorkspaceState.label() = when (this) {
    WorkspaceState.IDLE -> "未打开"
    WorkspaceState.UNPACKED -> "已就绪（还没改）"
    WorkspaceState.DIRTY -> "有未打包的改动"
    WorkspaceState.REBUILDING -> "正在打包"
    WorkspaceState.REBUILT -> "已打包（未签名）"
    WorkspaceState.SIGNED -> "已签名（可安装）"
    WorkspaceState.INSTALLED -> "已安装"
    WorkspaceState.FAILED -> "上一步失败"
}
