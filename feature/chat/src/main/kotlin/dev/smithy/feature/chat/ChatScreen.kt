package dev.smithy.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyEmptyState
import dev.smithy.design.smithyEmphasis
import dev.smithy.design.SmithyDialogTitle
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyNumeric
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.rememberSmithyHaptics
import dev.smithy.engine.CheckLevel
import dev.smithy.toolkit.Skill
import dev.smithy.toolkit.Skills
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 对话界面。
 *
 * **无状态**：数据与状态全在 [ChatViewModel] 里，这里只负责画和转发事件。
 * 这样「确认条会不会卡住」「停止能不能真停下」这类问题只可能出在一处，界面层不掺和状态机。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    workspaceName: String?,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    /** 点了一张技能卡：由 VM 决定是走固定流程还是给一条引导。 */
    onRunSkill: (Skill) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var showAllSkills by remember { mutableStateOf(false) }

    // 新内容进来就滚到底：AI 在流式输出，用户不该自己去追
    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.size - 1)
    }

    // **不要**在这里再挂 `.imePadding()`：主 Activity 的 Scaffold 已经在 content Box
    // 统一处理了键盘 inset（见 MainActivity 里 consumeWindowInsets + imePadding 那段）。
    // 两处都挂会把键盘高度算两遍，输入栏被顶到键盘底下 —— 表现为「一打字就看不见输入框」。
    Column(modifier.fillMaxSize()) {
        TopBar(workspaceName, state.running, state.trustWrites, onAttach, onClear)

        if (state.items.isEmpty()) {
            EmptyHint(workspaceName != null, onPick = onInput)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = SmithySpacing.gutter,
                vertical = SmithySpacing.gap,
            ),
            verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        ) {
            items(state.items, key = { it.id }) { item -> Item(item) }
        }

        // 确认条出现/消失都走展开动画：它是**门控**（不答就不往下走），
        // 突然盖在输入栏上会让人以为界面卡住了
        AnimatedVisibility(
            visible = state.pendingConfirm != null,
            enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
            exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
        ) {
            state.pendingConfirm?.let { request ->
                val destructive = state.items.filterIsInstance<ChatItem.Confirm>()
                    .lastOrNull()?.destructive ?: false
                ConfirmBar(request.toolName, request.summary, destructive, onConfirm)
            }
        }

        (state.message ?: state.busy)?.let { text ->
            StatusStrip(
                text = text,
                isError = state.isError,
                icon = if (state.isError) SmithyIcons.Warning else SmithyIcons.Run,
            )
        }

        state.configProblem?.let {
            StatusStrip(
                text = "AI 还没配好：$it（到「设置」填）",
                isError = true,
                icon = SmithyIcons.Warning,
            )
        }

        // 技能条：一条技能就是一套固定流程（侦查 → 改动 → 验证）。
        // 放在输入框上方而不是藏进菜单 —— 它是「不知道说什么」时的入口，
        // 而「不知道说什么」恰好发生在看着输入框的时候。
        SkillBar(
            skills = state.skills,
            active = state.activeSkill,
            running = state.running,
            onRun = onRunSkill,
            onShowAll = { showAllSkills = true },
        )

        InputBar(state.input, state.running, onInput, onSend, onStop)
    }

    if (showAllSkills) {
        SkillSheet(onDismiss = { showAllSkills = false }, onRun = { showAllSkills = false; onRunSkill(it) })
    }
}

/**
 * 一行状态条（忙碌 / 提示 / 配置问题）。
 *
 * 这三处在原来各写一遍 `Surface + Text`，底色不同、都没有图标 —— 而暗色下
 * 浅红和浅灰几乎分不出来，「出错了」和「在跑」看起来一样。统一成一个形状：
 * 有图标、有圆角、左右留白和列表对齐（原来贴边，看起来像被裁掉了）。
 */
@Composable
private fun StatusStrip(text: String, isError: Boolean, icon: ImageVector) {
    Surface(
        modifier = Modifier.padding(horizontal = SmithySpacing.gap, vertical = 4.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gap + 2.dp, vertical = SmithySpacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(SmithySpacing.gap))
            Text(
                text,
                style = SmithyRowMeta,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TopBar(
    workspaceName: String?,
    running: Boolean,
    trustWrites: Boolean,
    onAttach: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.barVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 「在操作哪个包」是这一页最重要的上下文（AI 的每个写操作都作用在它上面），
            // 所以给它一块**绑定卡**而不是一行小字：看不出来时用户会以为 AI 在瞎改
            Surface(
                color = if (workspaceName == null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⚠ / 📦 两个 emoji 换成矢量图标：emoji 在深色底上是彩的、大小还不一致，
                    // 而且它没法跟着 errorContainer 的语义色走
                    Icon(
                        imageVector = if (workspaceName == null) {
                            SmithyIcons.Warning
                        } else {
                            SmithyIcons.Package
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (workspaceName == null) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            workspaceName ?: "没有打开包",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            fontFamily = if (workspaceName == null) FontFamily.Default else SmithyMono,
                            color = if (workspaceName == null) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        )
                        Text(
                            buildString {
                                append(if (workspaceName == null) "先去工作台选一个" else "已绑定")
                                if (trustWrites) append("  ·  信任模式：写入不问")
                            },
                            style = SmithyRowMeta,
                            color = if (workspaceName == null) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (running) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                }
            }
            Spacer(Modifier.width(SmithySpacing.gap))
            // 「选包 / 清空」收成图标：这两个动作的含义足够强，而绑定卡才是这一行的主角，
            // 两个文字按钮会把它的宽度挤掉
            SmithyIconButton(
                icon = SmithyIcons.Attach,
                contentDescription = if (workspaceName == null) "选包" else "换包",
                onClick = onAttach,
                tint = MaterialTheme.colorScheme.primary,
            )
            SmithyIconButton(
                icon = SmithyIcons.ClearAll,
                contentDescription = "清空对话",
                onClick = onClear,
            )
        }
    }
}

@Composable
private fun EmptyHint(hasWorkspace: Boolean, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(SmithySpacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = SmithyIcons.Chat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(SmithySpacing.gap))
            Text("试着说一句", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(SmithySpacing.gap))
        // 例子做成**可点的药丸**：这几句话是可以照抄的指令，能点就不用手打；
        // 原来它们是一串带「·」的灰字，看着像说明文档而不像可用的入口
        EXAMPLE_PROMPTS.forEach { example ->
            Surface(
                onClick = { onPick(example) },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = SmithySpacing.gap),
            ) {
                Text(
                    example,
                    Modifier.padding(horizontal = 12.dp, vertical = SmithySpacing.gap),
                    style = SmithyRowMeta,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (!hasWorkspace) {
            StatusStrip(
                text = "改包之前要先在工作台打开一个 APK —— 否则我只能聊天，看不到包内容",
                isError = true,
                icon = SmithyIcons.Warning,
            )
        }
    }
}

/** 空对话时给的示例指令。写成可以直接点进输入框的整句，而不是关键词。 */
private val EXAMPLE_PROMPTS = listOf(
    "把这个包的应用名改成「我的应用」，版本改成 2.0",
    "把「工作台」这个文案改成「操作台」",
    "看看这个包有哪些权限，有没有危险的",
)

@Composable
private fun Item(item: ChatItem) = when (item) {
    is ChatItem.User -> UserBubble(item.text)
    is ChatItem.Assistant -> AssistantBubble(item)
    is ChatItem.Tool -> ToolCard(item)
    is ChatItem.Notice -> NoticeRow(item)
    is ChatItem.Inspection -> InspectionCard(item)
    is ChatItem.Confirm -> Unit    // 确认条固定在底部，不在列表里重复显示
}

/**
 * 用户消息：右侧**窄胶囊**。
 *
 * 0.75 宽而不是 0.85、圆角把右下角收成 4dp（指向发送者）—— 这是在说
 * 「这句是你说的话」。而 AI 那条是**全宽**：它的内容常常是路径、diff、
 * 多步说明，塞进窄气泡里会折成很难读的一长条。
 */
@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.fillMaxWidth(0.78f),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 助手回答超过这么多行就默认折叠。 */
private const val COLLAPSE_LINES = 8

@Composable
private fun AssistantBubble(item: ChatItem.Assistant) {
    var expanded by remember { mutableStateOf(false) }
    val lines = item.text.count { it == '\n' } + 1

    // 流式输出期间不折叠：一边出字一边变矮会很跳，读起来难受
    val collapsible = !item.streaming && lines > COLLAPSE_LINES

    // 全宽 + 描边气泡：AI 的回答是这一页的正文，不跟用户消息挤在同一侧。
    // surfaceContainer 底 + outlineVariant 描边（而不是纯色块）—— 长回答
    // 大色块会显得很重，描边只勾出边界
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = if (collapsible && !expanded) COLLAPSE_LINES else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.streaming) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                }
            }
            if (collapsible) {
                // 展开/收起用药丸：TextButton 的默认内边距让这一行显得比正文还重，
                // 而它只是「这条太长，要不要看全」的附属操作
                Surface(
                    onClick = { expanded = !expanded },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(99.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = SmithyIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(if (expanded) -90f else 90f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (expanded) "收起" else "展开全部（$lines 行）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具调用卡片。点一下展开参数 —— 参数常是 JSON，不展开太占地方。
 *
 * 表头用等宽字 + 状态色（✓ / ✗ / ⏳），「改了什么」的 diff 逐行上色 ——
 * 这是「AI 干了什么，用户看得见」的落点，也是决定要不要回退的依据。
 */
@Composable
private fun ToolCard(item: ChatItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = rememberSmithyHaptics()
    val failed = item.state == ChatItem.Tool.State.FAILED
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            1.dp,
            if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().clickable {
            haptics.tap()
            expanded = !expanded
        },
    ) {
        Column {
            // 表头块：和正文有底色差，一眼分得清「这是工具调用不是 AI 说的话」
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SmithySpacing.gap + 4.dp, vertical = SmithySpacing.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⏳ ✓ ✗ 三个字符换成图标：字符的字形、粗细、垂直位置都跟着系统字体走，
                    // 三种状态在一列里对不齐；图标还能跟着状态色走
                    Icon(
                        imageVector = when (item.state) {
                            ChatItem.Tool.State.RUNNING -> SmithyIcons.Run
                            ChatItem.Tool.State.OK -> SmithyIcons.Check
                            ChatItem.Tool.State.FAILED -> SmithyIcons.Warning
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = when {
                            failed -> MaterialTheme.colorScheme.error
                            item.state == ChatItem.Tool.State.RUNNING ->
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Text(
                        item.name,
                        style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    // 展开态靠箭头方向表示，不再多写「收起/参数」两个词
                    Icon(
                        imageVector = SmithyIcons.ChevronRight,
                        contentDescription = if (expanded) "收起参数" else "展开参数",
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (expanded) 90f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                Modifier.padding(
                    horizontal = SmithySpacing.gap + 4.dp,
                    vertical = SmithySpacing.rowVertical,
                ),
            ) {
            item.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) 40 else 2,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 改动明细：逐行上色，让「动了哪个文件」一眼能扫出来。
            // 这是「AI 改了什么，用户看得见」的落点，也是决定要不要回退的依据 ——
            // 它来自真实的改动记录（与「改动」标签页是同一批），所以两处不会对不上
            item.diff?.takeIf { it.isNotBlank() }?.let { diff ->
                Spacer(Modifier.height(6.dp))
                diff.lineSequence().forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.startsWith("＋") -> MaterialTheme.colorScheme.primary
                            line.startsWith("－") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = if (expanded) 60 else 4,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.args,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            }
        }
    }
}

@Composable
private fun NoticeRow(item: ChatItem.Notice) {
    // 给通知一个图标：不然「这一步没做成」和「顺手说一句」在视觉上完全一样
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.isError) SmithyIcons.Warning else SmithyIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (item.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(SmithySpacing.gap - 2.dp))
        Text(
            item.text,
            style = SmithyRowMeta,
            color = if (item.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 门控确认条。
 *
 * 破坏性操作的措辞要**比普通写入更重**：提示用户这件事靠回退救不回来
 * （比如装机 —— 包已经装到手机上了，撤销改动也换不回来）。
 */
@Composable
private fun ConfirmBar(
    toolName: String,
    summary: String,
    destructive: Boolean,
    onAnswer: (Boolean) -> Unit,
) {
    Surface(
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(horizontal = SmithySpacing.gap),
    ) {
        Column(Modifier.fillMaxWidth().padding(SmithySpacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (destructive) SmithyIcons.Warning else SmithyIcons.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (destructive) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(SmithySpacing.gap))
                Text(
                    if (destructive) "这个操作不可撤销，要执行吗" else "要执行这个操作吗",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "$toolName：$summary",
                style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                color = if (destructive) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // 「影响」一行：门控要用户答的是**后果**，而他此刻看得到的是工具名和参数。
            // 把后果写成一句话，比让他自己从工具名推要可靠 —— 尤其是两者看起来差不多的时候
            Spacer(Modifier.height(4.dp))
            Text(
                smithyEmphasis(
                    if (destructive) {
                        "影响：批准后**回退不回来**（包已经装到手机上 / 条目已经删掉）"
                    } else {
                        "影响：只写进工作区覆盖层，原包不动，之后可以逐条回退"
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (destructive) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(SmithySpacing.gap + 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
                // 「允许」带确认触感、「拒绝」带警示触感：这是门控，答错了代价最大，
                // 手指在点之前就该有点感觉
                val haptics = rememberSmithyHaptics()
                Button(
                    onClick = {
                        haptics.confirm()
                        onAnswer(true)
                    },
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Icon(SmithyIcons.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("允许")
                }
                OutlinedButton(onClick = {
                    haptics.warn()
                    onAnswer(false)
                }) {
                    Icon(SmithyIcons.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("拒绝")
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 体检卡与技能
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 体检卡：打开包之后直接摆进对话流的那张结论卡。
 *
 * 「结论一行 + 逐条判据」的排法：结论是给**要不要动手**用的，判据是给「凭什么这么说」用的。
 * 顺序反过来的话，用户得先读六行才能知道自己该不该继续。
 */
@Composable
private fun InspectionCard(item: ChatItem.Inspection) {
    val health = item.health
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(SmithySpacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = levelIcon(health.level),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = levelTint(health.level),
                )
                Spacer(Modifier.width(SmithySpacing.gap))
                Text(
                    "体检 · ${item.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(SmithySpacing.gap))
            Text(
                smithyEmphasis(health.headline),
                style = MaterialTheme.typography.bodyMedium,
                color = if (health.level == CheckLevel.BAD) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            health.detail?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(SmithySpacing.gap))
            health.items.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = levelIcon(h.level),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp).padding(top = 2.dp),
                        tint = levelTint(h.level),
                    )
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Text(
                        h.group,
                        style = SmithyRowMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp),
                    )
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Text(
                        smithyEmphasis(h.text),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (h.level == CheckLevel.BAD) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            health.next?.let {
                Spacer(Modifier.height(SmithySpacing.gap))
                Text(smithyEmphasis(it), style = SmithyRowMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 技能条：输入框上方那一排。
 *
 * 横向滚动而不是压成两行：技能数量会变，而每个名字都得看得完整
 * （「去签名校验」截成「去签名…」就分不清它和「装机验证」）。
 */
@Composable
private fun SkillBar(
    skills: List<Skill>,
    active: Skill?,
    running: Boolean,
    onRun: (Skill) -> Unit,
    onShowAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text(
            "技能（点一张按固定流程走）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SmithySpacing.gutter, bottom = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = SmithySpacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
        ) {
            skills.forEach { skill ->
                SkillChip(
                    label = skill.title,
                    icon = skillIcon(skill.id),
                    active = active?.id == skill.id,
                    enabled = !running,
                    onClick = { onRun(skill) },
                )
            }
            SkillChip(
                label = "全部…",
                icon = SmithyIcons.More,
                active = false,
                enabled = true,
                onClick = onShowAll,
            )
        }
    }
}

/** 一张技能卡（药丸）。 */
@Composable
private fun SkillChip(
    label: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberSmithyHaptics()
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(99.dp),
        modifier = Modifier.clickable(enabled = enabled) {
            haptics.tap()
            onClick()
        },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (active) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 全部技能。
 *
 * 每条都标出**只读还是会改动**：点之前该知道这条技能会不会动文件。
 * 引导型（如换图标）写明「要在工作台里做」，而不是让它看起来点了就能跑。
 */
@Composable
private fun SkillSheet(onDismiss: () -> Unit, onRun: (Skill) -> Unit) {
    val haptics = rememberSmithyHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { SmithyDialogTitle(icon = SmithyIcons.Run, text = "技能") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
            ) {
                Text(
                    "一条技能 = 一套固定流程（侦查 → 改动 → 验证）。点一下就走完，不用自己想措辞。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Skills.all.forEach { skill ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().clickable {
                            haptics.tap()
                            onRun(skill)
                        },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = skillIcon(skill.id),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(SmithySpacing.iconGap))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(skill.title, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.width(SmithySpacing.gap))
                                    Text(
                                        if (skill.readOnly) "只读" else "会改动",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (skill.readOnly) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    skill.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun levelTint(level: CheckLevel): Color = when (level) {
    CheckLevel.OK -> MaterialTheme.colorScheme.primary
    CheckLevel.WARN -> MaterialTheme.colorScheme.onSurfaceVariant
    CheckLevel.BAD -> MaterialTheme.colorScheme.error
}

private fun levelIcon(level: CheckLevel): ImageVector = when (level) {
    CheckLevel.OK -> SmithyIcons.Check
    CheckLevel.WARN -> SmithyIcons.Info
    CheckLevel.BAD -> SmithyIcons.Warning
}

/**
 * 技能 → 图标。
 *
 * 用**固定的映射**而不是让每条技能自己带一个图标：技能表在 toolkit（不认识 compose），
 * 图标是界面层的东西。多一层映射换来的是「技能表里加一条技能」不必碰界面代码。
 */
private fun skillIcon(id: String): ImageVector = when (id) {
    "inspect" -> SmithyIcons.Permission
    "text" -> SmithyIcons.Rename
    "rename" -> SmithyIcons.KindCode
    "localize" -> SmithyIcons.Files
    "killverify" -> SmithyIcons.Rule
    "ship" -> SmithyIcons.Download
    "icon" -> SmithyIcons.KindPicture
    else -> SmithyIcons.Run
}

/**
 * 输入栏。
 *
 * 圆角胶囊输入框 + 圆形发送键：这是聊天界面的通用形状语言，用户不用学。
 * 运行中时发送键变「停止」—— 同一个位置、同一个动作位（我在让它停），
 * 换到别处就会出现「停止在哪」的问题。
 */
@Composable
private fun InputBar(
    input: String,
    running: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = rememberSmithyHaptics()
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = SmithySpacing.gap + 2.dp, vertical = SmithySpacing.gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("说点什么…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
            }
            Spacer(Modifier.width(SmithySpacing.gap))
            if (running) {
                FilledIconButton(
                    onClick = {
                        haptics.warn()
                        onStop()
                    },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(SmithyIcons.Stop, "停止", Modifier.size(22.dp))
                }
            } else {
                FilledIconButton(
                    onClick = {
                        haptics.tap()
                        onSend()
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(SmithyIcons.Send, "发送", Modifier.size(20.dp))
                }
            }
        }
    }
}
