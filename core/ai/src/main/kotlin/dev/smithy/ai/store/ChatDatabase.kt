package dev.smithy.ai.store

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 会话与消息的持久化。
 *
 * 为什么值得落盘：一次改包往往要来回十几轮（搜 → 改 → 打包 → 装 → 发现问题再改），
 * 而手机上的应用随时可能被系统杀掉。不落盘的话，回来只剩一片空白，
 * 用户还得重新交代一遍「我要改什么、改到哪了」。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 这条会话是在哪个包上进行的（换包时要能提醒用户） */
    val workspaceName: String?,
)

@Entity(
    tableName = "messages",
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** OpenAI 协议里的角色：system / user / assistant / tool */
    val role: String,
    val content: String?,
    /** assistant 消息带的 tool_calls，存 JSON —— 协议要求原样回传 */
    val toolCallsJson: String?,
    /** tool 消息对应的调用 id，**必须与 assistant 里的配对**，否则上游直接 400 */
    val toolCallId: String?,
    /** UI 侧的分类：user / assistant / tool / notice（只影响展示） */
    val kind: String,
    val toolName: String?,
    val toolOk: Boolean?,
    val createdAt: Long,
)

@Dao
interface ChatDao {

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    suspend fun sessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id")
    suspend fun messages(sessionId: String): List<MessageEntity>

    @Insert
    suspend fun insertMessage(entity: MessageEntity): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearMessages(sessionId: String)
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
