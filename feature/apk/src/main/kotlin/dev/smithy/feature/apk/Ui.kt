package dev.smithy.feature.apk

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyCard
import dev.smithy.design.SmithyDialogTitle
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySectionTitle
import dev.smithy.design.SmithySpacing
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.fs.FileKind

/**
 * 工作台（apk / 模块）各标签页共用的小零件。
 *
 * 这一层是 `core:design` 和六个标签页之间的中间层：色板 / 字阶 / 圆角 / 间距 / 动效 /
 * 触感这些 token 由设计系统给，而「工作台里一块内容长什么样」—— 区块、药丸、消息条、
 * 对话框、行首图标 —— 在这里定一次，页面里不再各写一份。
 *
 * 对齐的参照是**文件页**（`feature/files`）：同一套行解剖（行首 [SmithySpacing.iconSize]
 * 图标 + 主副文案 + 右侧等宽数字列）、同一套药丸（[Pill]）、同一套对话框形状
 * （extraLarge 圆角 + 图标化标题 + 破坏性操作填色按钮）。
 *
 * 和文件页那几件同名零件（`ToolbarPill` / `MessageBar` / `ConfirmDialog` …）是**刻意重复**的：
 * 它们在文件页里是 private，两个 feature 模块之间又没有依赖 —— 为了共用这几个几十行的
 * 表现零件去引一条模块依赖，代价比重复大。
 *
 * **已经进设计系统的那几件直接用那一份，不在这里抄**：对话框标题（`SmithyDialogTitle`）、
 * 类型 → 图标/颜色（`FileKind.icon` / `FileKind.tint()`）—— 后者尤其不能抄，同一个类型在
 * 文件页和工作台上必须是同一张图、同一个颜色。
 */

// ═══════════════════════════════════════════════════════════════════════════
// 区块与行
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 一个区块：小标题 + 一张底色卡。
 *
 * 取代「分割线 + 加粗小标题」的老做法：**分组靠底色和间距**，不靠横线。卡片底色用
 * `surfaceContainerLow`（[SmithyCard] 默认），在明暗两套主题下都成立 —— 描边在暗色下发灰、
 * 投影暗色下看不见。
 *
 * [SmithySectionTitle] 自带 [SmithySpacing.gutter] 的左右留白，所以卡片在这里补同样的
 * 留白；调用方只负责区块之间的纵向间距（`Arrangement.spacedBy(SmithySpacing.section)`）。
 */
@Composable
internal fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SmithySectionTitle(title, trailing = trailing)
        Spacer(Modifier.height(SmithySpacing.gap))
        SmithyCard(Modifier.padding(horizontal = SmithySpacing.gutter)) {
            Column(Modifier.padding(SmithySpacing.cardPadding), content = content)
        }
    }
}

/**
 * 一行「标签：值」。
 *
 * 值是等宽的：包里这一类的值全是路径、大小、哈希 —— 等宽让它们能上下对齐着扫。
 * 标签固定 [LABEL_WIDTH]，右列的起点因此每行都相同。
 */
@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Text(
            value,
            style = SmithyRowMeta.copy(fontFamily = SmithyMono),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private val LABEL_WIDTH = 64.dp

/** 等宽文本（路径、类名、方法签名、哈希）。颜色默认跟随内容色。 */
@Composable
internal fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = SmithyRowMeta,
) {
    Text(text, style = style.copy(fontFamily = SmithyMono), color = color, modifier = modifier)
}

// ═══════════════════════════════════════════════════════════════════════════
// 药丸与标签
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 工具条上的药丸（筛选项、切换项、轻量动作）。
 *
 * [active] 用填色而不是「加粗 + 主色」：加粗会让同一个名字选中前后宽度不同，旁边的药丸
 * 会跟着挪；填色不会。
 *
 * [enabled] = false 时**不震动也不动**，只用 [SmithyHaptics.warn] 回一句「现在不行」——
 * 灰掉的按钮点下去毫无反应，是最容易让人以为界面坏了的一种。
 *
 * @param danger 破坏性动作（删除、卸载）：文字与图标走 error 色。
 */
@Composable
internal fun Pill(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val haptics = rememberSmithyHaptics()
    val container by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = SmithyMotion.state(),
        label = "pillContainer",
    )
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = {
            if (!enabled) {
                haptics.warn()
                return@Surface
            }
            haptics.tap()
            onClick()
        },
        color = if (enabled) container else container.copy(alpha = 0.5f),
        shape = RoundedCornerShape(99.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = content)
                Spacer(Modifier.width(5.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

/**
 * 只读的小标签（权限组、工作区状态、条目的「二进制」）。
 *
 * 和 [Pill] 的区别是**不可点** —— 没有 `onClick` 的可点外观会让人去点它。
 */
@Composable
internal fun Tag(text: String, danger: Boolean = false, icon: ImageVector? = null) {
    val content = if (danger) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = if (danger) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(99.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = content)
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

/**
 * 搜索 / 过滤框。
 *
 * 圆角药丸 + 无描边 + 底色 `surfaceContainerHighest`：方角描边的 OutlinedTextField
 * 摆在列表上方一眼就是「安卓示例工程」。表单里的输入框（改名、改版本、批量替换）仍用
 * OutlinedTextField —— 那些是「要填的东西」，形状语言本来就该和「要找的东西」不同。
 */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(placeholder) },
        shape = RoundedCornerShape(99.dp),
        leadingIcon = {
            Icon(
                imageVector = SmithyIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                SmithyIconButton(
                    icon = SmithyIcons.ClearAll,
                    contentDescription = "清空",
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(32.dp),
                )
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 消息与进度
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 消息条（成功 / 失败）。
 *
 * 两者都带**图标**：原先只差底色，而浅红和浅灰在户外几乎一样 —— 图标是那一秒内唯一
 * 能分辨出差别的信号。
 */
@Composable
internal fun MessageBar(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier.padding(horizontal = SmithySpacing.gap, vertical = 4.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.barVertical + 4.dp, vertical = SmithySpacing.barVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) SmithyIcons.Warning else SmithyIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Spacer(Modifier.width(SmithySpacing.gap))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 忙碌行：转圈 + 一句话。
 *
 * 只有文字的话，几秒的解包 / 打包看起来就是卡死；只有转圈又不知道在做什么。
 * （文件名页的 `ZipHeader` / 骨架屏是同一套判断。）
 */
@Composable
internal fun BusyRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.6.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(SmithySpacing.gap))
        Text(text, style = SmithyRowMeta, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 卡片里的一句警告：图标 + error 色文字。
 *
 * 比整条 `errorContainer` 的横幅轻，适合一条之内说得完的提醒（方法数超 64K、
 * 刷入需要 root）。**必须带图标**：只改文字颜色的话，暗色主题下和正文几乎分不出来。
 */
@Composable
internal fun WarningNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = SmithyIcons.Warning,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(SmithySpacing.gap))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一整条警告横幅（模块结构问题、看不到设备模块那一类）。
 *
 * 用 `errorContainer` 铺底 + 图标：这一条说的是「你看到的东西不完整 / 这个包装上去
 * 也不会生效」，比卡片里的一行注重要显眼一档。
 */
@Composable
internal fun WarnBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical + 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = SmithyIcons.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(SmithySpacing.gap))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 对话框
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 确认对话框。
 *
 * 破坏性动作的按钮**写清动作**（「删除」「重启」）而不是「确定」—— 「确定」是那种点完
 * 之后想不起来自己确认了什么的东西；并且用**填色**按钮：文字按钮和「取消」长得一样，
 * 而这两个按钮点错的代价并不对称。
 *
 * 标题用设计系统的 [SmithyDialogTitle]（图标 + 文字）：对话框是「突然盖住整屏」的东西，
 * 一个图标能让人在半秒内认出这是哪一类操作。
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            SmithyDialogTitle(
                icon = icon ?: if (destructive) SmithyIcons.Warning else SmithyIcons.Info,
                text = title,
                tint = if (destructive) MaterialTheme.colorScheme.error else null,
            )
        },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 单输入框对话框（改一条资源、新建条目、改权限那种）。 */
@Composable
internal fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector = SmithyIcons.Rename,
    fieldLabel: String? = null,
) {
    // remember 带 initial 这个 key：同一个对话框位置换成另一条内容时（列表里连着改两条），
    // 不该把上一条的草稿带过来
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { SmithyDialogTitle(icon = icon, text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = fieldLabel?.let { { Text(it) } },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 行首图标：类型 → 形状
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 条目路径（含目录语义）→ 类型。
 *
 * 列表里判类型都走它，免得每个页面自己切扩展名、自己拼 `FileKind.of(name, false)`。
 * 「这个类型长什么样」不在这里 —— 那在**设计系统**里（`core:design` 的
 * `FileKind.icon` / `FileKind.tint()`），文件页和工作台共用同一套映射：
 * 同一个类型在两个页面上必须是同一张图、同一个颜色。
 */
internal fun kindOf(path: String, isDir: Boolean = false): FileKind =
    FileKind.of(path.substringAfterLast('/'), isDir)
