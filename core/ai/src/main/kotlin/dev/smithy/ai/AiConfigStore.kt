package dev.smithy.ai

import android.content.Context

/**
 * AI 配置的读写（BYOK —— 用户自己填网关地址与 key）。
 *
 * 用 `SharedPreferences` 而不是 DataStore/Room：这里只有三个字段，而 DataStore 会带来
 * 额外的协程依赖和初始化时机问题，不值得。
 *
 * **key 存在应用私有目录**（`MODE_PRIVATE`），不上传、不写日志。
 */
class AiConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("smithy.ai", Context.MODE_PRIVATE)

    fun load(): AiConfig {
        val defaults = AiConfig()
        return AiConfig(
            baseUrl = prefs.getString(KEY_BASE, null)?.takeIf { it.isNotBlank() } ?: defaults.baseUrl,
            apiKey = prefs.getString(KEY_KEY, null).orEmpty(),
            model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: defaults.model,
        )
    }

    fun save(config: AiConfig) {
        prefs.edit()
            .putString(KEY_BASE, config.baseUrl.trim())
            .putString(KEY_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .apply()
    }

    /** 只清 key，保留网关与模型名（换 key 比重配一遍全部字段常见）。 */
    fun clearKey() {
        prefs.edit().remove(KEY_KEY).apply()
    }

    /**
     * 配置是否可用。
     *
     * 缺 key 时**直接告诉用户缺什么**，而不是发一个请求再翻译 401 ——
     * 那要多等一个往返，错误信息还绕。
     */
    fun validate(config: AiConfig): String? = when {
        config.baseUrl.isBlank() -> "还没填接口地址（形如 https://api.openai.com/v1）"
        !config.baseUrl.startsWith("http") -> "接口地址要以 http:// 或 https:// 开头"
        config.apiKey.isBlank() -> "还没填 API key"
        config.model.isBlank() -> "还没填模型名"
        else -> null
    }

    private companion object {
        const val KEY_BASE = "baseUrl"
        const val KEY_KEY = "apiKey"
        const val KEY_MODEL = "model"
    }
}
