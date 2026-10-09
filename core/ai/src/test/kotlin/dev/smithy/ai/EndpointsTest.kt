package dev.smithy.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 地址归一。
 *
 * 这些用例的**来源是一次真实的对照**：同一个网关地址，用户填在别处能用，填到这里
 * 只报「连不上 / 404」。差别就是拼地址的方式，所以这里把三种常见填法都钉死 ——
 * 宽容是刻意的（用户不知道自家网关的路径长什么样），不是随手写的容错。
 */
class EndpointsTest {

    @Test
    fun `只填主机时补出完整路径`() {
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            Endpoints.chatCompletions("https://gateway.example.com"),
        )
    }

    @Test
    fun `局域网地址与端口照样补`() {
        assertEquals(
            "http://192.168.1.9:8317/v1/chat/completions",
            Endpoints.chatCompletions("http://192.168.1.9:8317"),
        )
    }

    @Test
    fun `填到 v1 时只补末段`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Endpoints.chatCompletions("https://api.openai.com/v1"),
        )
    }

    @Test
    fun `已经是完整路径就原样用`() {
        // 再拼一次会变成 /chat/completions/chat/completions，而那种错在界面上只是个 404
        val full = "https://api.deepseek.com/v1/chat/completions"
        assertEquals(full, Endpoints.chatCompletions(full))
        assertEquals(
            "https://x.example.com/anthropic/v1/responses",
            Endpoints.chatCompletions("https://x.example.com/anthropic/v1/responses"),
        )
    }

    @Test
    fun `结尾斜杠与空格不影响结果`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Endpoints.chatCompletions("  https://api.openai.com/v1/  "),
        )
    }

    @Test
    fun `非版本结尾的路径按整条缺省路径补`() {
        // 这是刻意的取舍：看不出用户想请求哪里时，只有补规范路径这一种「大概率对」的选择
        assertEquals(
            "https://host.example.com/api/v1/chat/completions",
            Endpoints.chatCompletions("https://host.example.com/api"),
        )
    }

    @Test
    fun `空串进空串出`() {
        assertEquals("", Endpoints.chatCompletions("   "))
    }

    @Test
    fun `非 http 的串不猜路径`() {
        // 该由「地址要以 http:// 或 https:// 开头」那条校验去报，这里硬拼只会制造一个更怪的错
        assertEquals("api.openai.com", Endpoints.chatCompletions("api.openai.com"))
    }

    @Test
    fun `私网地址认得出来`() {
        listOf(
            "http://192.168.1.9:8317/v1",
            "http://10.0.0.2/v1",
            "http://172.16.5.4/v1",
            "http://172.31.255.254/v1",
            "http://127.0.0.1:1234/v1",
            "http://localhost:1234/v1",
            "http://nas.local/v1",
            "http://169.254.1.1/v1",
        ).forEach { assertTrue(Endpoints.isPrivateHost(it), "$it 应该算自己的网") }
    }

    @Test
    fun `公网地址不当私网`() {
        listOf(
            "https://api.openai.com/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",   // 172.16/12 之外
            "http://192.169.1.1/v1",  // 不是 192.168
            "http://gateway.example.com/v1",
        ).forEach { assertFalse(Endpoints.isPrivateHost(it), "$it 不该当成自己的网") }
    }

    @Test
    fun `公网明文才提醒 私网不念`() {
        assertNotNull(Endpoints.plaintextWarning("http://api.example.com/v1"))
        assertNull(Endpoints.plaintextWarning("http://192.168.1.9:8317/v1"))
        assertNull(Endpoints.plaintextWarning("https://api.example.com/v1"))
    }
}
