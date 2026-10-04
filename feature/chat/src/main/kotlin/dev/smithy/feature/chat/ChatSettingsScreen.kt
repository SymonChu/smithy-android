package dev.smithy.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.smithy.ai.AiConfig

/**
 * AI 设置（BYOK —— 用自己的 key）。
 *
 * 只放三样：网关地址、模型名、key。**不做「内置额度」**：那需要服务端，
 * 而这是个开源工具，用户自带 key 最省事也最可控；不该悄悄替用户付钱或替他保管凭据。
 */
@Composable
fun ChatSettingsScreen(
    config: AiConfig,
    problem: String?,
    onConfigChange: (AiConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("接口") {
            Field("地址", config.baseUrl, hint = "形如 https://api.openai.com/v1，不要带 /chat/completions") {
                onConfigChange(config.copy(baseUrl = it))
            }
            Field("模型", config.model, hint = "如 gpt-4o-mini / deepseek-chat") {
                onConfigChange(config.copy(model = it))
            }
            Field("API key", config.apiKey, secret = true, hint = "只存在本机应用私有目录，不会上传到别处") {
                onConfigChange(config.copy(apiKey = it))
            }
        }

        Section("常用网关") {
            listOf(
                "https://api.openai.com/v1" to "OpenAI",
                "https://api.deepseek.com/v1" to "DeepSeek",
                "https://dashscope.aliyuncs.com/compatible-mode/v1" to "通义千问（兼容模式）",
            ).forEach { (url, name) ->
                TextButton(onClick = { onConfigChange(config.copy(baseUrl = url)) }) {
                    Text("用 $name 的地址")
                }
            }
        }

        Section("当前状态") {
            if (problem == null) {
                Text("配置齐了，可以去对话页了", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "还差：$problem",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            "说明：对话里的每个写操作都会先问你（破坏性操作会额外标出来）。" +
                "所有改动都落在工作区的覆盖层里，重打包之前原包不动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
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
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (secret) {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (secret && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        )
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
