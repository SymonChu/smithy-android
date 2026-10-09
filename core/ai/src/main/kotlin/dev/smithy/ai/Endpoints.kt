package dev.smithy.ai

import java.net.URI

/**
 * 网关地址的归一化与「这个地址安全吗」的判断。
 *
 * 起因是一次真实的对照：用户同一个网关地址，在别处（Xposed 模块里，那边是
 * 「填 base 也能用」的宽容写法）能用，到 Smithy 就报连不上/404。差别只在
 * **怎么把用户填的字符串拼成完整 URL** —— 一家要求填到 `/v1`、另一家自己补，
 * 用户不可能知道该填哪一档。
 *
 * 所以这里对齐**宽容**的那一档（Xposed 模块 `AiChatConfig.resolveEndpoint` 的做法）：
 *
 * - 已经是完整路径（`/chat/completions` 等）⇒ 原样用，不再拼
 * - 以 `/vN` 结尾 ⇒ 只补末段
 * - 否则 ⇒ 补整条缺省路径
 *
 * 三种填法都能打通是**目标**，不是顺带：BYOK 的用户填的是自己的网关，
 * 而「填错了」和「网关了」在界面上表现为同一个红条，用户没法自己分辨。
 */
object Endpoints {

    /** 缺省路径：OpenAI 兼容接口的规范位置。 */
    const val DEFAULT_PATH = "/v1/chat/completions"

    /** `/v1`、`/v2` 这种版本尾巴。 */
    private val VERSION_TAIL = Regex("""/v\d+$""")

    /**
     * 已经是完整接口路径的尾巴。
     *
     * 再拼一次就变成 `/chat/completions/chat/completions` —— 这种错在界面上只表现为
     * 一个 404，而用户的反应是「这地址我别处能用啊」。
     */
    private val FULL_TAILS = listOf("/chat/completions", "/completions", "/responses")

    /** 用户填的地址 → 真正要 POST 的地址。空串进空串出。 */
    fun chatCompletions(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        // 不是 http(s) 就不猜了：交给上层报「地址要以 http:// 或 https:// 开头」
        if (!s.startsWith("http", ignoreCase = true)) return s
        if (FULL_TAILS.any { s.endsWith(it) }) return s
        val base = s.trimEnd('/')
        return if (VERSION_TAIL.containsMatchIn(base)) "$base/chat/completions" else "$base$DEFAULT_PATH"
    }

    /** 明文（http）地址。 */
    fun isPlaintext(raw: String): Boolean = raw.trim().startsWith("http://", ignoreCase = true)

    /**
     * 这个 host 是不是「自己的网」。
     *
     * 域名**一律当公网**：`api.deepseek.com` 和 `my-gateway.lan` 从字符串上没法区分，
     * 猜错的方向只会是「把公网当私网」= 少提醒一次 —— 那不如不猜。IPv4 的字面量
     * 与 localhost 能准确判断，这两类恰好是自建网关最常见的形态。
     */
    fun isPrivateHost(raw: String): Boolean {
        val host = runCatching { URI(raw.trim()).host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true

        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false

        return octets[0] == 10 ||                              // 10/8
            (octets[0] == 172 && octets[1] in 16..31) ||        // 172.16/12
            (octets[0] == 192 && octets[1] == 168) ||           // 192.168/16
            octets[0] == 127 ||                                 // 本机
            (octets[0] == 169 && octets[1] == 254)              // link-local
    }

    /**
     * 公网明文地址的提醒；没必要提醒时返回 null。
     *
     * 为什么是「提醒」而不是「拦住」：Android 的网络策略**没法按网段放行明文**
     * （network security config 的 `<domain>` 只认具体主机名，不认 CIDR），
     * 所以 App 层面只能整开明文。既然整开了，代价就要说给用户听 —— 而局域网自建
     * 网关（这是明文的主要用户）本来就只在内网跑，不该被念一遍。
     */
    fun plaintextWarning(raw: String): String? {
        if (!isPlaintext(raw) || isPrivateHost(raw)) return null
        return "这是公网明文地址：API key 与对话内容会不加密地经过中间网络，" +
            "同一网络里的人能看出来。能用 https 就换 https。"
    }
}
