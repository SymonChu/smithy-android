package dev.smithy.ai

import android.content.Context
import androidx.room.Room
import dev.smithy.ai.store.ChatDatabase
import dev.smithy.ai.store.MessageEntity
import dev.smithy.ai.store.SessionEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 会话读写。
 *
 * 领域对象（[ChatMessage]）与存储实体（[MessageEntity]）**刻意分开**：
 * 前者要贴合上游协议（角色取值、`tool_calls` 的嵌套结构），后者要贴合查询
 * （会话 id、UI 分类、时间）。合成一个的话，任何一边变都会牵动另一边。
 */
class SessionStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val db = Room.databaseBuilder(
        context.applicationContext,
        ChatDatabase::class.java,
        "smithy-chat.db",
    ).build()

    private val dao get() = db.chatDao()

    fun newSessionId(): String = UUID.randomUUID().toString()

    suspend fun createSession(id: String, title: String, workspaceName: String?) {
        val now = System.currentTimeMillis()
        dao.upsertSession(
            SessionEntity(
                id = id,
                title = title.take(60),
                createdAt = now,
                updatedAt = now,
                workspaceName = workspaceName,
            ),
        )
    }

    suspend fun touchSession(id: String, title: String?) {
        val existing = dao.session(id) ?: return
        dao.upsertSession(
            existing.copy(
                title = title?.take(60) ?: existing.title,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun sessions(): List<SessionEntity> = runCatching { dao.sessions() }.getOrDefault(emptyList())

    suspend fun delete(sessionId: String) {
        dao.clearMessages(sessionId)
        dao.deleteSession(sessionId)
    }

    suspend fun clear(sessionId: String) = dao.clearMessages(sessionId)

    /**
     * 追加一条消息。
     *
     * `kind` 与 `toolName` 只服务界面展示，回放历史时不参与协议拼装 ——
     * 协议只认 role / content / tool_calls / tool_call_id。
     */
    suspend fun append(
        sessionId: String,
        message: ChatMessage,
        kind: String,
        toolName: String? = null,
        toolOk: Boolean? = null,
    ) {
        runCatching {
            dao.insertMessage(
                MessageEntity(
                    sessionId = sessionId,
                    role = message.role.name.lowercase(),
                    content = message.content,
                    toolCallsJson = message.toolCalls
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { json.encodeToString(it) },
                    toolCallId = message.toolCallId,
                    kind = kind,
                    toolName = toolName,
                    toolOk = toolOk,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            touchSession(sessionId, title = null)
        }
    }

    /**
     * 读回历史。
     *
     * 读失败（数据库损坏、结构升级）时**返回空列表而不是抛异常**：
     * 让用户丢历史可以接受，让对话页整个打不开不行。
     */
    suspend fun load(sessionId: String): List<ChatMessage> = runCatching {
        dao.messages(sessionId).mapNotNull { it.toChatMessage() }
    }.getOrDefault(emptyList())

    private fun MessageEntity.toChatMessage(): ChatMessage? {
        val role = when (this.role) {
            "system" -> ChatMessage.Role.SYSTEM
            "user" -> ChatMessage.Role.USER
            "assistant" -> ChatMessage.Role.ASSISTANT
            "tool" -> ChatMessage.Role.TOOL
            else -> return null
        }
        val calls = toolCallsJson?.let {
            runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
        }
        return ChatMessage(
            role = role,
            content = content,
            toolCalls = calls,
            toolCallId = toolCallId,
        )
    }
}
