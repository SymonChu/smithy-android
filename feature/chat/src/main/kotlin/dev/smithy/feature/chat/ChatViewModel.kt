package dev.smithy.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.ai.AgentEvent
import dev.smithy.ai.AgentLoop
import dev.smithy.ai.AiConfig
import dev.smithy.ai.AiConfigStore
import dev.smithy.ai.ChatMessage
import dev.smithy.ai.DEFAULT_SYSTEM_PROMPT
import dev.smithy.ai.OpenAiClient
import dev.smithy.toolkit.ConfirmRequest
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.NoWorkspaceException
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.WorkspaceHolder
import dev.smithy.toolkit.defaultRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
// 对话里的一条条目
// ─────────────────────────────────────────────────────────────

/**
 * 工具调用是**独立条目**而不是塞进助手气泡里：它有自己的生命周期
 * （进行中 → 成功/失败），而且用户要能单独点开看参数和结果。
 */
sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem

    data class Assistant(
        override val id: Long,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatItem

    data class Tool(
        override val id: Long,
        val name: String,
        val args: String,
        val state: State,
        val summary: String? = null,
    ) : ChatItem {
        enum class State { RUNNING, OK, FAILED }
    }

    data class Notice(
        override val id: Long,
        val text: String,
        val isError: Boolean = false,
    ) : ChatItem

    data class Confirm(
        override val id: Long,
        val toolName: String,
        val summary: String,
        val destructive: Boolean,
    ) : ChatItem
}

data class ChatUiState(
    val items: List<ChatItem> = emptyList(),
    val input: String = "",
    val running: Boolean = false,
    val config: AiConfig = AiConfig(),
    /** 配置缺什么（null = 齐全）。缺的时候直接说明，不用发请求再翻译 401 */
    val configProblem: String? = null,
    /** 当前工作区名字（null = 没打开包） */
    val workspaceName: String? = null,
    /** 正在等用户点的确认（null = 没有） */
    val pendingConfirm: ConfirmRequest? = null,
)

/**
 * 对话与 Agent 的接线。
 *
 * 三件事在这里落地：
 * 1. **把工具的执行过程投影成 UI 条目**（Agent 事件 → 气泡/卡片）
 * 2. **门控确认**：工具层的 `confirm()` 是挂起的，这里把它接到界面上的两个按钮
 * 3. **工作区一致性**：工具操作的是工作台打开的那个工程（`WorkspaceHolder`），
 *    而不是自己再开一份
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val configStore = AiConfigStore(app)
    private val registry = defaultRegistry()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 已确认的对话历史（含工具往返），每轮结束由 AgentLoop 回填 */
    private var history = mutableListOf<ChatMessage>()
    private var job: Job? = null
    private var nextId = 1L

    /**
     * 正在等用户点的确认。
     *
     * 必须在对话结束时**放行**（返回 false）：否则 `confirm()` 会一直挂着，
     * 用户点了「停止」也停不下来 —— 协程卡在 await 上，取消都要等它。
     */
    private var pendingConfirm: CompletableDeferred<Boolean>? = null

    init {
        val cfg = configStore.load()
        _state.update {
            it.copy(config = cfg, configProblem = configStore.validate(cfg), workspaceName = WorkspaceHolder.currentName)
        }
    }

    private fun id(): Long = nextId++

    private fun addItem(item: ChatItem) {
        _state.update { it.copy(items = it.items + item) }
    }

    // ── 输入与配置 ──────────────────────────────────────────────

    fun onInput(text: String) = _state.update { it.copy(input = text) }

    /** 切回对话页时调一下：工作区可能在工作台里换了包。 */
    fun refreshWorkspace() = _state.update { it.copy(workspaceName = WorkspaceHolder.currentName) }

    fun onConfigChange(config: AiConfig) {
        configStore.save(config)
        _state.update { it.copy(config = config, configProblem = configStore.validate(config)) }
    }

    // ── 发送 / 停止 ─────────────────────────────────────────────

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.running) return

        val config = _state.value.config
        configStore.validate(config)?.let { problem ->
            addItem(ChatItem.Notice(id(), problem, isError = true))
            return
        }

        _state.update { it.copy(input = "", running = true) }
        addItem(ChatItem.User(id(), text))
        history += ChatMessage.user(text)

        val agent = AgentLoop(OpenAiClient(config), registry, systemPrompt = systemPrompt())
        val ctx = UiToolContext()

        job = viewModelScope.launch {
            try {
                agent.run(history.toList(), ctx).collect(::handle)
            } catch (t: Throwable) {
                addItem(
                    ChatItem.Notice(
                        id(),
                        "对话中断：${t.message ?: t::class.java.simpleName}",
                        isError = true,
                    ),
                )
            } finally {
                // 先放行挂着的确认，再收尾 —— 顺序反了会留下一个永远等不到的 await
                pendingConfirm?.complete(false)
                pendingConfirm = null
                _state.update { it.copy(running = false, pendingConfirm = null) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        addItem(ChatItem.Notice(id(), "已停止（未确认的操作都当作拒绝）"))
        _state.update { it.copy(running = false, pendingConfirm = null) }
    }

    fun clear() {
        stop()
        history.clear()
        _state.update { it.copy(items = emptyList()) }
    }

    /** 用户在确认条上点了允许/拒绝。 */
    fun answerConfirm(allow: Boolean) {
        val deferred = pendingConfirm
        pendingConfirm = null
        _state.update {
            it.copy(
                pendingConfirm = null,
                items = it.items.filterNot { item -> item is ChatItem.Confirm },
            )
        }
        deferred?.complete(allow)
    }

    // ── Agent 事件 → UI ────────────────────────────────────────

    private fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.Round -> Unit

            is AgentEvent.Text -> appendAssistant(event.delta)

            is AgentEvent.ToolStarted -> {
                markAssistantDone()
                addItem(
                    ChatItem.Tool(id(), event.name, event.argsPreview, ChatItem.Tool.State.RUNNING),
                )
            }

            is AgentEvent.ToolFinished -> finishTool(event)

            is AgentEvent.Done -> {
                history = event.messages.toMutableList()
                markAssistantDone()
            }

            is AgentEvent.Failed -> {
                markAssistantDone()
                addItem(ChatItem.Notice(id(), event.message, isError = true))
            }
        }
    }

    /** 流式文本往最后一条助手气泡上追加；没有就新开一条。 */
    private fun appendAssistant(delta: String) {
        _state.update { s ->
            val last = s.items.lastOrNull()
            if (last is ChatItem.Assistant && last.streaming) {
                s.copy(items = s.items.dropLast(1) + last.copy(text = last.text + delta))
            } else {
                s.copy(items = s.items + ChatItem.Assistant(id(), delta, streaming = true))
            }
        }
    }

    private fun markAssistantDone() {
        _state.update { s ->
            val last = s.items.lastOrNull()
            if (last is ChatItem.Assistant && last.streaming) {
                s.copy(items = s.items.dropLast(1) + last.copy(streaming = false))
            } else {
                s
            }
        }
    }

    private fun finishTool(event: AgentEvent.ToolFinished) {
        _state.update { s ->
            val idx = s.items.indexOfLast {
                it is ChatItem.Tool && it.name == event.name && it.state == ChatItem.Tool.State.RUNNING
            }
            if (idx < 0) return@update s
            val old = s.items[idx] as ChatItem.Tool
            val updated = old.copy(
                state = if (event.ok) ChatItem.Tool.State.OK else ChatItem.Tool.State.FAILED,
                summary = event.summary,
            )
            s.copy(items = s.items.toMutableList().also { it[idx] = updated })
        }
    }

    // ── 工具上下文 ─────────────────────────────────────────────

    /**
     * 工具执行时的环境。
     *
     * `workspace` 直接读 `WorkspaceHolder` —— 用户在工作台换包之后，下一个工具调用
     * 自然就作用于新包，不需要重建这个对象。
     */
    private inner class UiToolContext : ToolContext {

        override val workspace get() = WorkspaceHolder.current

        override fun requireWorkspace() = workspace ?: throw NoWorkspaceException()

        override val sessionId: String = "chat"

        override val callId: String = "call"

        override fun progress(message: String) = addItem(ChatItem.Notice(id(), message))

        override suspend fun confirm(request: ConfirmRequest): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            pendingConfirm = deferred
            _state.update {
                it.copy(
                    pendingConfirm = request,
                    items = it.items + ChatItem.Confirm(
                        id = id(),
                        toolName = request.toolName,
                        summary = request.summary,
                        destructive = request.effect == Effect.DESTRUCTIVE,
                    ),
                )
            }
            return deferred.await()
        }
    }

    /**
     * 系统提示 = 基础说明 + **当前环境**。
     *
     * 把「有没有打开包、打开的是哪个」写进去，是因为模型看不见界面状态：
     * 不说的话它会去猜，或者在没包的时候硬调工具，然后收到一串 `NO_WORKSPACE`
     * 才开始道歉 —— 这几轮都是白花的额度。
     */
    private fun systemPrompt(): String = buildString {
        append(DEFAULT_SYSTEM_PROMPT)
        append("\n## 现在的环境\n")
        val name = WorkspaceHolder.currentName
        if (name == null) {
            append("用户还没有打开任何 APK。要操作包之前，先请他到「工作台」标签页选一个包打开；")
            append("在那之前你只能回答概念问题，**不要假装能看到包内容**。")
        } else {
            append("用户已经在工作台打开了「$name」，你的工具默认作用于它。")
        }
    }
}
