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
import dev.smithy.ai.SessionStore
import dev.smithy.engine.ApkHealthCheck
import dev.smithy.engine.ApkHealth
import dev.smithy.engine.ApkProject
import dev.smithy.engine.ApkProjects
import dev.smithy.fs.InstalledApp
import dev.smithy.design.withoutEmphasis
import dev.smithy.toolkit.ConfirmPolicy
import dev.smithy.toolkit.ConfirmRequest
import dev.smithy.toolkit.Effect
import dev.smithy.toolkit.NoWorkspaceException
import dev.smithy.toolkit.Skill
import dev.smithy.toolkit.Skills
import dev.smithy.toolkit.ToolContext
import dev.smithy.toolkit.WorkspaceHolder
import dev.smithy.toolkit.defaultRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        /** 这次调用**实际改了什么**（来自改动记录）。只读工具为 null */
        val diff: String? = null,
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

    /**
     * 体检卡。
     *
     * 它是**本地扫描的结论**，不是模型的输出：打开包的那一刻就能算出来（不解包、不联网、
     * 不花 token），所以直接插进对话流 —— 用户问「能改吗」时，答案已经在屏幕上了。
     */
    data class Inspection(
        override val id: Long,
        val name: String,
        val health: ApkHealth,
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

    /** 可选技能（固定流程）。界面上是输入框上方那一排。 */
    val skills: List<Skill> = Skills.quickPicks,

    /**
     * 这一轮正在走哪条技能（null = 自由对话）。
     *
     * 它决定**挂哪些工具**：技能只挂这条流程要用的那几个。49 个工具全挂给模型时，
     * 它会挑看起来差不多的那个（改文案时去动 dex、顺手改别的字段）。
     */
    val activeSkill: Skill? = null,

    /** 信任模式：开着的话 WRITE 级工具不再逐条问（DESTRUCTIVE 仍然问） */
    val trustWrites: Boolean = false,

    /** 正在忙什么（打开包之类的即时动作），null = 空闲 */
    val busy: String? = null,

    /** 一次性提示（成功也用它，不只报错） */
    val message: String? = null,
    val isError: Boolean = false,
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

    /**
     * 工具注册表。
     *
     * **信任模式切换时要重建**：策略是构造参数（`ConfirmPolicy`），不能就地改 ——
     * 一份策略只有一个来源，才不会出现「界面显示已信任、实际还在问」这种不一致。
     */
    private var registry = defaultRegistry(ConfirmPolicy(configStore.trustWrites()))

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val sessionStore = SessionStore(app)

    /** 已确认的对话历史（含工具往返），每轮结束由 AgentLoop 回填 */
    private var history = mutableListOf<ChatMessage>()
    private var job: Job? = null
    private var nextId = 1L

    /** 当前会话 id（落盘用） */
    private var sessionId: String? = null

    /**
     * 已经落盘到第几条**协议消息**。
     *
     * 落盘按「协议消息」而不是「UI 条目」记账：条目是展示层的投影，
     * 两者数量对不上，混着记迟早错位。Done / 中断时把新增的补上即可。
     */
    private var persistedCount = 0

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
            it.copy(
                config = cfg,
                configProblem = configStore.validate(cfg),
                workspaceName = WorkspaceHolder.currentName,
                trustWrites = configStore.trustWrites(),
            )
        }
        // 恢复上次的会话：改包常要来回十几轮，中途被系统杀掉不该丢掉「改到哪了」
        viewModelScope.launch {
            val last = runCatching { sessionStore.sessions().firstOrNull() }.getOrNull()
            if (last == null) {
                sessionId = sessionStore.newSessionId()
            } else {
                sessionId = last.id
                history = sessionStore.load(last.id).toMutableList()
                persistedCount = history.size
                _state.update { s -> s.copy(items = history.mapIndexedNotNull { i, m -> m.toUiItem(i + 1L) }) }
                nextId = history.size + 1L
            }
        }
    }

    /**
     * 协议消息 → 界面条目（只用于恢复历史）。
     *
     * 工具结果恢复成「已完成」的卡片：原文已经在上下文里，这里只需让用户看到当时调过什么。
     * **summary 不落盘**（那是展示层的简述）—— 有意为之：多存一份展示文案，
     * 就多一处「两边不一致」的隐患。
     */
    private fun ChatMessage.toUiItem(itemId: Long): ChatItem? = when (role) {
        ChatMessage.Role.USER -> ChatItem.User(itemId, content.orEmpty())

        ChatMessage.Role.ASSISTANT ->
            content?.takeIf { it.isNotBlank() }?.let { ChatItem.Assistant(itemId, it) }

        ChatMessage.Role.TOOL -> ChatItem.Tool(
            id = itemId,
            name = "工具结果",
            args = "",
            state = ChatItem.Tool.State.OK,
            summary = content?.take(200),
        )

        ChatMessage.Role.SYSTEM -> null
    }

    /** 把还没落盘的协议消息补上。 */
    private suspend fun persistNew() {
        val session = sessionId ?: return
        while (persistedCount < history.size) {
            val message = history[persistedCount]
            sessionStore.append(session, message, kind = message.role.name.lowercase())
            persistedCount++
        }
        sessionStore.touchSession(session, title = history.firstOrNull { it.role == ChatMessage.Role.USER }?.content)
    }

    private fun id(): Long = nextId++

    private fun addItem(item: ChatItem) {
        _state.update { it.copy(items = it.items + item) }
    }

    // ── 输入与配置 ──────────────────────────────────────────────

    fun onInput(text: String) = _state.update { it.copy(input = text) }

    /**
     * 切回对话页时调一下：工作区可能在工作台里换了包。
     *
     * 换了包就**补一张体检卡**：两条入口（对话里选包 / 工作台里开包）应该看到同一份结论，
     * 否则用户会以为「在工作台开的包没有体检」。
     */
    fun refreshWorkspace() {
        val name = WorkspaceHolder.currentName
        if (name == _state.value.workspaceName) return
        _state.update { it.copy(workspaceName = name) }

        val project = WorkspaceHolder.current ?: return
        viewModelScope.launch { addItem(inspectionCard(project, name.orEmpty())) }
    }

    /**
     * 体检卡：读条目名 + meta 就能算出来，**不调模型、不联网**。
     *
     * 放在这一层而不是引擎里，是因为「已装版本是谁签的」要问 PackageManager
     * （引擎是纯 JVM，拿不到），而签名冲突恰好是装机时唯一会撞的墙。
     */
    private suspend fun inspectionCard(project: ApkProject, name: String): ChatItem.Inspection = withContext(Dispatchers.IO) {
        val meta = project.meta
        val entries = runCatching { project.list().map { it.path } }.getOrDefault(emptyList())
        val installedCert = runCatching {
            InstalledApp.certSha256(getApplication(), meta.packageName)
        }.getOrNull()
        ChatItem.Inspection(id(), name, ApkHealthCheck.of(meta, entries, installedCert))
    }

    /**
     * 走一条技能。
     *
     * 三件事必须按顺序做：**先确认能不能跑**（有包、配置齐），再上屏（用户气泡 + 体检式提示），
     * 最后才起一轮。反过来会出现「气泡已经上屏、结果什么都没有」。
     *
     * 纯引导型技能（如换图标）不调模型：它需要选图，只能在界面上做 ——
     * 把这件事说明白，比让模型假装能做要诚实。
     */
    fun runSkill(skill: Skill) {
        if (_state.value.running) return

        if (skill.tools.isEmpty()) {
            addItem(ChatItem.Notice(id(), skill.guide ?: "这条技能要在工作台里做"))
            return
        }
        if (WorkspaceHolder.current == null) {
            addItem(ChatItem.Notice(id(), "还没打开包：点输入框右边的回形针选一个 APK", isError = true))
            return
        }
        if (!ensureReady()) return

        _state.update { it.copy(activeSkill = skill) }
        addItem(ChatItem.User(id(), "技能：${skill.title}"))
        history += ChatMessage.user(skill.prompt)
        startRound()
    }

    fun onConfigChange(config: AiConfig) {
        configStore.save(config)
        _state.update { it.copy(config = config, configProblem = configStore.validate(config)) }
    }

    /**
     * 切换信任模式：开着的话 WRITE 级工具不再逐条问（DESTRUCTIVE 仍然问）。
     *
     * 重建注册表而不是就地改它的策略字段 —— 策略是构造参数，一份策略只有一个来源，
     * 否则迟早出现「界面显示已信任、实际还在逐条问」这种说不清的状态。
     *
     * 切换**不影响正在跑的那一轮**（它已经拿到旧的 registry 引用）。这反而更安全：
     * 半路换策略会让「这一轮里哪些问了、哪些没问」变得没法解释。
     */
    fun setTrustWrites(value: Boolean) {
        configStore.setTrustWrites(value)
        registry = defaultRegistry(ConfirmPolicy(value))
        _state.update { it.copy(trustWrites = value) }
    }

    /**
     * 在对话里直接打开一个 APK 作为工作区，不用先去「工作台」标签。
     *
     * 打开后**替换**掉 `WorkspaceHolder` 里原来那个：两个工程同时开着的话，
     * 「工具到底作用于哪个」是说不清的，而用户默认以为是刚打开的这个。
     * 所以先关掉旧的（保留它的产物，用户可能还要用）。
     *
     * 文件先复制到缓存目录再开：内容提供者给的 URI 不保证能被反复随机读，
     * 而引擎要多次 seek 那个 zip。
     */
    fun attachApk(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "打开 APK…", message = null, isError = false) }
            try {
                val app = getApplication<Application>()
                val file = withContext(Dispatchers.IO) {
                    val rawName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                    val name = rawName.ifBlank { "attached.apk" }
                    val dest = File(app.cacheDir, "chat-attach-$name")
                    app.contentResolver.openInputStream(uri)?.use { ins ->
                        dest.outputStream().use { ins.copyTo(it) }
                    } ?: throw IllegalArgumentException("读不到这个文件")
                    dest
                }

                // 换包之前把旧的关掉 —— 免得两个工程同时活着，改到不该改的那个
                WorkspaceHolder.current?.let { old ->
                    runCatching { old.close(keepArtifacts = true) }
                }

                val project = ApkProjects.open(file)
                WorkspaceHolder.set(project, file.name)

                // 打开包就体检：这一行的结论（能不能改、改完能不能装）是后面所有动作的前提，
                // 而且它是本地算的 —— 不花 token、不等模型
                val card = inspectionCard(project, file.name)
                _state.update { s ->
                    s.copy(
                        busy = null,
                        workspaceName = file.name,
                        // 状态条是单行纯文本，渲染不了加粗 —— 把强调标记去掉，
                        // 否则屏幕上会出现两个星号
                        message = "已打开「${file.name}」：${card.health.headline.withoutEmphasis()}",
                        isError = false,
                    )
                }
                addItem(card)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = null, message = t.message ?: "打不开这个文件", isError = true) }
            }
        }
    }

    /** 一次性提示看过了。 */
    fun clearMessage() = _state.update { it.copy(message = null) }

    // ── 发送 / 停止 ─────────────────────────────────────────────

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.running) return
        if (!ensureReady()) return

        // 自由对话不带技能限制：用户自己说什么，就挂全部工具
        _state.update { it.copy(activeSkill = null) }
        addItem(ChatItem.User(id(), text))
        history += ChatMessage.user(text)
        startRound()
    }

    /**
     * 配置不齐时**直接说缺什么**，不要发一个请求再翻译 401 ——
     * 那要多等一个往返，错误信息还绕。
     */
    private fun ensureReady(): Boolean {
        val problem = configStore.validate(_state.value.config)
        if (problem != null) {
            addItem(ChatItem.Notice(id(), problem, isError = true))
            return false
        }
        return true
    }

    /** 起一轮 Agent。挂哪些工具由当前技能决定（[ChatUiState.activeSkill]）。 */
    private fun startRound() {
        val config = _state.value.config
        val skill = _state.value.activeSkill
        _state.update { it.copy(input = "", running = true) }

        val agent = AgentLoop(
            OpenAiClient(config),
            registry,
            systemPrompt = systemPrompt(),
            allowedTools = skill?.tools?.toSet(),
        )
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
                // 中途停止也要落盘：已经改好的东西不该因为「用户按了停止」就从记录里消失
                runCatching { persistNew() }
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

    /**
     * 开一段新对话。
     *
     * **不是删掉当前会话的记录**，而是另起一个：用户点「清空」多半是想换个话题，
     * 而上一段里「改过哪些东西」还有用（改到一半回头查很常见）。
     */
    fun clear() {
        stop()
        history.clear()
        persistedCount = 0
        nextId = 1
        _state.update { it.copy(items = emptyList()) }
        viewModelScope.launch {
            val id = sessionStore.newSessionId()
            sessionId = id
            runCatching { sessionStore.createSession(id, "新对话", WorkspaceHolder.currentName) }
        }
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
                // 落盘放在 Done 而不是流式过程中：模型可能中途改主意，
                // 只有这一轮定下来的历史才值得写进库
                viewModelScope.launch { runCatching { persistNew() } }
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
                diff = event.diff,
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
