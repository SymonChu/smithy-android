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
            skipTlsVerify = prefs.getBoolean(KEY_SKIP_TLS, defaults.skipTlsVerify),
        )
    }

    fun save(config: AiConfig) {
        prefs.edit()
            .putString(KEY_BASE, config.baseUrl.trim())
            .putString(KEY_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putBoolean(KEY_SKIP_TLS, config.skipTlsVerify)
            .apply()
    }

    /** 只清 key，保留网关与模型名（换 key 比重配一遍全部字段常见）。 */
    fun clearKey() {
        prefs.edit().remove(KEY_KEY).apply()
    }

    /**
     * 信任模式：开启后 WRITE 级工具不再逐条问，**DESTRUCTIVE 仍然要确认**。
     *
     * 单独存取而不是塞进 [AiConfig]：那是「模型怎么连」的配置，这是「工具要不要问」的
     * 安全策略 —— 混在一起的话，导出一份模型配置会把安全设置一并带出去。
     *
     * **默认关闭**：新用户对「AI 直接改包」应该有戒心，主动开启才算知情。
     */
    fun trustWrites(): Boolean = prefs.getBoolean(KEY_TRUST, false)

    fun setTrustWrites(value: Boolean) {
        prefs.edit().putBoolean(KEY_TRUST, value).apply()
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
        // 地址归一后拼不出 URL 的话，问题在地址本身，不该等到发请求才炸
        config.chatCompletionsUrl.isBlank() -> "接口地址看不出该请求哪里，检查一下有没有多余的空格"
        else -> null
    }

    private companion object {
        const val KEY_BASE = "baseUrl"
        const val KEY_KEY = "apiKey"
        const val KEY_MODEL = "model"
        const val KEY_TRUST = "trustWrites"
        const val KEY_SKIP_TLS = "skipTlsVerify"
    }
}
