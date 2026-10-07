package dev.smithy.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.smithy.ai.AiConfig
import dev.smithy.design.SmithyCard
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics

/**
 * AI 设置（BYOK —— 用自己的 key）。
 *
 * 只放三样：网关地址、模型名、key。**不做「内置额度」**：那需要服务端，
 * 而这是个开源工具，用户自带 key 最省事也最可控；不该悄悄替用户付钱或替他保管凭据。
 *
 * 排版上这一页**不用 Card**（原来的三张 M3 默认 Card 是「示例工程」观感里最典型的一件）：
 * 分组靠 `SmithyCard` 的 surfaceContainerLow 底色 + 小标题，和文件页的分组语言一致。
 */
@Composable
fun ChatSettingsScreen(
    config: AiConfig,
    problem: String?,
    trustWrites: Boolean,
    onConfigChange: (AiConfig) -> Unit,
    onTrustWritesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SmithyTopBar(title = "设置", subtitle = "AI 接口与写入门控")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = SmithySpacing.gutter,
                    vertical = SmithySpacing.gap,
                ),
            verticalArrangement = Arrangement.spacedBy(SmithySpacing.section),
        ) {
            Group(title = "接口") {
                Field(
                    label = "地址",
                    value = config.baseUrl,
                    hint = "形如 https://api.openai.com/v1，不要带 /chat/completions",
                ) { onConfigChange(config.copy(baseUrl = it)) }
                Field(
                    label = "模型",
                    value = config.model,
                    hint = "如 gpt-4o-mini / deepseek-chat",
                ) { onConfigChange(config.copy(model = it)) }
                Field(
                    label = "API key",
                    value = config.apiKey,
                    secret = true,
                    hint = "只存在本机应用私有目录，不会上传到别处",
                ) { onConfigChange(config.copy(apiKey = it)) }
            }

            Group(title = "常用网关", hint = "点一下就把地址填进去") {
                listOf(
                    "https://api.openai.com/v1" to "OpenAI",
                    "https://api.deepseek.com/v1" to "DeepSeek",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1" to "通义千问（兼容模式）",
                ).forEach { (url, name) ->
                    GatewayPill(
                        name = name,
                        current = config.baseUrl == url,
                        onClick = { onConfigChange(config.copy(baseUrl = url)) },
                    )
                }
            }

            Group(title = "门控") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("信任模式", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "开启后，改文案、改清单这类写入不再逐条问你；" +
                                "装机、删除这类不可撤销的操作**仍然会问** —— " +
                                "因为它们的后果回退不回来（包已经装到手机上了）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(SmithySpacing.gap))
                    val haptics = rememberSmithyHaptics()
                    // 开关拨动给触感：它是「以后不再问我」这种影响后续所有操作的决定，
                    // 比普通勾选重一档
                    Switch(
                        checked = trustWrites,
                        onCheckedChange = {
                            haptics.toggle()
                            onTrustWritesChange(it)
                        },
                    )
                }
            }

            Group(title = "当前状态") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (problem == null) {
                            SmithyIcons.Check
                        } else {
                            SmithyIcons.Warning
                        },
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                        tint = if (problem == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.width(SmithySpacing.gap))
                    Text(
                        if (problem == null) "配置齐了，可以去对话页了" else "还差：$problem",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (problem == null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Text(
                "说明：对话里的每个写操作都会先问你（破坏性操作会额外标出来）。" +
                    "所有改动都落在工作区的覆盖层里，重打包之前原包不动。",
                style = SmithyRowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一组设置。
 *
 * 用 `SmithyCard`（surfaceContainerLow 底色 + 圆角）而不是 M3 的 `Card`：
 * 后者的默认高度和投影在暗色主题下几乎看不见，最后还是靠描边 —— 而整个应用
 * 现在统一用**表面色层次**分组，不靠线和影。
 */
@Composable
private fun Group(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SmithyCard(modifier = Modifier.padding(top = SmithySpacing.gap)) {
            Column(
                Modifier.padding(SmithySpacing.cardPadding),
                verticalArrangement = Arrangement.spacedBy(SmithySpacing.gap + 2.dp),
            ) {
                content()
            }
        }
    }
}

/** 常用网关。当前正在用的那个填色 —— 不然用户点了之后看不出生效没有。 */
@Composable
private fun GatewayPill(name: String, current: Boolean, onClick: () -> Unit) {
    val haptics = rememberSmithyHaptics()
    Surface(
        color = if (current) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(99.dp),
        modifier = Modifier.clickable {
            haptics.tap()
            onClick()
        },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current) {
                Icon(
                    imageVector = SmithyIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = if (current) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String? = null,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    // key 默认遮住；给一个显式开关，因为「我刚粘的对不对」是很实际的需求
    var revealed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (secret) {
                // 「显示 / 隐藏」换成一个会切换的眼睛图标：状态由图标本身表达，
                // 不用读文字，也不用在两个词之间来回确认自己现在处在哪个状态
                SmithyIconButton(
                    icon = if (revealed) SmithyIcons.HiddenOn else SmithyIcons.HiddenOff,
                    contentDescription = if (revealed) "隐藏 key" else "显示 key",
                    onClick = { revealed = !revealed },
                    modifier = Modifier.size(SmithySpacing.barIconBox),
                )
            }
        }
        // 填充式输入框（不是描边式）：这一页是「填几项配置」，描边框在密集表单里
        // 会产生很多横线，而底色块更安静
        TextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            visualTransformation = if (secret && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
