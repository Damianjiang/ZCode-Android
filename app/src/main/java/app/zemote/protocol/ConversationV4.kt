package app.zemote.protocol

import app.zemote.ui.logger.ZemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


/** bootstrap 返回的任务条目（任务会话页主数据源） */
data class TaskEntry(
    val taskId: String,
    val title: String,
    val status: String? = null,
    val workspacePath: String? = null,
    val workspaceLabel: String? = null,
    val updatedAt: Long? = null,
) {
    val running: Boolean get() = status == "running"
}

/** 从 bootstrap 响应解析任务列表（workspaceKey 非空时只保留该工作区的任务） */
suspend fun fetchTasksFromBootstrap(client: ZemoteClient, workspaceKey: String? = null): List<TaskEntry> = withContext(Dispatchers.IO) {
    val res = client.bootstrap()
    val tasks = res["tasks"] as? List<*> ?: emptyList<Any>()
    tasks.mapNotNull { t ->
        val m = t as? Map<*, *> ?: return@mapNotNull null
        val id = m["taskId"]?.toString() ?: return@mapNotNull null
        if (workspaceKey != null) {
            val identity = m["workspaceIdentity"]?.toString()
            val path = m["workspacePath"]?.toString()
            if (identity != workspaceKey && path != workspaceKey) return@mapNotNull null
        }
        TaskEntry(
            taskId = id,
            title = m["title"]?.toString() ?: "Untitled task",
            status = m["displayStatus"]?.toString(),
            workspacePath = m["workspacePath"]?.toString(),
            workspaceLabel = m["workspaceLabel"]?.toString(),
            updatedAt = (m["updatedAt"] as? Number)?.toLong(),
        )
    }
}

/** sessions-index 实时会话条目（subscribeSessionsIndexV4 推送） */
data class SessionEntry(
    val sessionId: String,
    val title: String,
    val phase: String,
    val lastAssistantPreview: String? = null,
    val lastActivityAt: Long = 0,
    val createdAt: Long = 0,
    val hasBackgroundWork: Boolean = false,
) {
    val running: Boolean get() = phase == "running" || phase == "prewarming"
}

/** 排队中的消息（snapshot.queue.items，AI 工作时发送的内容进入此队列） */
data class QueueItem(
    val queueItemId: String,
    val text: String,
    val createdAt: Long? = null,
)

/** 待响应的交互请求（权限审批 / 用户输入 / 计划确认） */
data class PendingInteraction(
    val requestId: String,
    val kind: String,          // "permission" | "userInput" | "workspaceHookReview"
    val title: String,
    val body: String,
    val options: List<InteractionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

data class InteractionOption(
    val optionId: String,
    val label: String,
    val kind: String,          // "allowOnce" | "allowAlways" | "deny" | "custom"
)

/** 后台任务（bash / subagent） */
data class BackgroundWork(
    val workId: String,
    val kind: String,          // "bash" | "subagent"
    val title: String,
    val status: String,        // "running" | "resultPending" | "failed" | "cancelled"
    val startedAt: Long = 0L,
    val cancellable: Boolean = false,
)

/** 对话时间线行（聊天页）。kind 见 [ConvKinds]。rowId 为服务端递增数字。 */
data class ConvRow(
    val rowId: Long,
    val kind: String,
    val text: String = "",
    val inputText: String = "",
    val outputText: String = "",
    val summaryText: String = "",
    val toolName: String? = null,
    val toolStatus: String? = null,
    val state: String? = null,
    val additions: Int? = null,
    val issuedAt: Long? = null,
    val attachments: List<Map<String, String>> = emptyList(),
    /** 子智能体独有：子会话 ID（null 表示非子智能体行） */
    val childSessionId: String? = null,
    /** 子智能体独有：子智能体类型（如 "agent" / "task" / "read" 等） */
    val subagentType: String? = null,
)

object ConvKinds {
    const val TURN_HEADER = "turnHeader"
    const val USER_INPUT = "userInput"
    const val ASSISTANT_TEXT = "assistantText"
    const val REASONING = "reasoning"
    const val TOOL_CALL = "toolCall"
    const val SUBAGENT = "subagent"
    const val IMAGE = "image"
}

/** 行的完成状态集合（用于思考块自动展开/折叠） */
val COMPLETE_STATES = setOf("complete", "completedSuccess", "completedInterrupted", "error", "cancelled", "aborted")

/** 历史窗口加载状态（驱动聊天页的空态 / 失败重试） */
enum class HistoryState { LOADING, READY, EMPTY, FAILED }

/** 会话运行配置（模型 / 思考等级 / 模式），来自状态帧 config */
data class ConvConfig(
    val provider: String? = null,
    val model: String? = null,
    val thought: String? = null,
    val thoughtLevels: List<String> = emptyList(),
    val mode: String? = null,
    val followupMode: String? = null,
) {
    val thoughtSupported: Boolean get() = thoughtLevels.isNotEmpty()
}

/** 上下文用量（官方「上下文容量」弹窗数据） */
data class ConvUsage(
    val usedTokens: Long = 0,
    val maxTokens: Long = 0,
    val hitRate: Double? = null,
    val breakdown: List<Pair<String, Long>> = emptyList(), // source → chars
) {
    val ratio: Float get() = if (maxTokens > 0) (usedTokens.toDouble() / maxTokens).toFloat() else 0f
}

/** 可用模型选项（provider + model 组合），来自 prepareWorkspace 的 configOptions */
data class ModelOption(
    val provider: String,
    val model: String,
    val label: String = model,
)

/** 附件上传结果：ref 用于随 sendText/createSession 发送 */
data class AttachmentUpload(
    val ref: String?,
    val fileName: String,
    val mime: String,
    val bytes: Long,
)

/** 附件读取结果（图片预览等） */
class AttachmentData(val bytes: ByteArray, val mediaType: String?)

/**
 * 发送结果。
 *
 * 官方客户端在 `sendText` 之后会校验 `status`：只有 `accepted` / `duplicate` / `noop`
 * 才算成功，其余（`blocked` / `reject*`）一律当失败处理。本 App 之前完全忽略返回值，
 * 结果是消息被服务端拒绝时输入框已经清空、界面毫无反馈，用户以为"发出去了"。
 */
sealed interface SendOutcome {
    /** 桌面端已接受（accepted / duplicate / noop） */
    data class Accepted(val sessionId: String?) : SendOutcome
    /** 被拒绝：reasonCode 来自协议，可用于提示用户 */
    data class Rejected(val reasonCode: String?, val message: String? = null) : SendOutcome
}

/**
 * Conversation V4 仓库：封装从官方 Web 客户端逆向出的 zcode-agent 通道 RPC。
 * 帧协议与原版 Flutter 实现对齐（lib/protocol/conversation.dart）：
 *
 *  - helloConversationV4 → initializeConversationV4(clientHello, appVersion=3.6.5)
 *  - subscribeConversationV4(scope+sessionId) → {ack:{subscriptionId, logEpoch}}
 *  - onDynamicConversationFrame 推送 wire 帧：{kind:'complete'|'fragment', topic,
 *    subscriptionId, frame|fragment*}；complete 内层是 {fromSeq, toSeq, payload}，
 *    payload = {kind:'snapshot'} | {kind:'deltas'}；fragment 需 base64 分片重组
 *  - seq 断层 → resyncConversationV4(forceSnapshot)；静默 20s 且运行中 → 看门狗 resync
 *  - 会话列表：subscribeSessionsIndexV4 + onDynamicSessionsIndexFrame
 *  - 命令：sendConversationCommandV4(scope+envelope)，CAS 命令须带 baseRevision，
 *    stale 时按 revisionAtDecision 重试一次
 */
class ConversationV4Session private constructor(
    val client: ZemoteClient,
    val bridge: BridgeSession,
    val workspaceKey: String,
    private val scopeParams: Map<String, Any>? = null,
) {
    companion object {
        /** 桌面对话协议能力版本。发本 App 版本号（0.x/1.x）会导致 V4 能力协商失败。 */
        const val PROTOCOL_APP_VERSION = "3.6.5"

        /** 流式 delta 批量提交间隔：把 token 级更新合并到 ~16fps，避免每个 token 重建整张列表 */
        private const val DELTA_FLUSH_INTERVAL_MS = 60L

        /** 订阅会话超时：桌面端可能要预热会话运行时，官方给 60s；过短会拿不到 ack → 历史永远进不来 */
        private const val SUBSCRIBE_TIMEOUT_MS = 45_000L

        /**
         * 队列被占用时服务端返回的 reasonCode。官方要求客户端明确二选一：
         * `clearQueueAndSend`（丢弃已排队消息）或 `keepQueueAndSend`（保留）。
         */
        private const val HELD_QUEUE_STALE = "guard.heldQueueConfirmationStale"

        /** 视为发送成功的 ack 状态（对齐官方 LOe） */
        private val SEND_OK_STATUSES = setOf("accepted", "duplicate", "noop")

        private val CLIENT_ID = UUID.randomUUID().toString()

        /** CAS 命令集合：信封必须携带 baseRevision（对齐官方 eAe） */
        private val CAS_COMMANDS = setOf(
            "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
            "setAssistantFeedback", "sendQueuedNow", "editQueueItem",
            "reorderQueueItem", "deleteQueueItem", "setAutoDrain",
            "switchModelConfig", "switchCollaborationMode", "setFollowupMode",
            "pauseGoal", "resumeGoal",
        )

        /** 打开 workspace bridge 并创建会话仓库（单工作区单桥，订阅在仓库内按需切换）。 */
        suspend fun open(
            client: ZemoteClient,
            workspaceKey: String,
            scopeParams: Map<String, Any>? = null,
        ): ConversationV4Session = withContext(Dispatchers.IO) {
            val bridge = client.openBridge(workspaceKey)
            ConversationV4Session(client, bridge, workspaceKey, scopeParams)
        }
    }

    private val channels get() = bridge.channelsClient
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 状态（UI 绑定） ──
    private val _rows = MutableStateFlow<List<ConvRow>>(emptyList())
    val rows: StateFlow<List<ConvRow>> = _rows.asStateFlow()

    /** rows 的读改写互斥：流式 flush 与 loadRows 分页可能并发写，加锁避免丢更新 */
    private val rowsLock = Any()

    /**
     * rows 的唯一发布入口。
     *
     * 两条不变量，UI 侧的性能依赖它们，改动前请先确认没有破坏：
     * 1. 内容未变的行必须复用同一个 ConvRow 实例（不要无条件 copy），
     *    ChatAndTasksScreen 的 buildDisplayItems 靠 `===` 判断条目能否复用；
     * 2. 这里不再对每一行做 `row.copy(version = ...)`——旧实现每个 token 都要
     *    拷贝整张列表的所有行（O(n) 分配），是流式卡顿的主要来源。
     */
    private fun publishRows(newRows: List<ConvRow>) {
        synchronized(rowsLock) { _rows.value = newRows }
    }

    /** 结构性替换入口：先落盘缓冲中的流式文本，保证追加顺序不被打乱 */
    private fun setRows(newRows: List<ConvRow>) {
        flushPendingDeltas()
        publishRows(newRows)
    }

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _agentWorking = MutableStateFlow(false)
    val agentWorking: StateFlow<Boolean> = _agentWorking.asStateFlow()

    private val _convConfig = MutableStateFlow<ConvConfig?>(null)
    val convConfig: StateFlow<ConvConfig?> = _convConfig.asStateFlow()

    private val _usage = MutableStateFlow<ConvUsage?>(null)
    val usage: StateFlow<ConvUsage?> = _usage.asStateFlow()

    private val _modelOptions = MutableStateFlow<List<ModelOption>>(emptyList())
    val modelOptions: StateFlow<List<ModelOption>> = _modelOptions.asStateFlow()

    private val _stopWorkId = MutableStateFlow<String?>(null)
    val stopWorkId: StateFlow<String?> = _stopWorkId.asStateFlow()

    private val _followupMode = MutableStateFlow<String?>(null)
    val followupMode: StateFlow<String?> = _followupMode.asStateFlow()

    /** 排队中的消息（官方队列卡片数据源） */
    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueItems: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()

    /** 队列自动发送开关（queue.autoDrain，默认开） */
    private val _autoDrain = MutableStateFlow(true)
    val autoDrain: StateFlow<Boolean> = _autoDrain.asStateFlow()

    /** sessions-index 实时会话列表（按最近活动倒序） */
    private val _sessionEntries = MutableStateFlow<List<SessionEntry>>(emptyList())
    val sessionEntries: StateFlow<List<SessionEntry>> = _sessionEntries.asStateFlow()

    /** 待响应的交互请求（权限审批 / 用户输入 / 计划确认） */
    private val _pendingInteractions = MutableStateFlow<List<PendingInteraction>>(emptyList())
    val pendingInteractions: StateFlow<List<PendingInteraction>> = _pendingInteractions.asStateFlow()

    /** 后台运行中的任务（bash / subagent） */
    private val _backgroundWorks = MutableStateFlow<List<BackgroundWork>>(emptyList())
    val backgroundWorks: StateFlow<List<BackgroundWork>> = _backgroundWorks.asStateFlow()

    /**
     * 历史加载状态。UI 用它区分「正在加载 / 已就绪 / 确实为空 / 加载失败」，
     * 避免订阅失败时页面一片空白、用户以为"卡死"。
     */
    private val _historyState = MutableStateFlow(HistoryState.LOADING)
    val historyState: StateFlow<HistoryState> = _historyState.asStateFlow()

    // ── 内部协议状态 ──
    /** 握手版本号：每次 rebuildSubscriptions 递增，ensureHandshake 会跳过过期请求 */
    @Volatile private var handshakeGen = 0
    @Volatile private var handshakeDone = false
    private var connectionId: String? = null
    private var revision = 0L
    private val ackedRevisions = ConcurrentHashMap<String, Long>()

    // conversation 订阅
    private var convSubId: String? = null
    private var convCancel: (() -> Unit)? = null
    private var convLogEpoch: String? = null
    private var convSeq = 0L
    private var convWatchdog: Job? = null
    private var lastFrameAt = 0L
    private var resyncing = false
    private val stagedFrames = mutableListOf<Map<*, *>>()

    // ── 流式 delta 合并缓冲 ──
    /** rowId → (字段路径 → 累积文本)。把高频 token 合并成 ~16 次/秒的批量提交。 */
    private class DeltaBuffer {
        val parts = LinkedHashMap<String, StringBuilder>()
        fun append(path: String, text: String) {
            parts.getOrPut(path) { StringBuilder() }.append(text)
        }
    }
    private val pendingDeltas = HashMap<Long, DeltaBuffer>()
    private var deltaFlushJob: Job? = null

    // sessions-index 订阅
    private var siSubId: String? = null
    private var siCancel: (() -> Unit)? = null
    private var siLogEpoch: String? = null
    private var siSeq = 0L
    private var siResyncing = false
    private val siStaged = mutableListOf<Map<*, *>>()

    // prepareWorkspace 缓存（模型/思考等级选项）
    private var prepThoughtLevels: List<String> = emptyList()

    // 历史窗口游标（对齐 Flutter ConversationState）
    var firstRowId: Long? = null
        internal set
    var totalCount: Long = 0L
        internal set
    /**
     * 服务端返回的 hasMore 标志：true 表示还有更早的历史未加载。
     *
     * 旧实现是普通 `var`，UI 里 `repo.hasOlderHistory` 直接读它 —— 服务端翻页结果
     * 变化时不会触发任何重组，于是「加载更早消息」按钮的显示/隐藏与真实状态脱节
     * （明明还有更早历史，按钮却不出现）。这里额外暴露 [hasMoreFlow] 供 UI 观察。
     */
    private var hasMoreRaw: Boolean = false
    private val _hasMore = MutableStateFlow(false)
    val hasMoreFlow: StateFlow<Boolean> = _hasMore.asStateFlow()
    var hasMore: Boolean
        get() = hasMoreRaw
        internal set(value) {
            if (hasMoreRaw == value) return
            hasMoreRaw = value
            _hasMore.value = value
        }

    // openConversation 进行中标志：恢复重建必须避让，否则会拆掉刚建立的订阅，
    // 导致历史/模型等数据永远拉不到
    @Volatile private var opening = false
    private var rebuilding = false

    init {
        // bridge 恢复后异步重建，不阻塞任何 openConversation 调用
        sessionScope.launch {
            bridge.recovered.collect { count ->
                if (count <= 0 || bridge.isDisposed) return@collect
                log("[v4] bridge recovered (count=$count), scheduling async rebuild")
                sessionScope.launch { rebuildSubscriptions() }
            }
        }
    }

    /** 由 [ZemoteClient.recoverActiveBridges] 在桥接恢复成功后调用，重建握手和所有订阅。 */
    /**
     * Bridge 恢复后仅重新注册事件监听 + 重新订阅，不清空任何状态。
     * 对齐官方 Flutter 的 _resubscribe()：保留 rows/config/revision 等，
     * 只断掉旧的 frame listener，在新生成的 ChannelClient 上重建。
     */
    suspend fun rebuildSubscriptions() {
        if (rebuilding) return
        rebuilding = true
        try {
            log("[v4] bridge recovered, resubscribing only (preserving state)")
            flushPendingDeltas()
            val hadSessionsIndex = siSubId != null
            val activeId = _activeSessionId.value
            handshakeGen++
            // 只清理旧的 listener，不清空 rows / stagedFrames / 其他状态
            convWatchdog?.cancel()
            convCancel?.invoke(); convCancel = null
            convSubId = null
            siCancel?.invoke(); siCancel = null
            siSubId = null
            synchronized(stagedFrames) { stagedFrames.clear() }
            synchronized(siStaged) { siStaged.clear() }
            // 重新握手：新 bridge 需要重新建立 IPC 连接，必须重置状态
            handshakeDone = false
            connectionId = null
            runCatching { ensureHandshake() }
                .onFailure { log("[v4] handshake after recovery failed: $it"); return }
            // 重新订阅（不重置 rows，快照帧会自动填充）
            if (activeId != null) {
                runCatching { subscribeConversation(activeId) }
                    .onFailure { log("[v4] resubscribe failed: $it") }
            }
            if (hadSessionsIndex) runCatching { openSessionsIndex() }
        } finally {
            rebuilding = false
        }
    }

    // ── scope：只保留官方使用的两个字段（多余字段可能干扰服务端校验） ──
    private fun scope(): Map<String, Any> = buildMap {
        val path = (scopeParams?.get("workspacePath") ?: workspaceKey)?.toString()
        val identity = (scopeParams?.get("workspaceIdentity") ?: workspaceKey)?.toString()
        if (path != null) put("workspacePath", path)
        if (identity != null) put("workspaceIdentity", identity)
    }

    private fun log(msg: String) {
        client.onLog?.invoke(msg)
    }

    // ────────────────────────── 握手 ──────────────────────────

    /** hello + clientHello 握手（每个 bridge 连接一次） */
    private suspend fun ensureHandshake() {
        // 快照当前握手版本，防止并发请求互相干扰
        val gen = handshakeGen
        if (handshakeDone) return
        if (!sessionScope.isActive) return  // scope 已取消，不发起握手
        val hello = call("helloConversationV4", emptyList(), isActiveCheck = { sessionScope.isActive }) as? Map<*, *>
        if (!sessionScope.isActive) return
        // 如果握手期间有 rebuildSubscriptions 被触发，停止
        if (gen != handshakeGen) return
        connectionId = hello?.get("connectionId")?.toString()
        // 对齐官方：clientKind 由服务端 hello 返回的 clientMode 决定（desktop 或 web），
        // appVersion 固定传 'unknown'（与官方一致），避免版本校验拦截
        val clientMode = hello?.get("clientMode")?.toString()
        val clientKind = if (clientMode == "desktop-continuous") "desktop" else "web"
        call(
            "initializeConversationV4",
            listOf(mapOf<String, Any>(
                "kind" to "clientHello",
                "protocolVersion" to 3L,
                "clientId" to CLIENT_ID,
                "clientKind" to clientKind,
                "appVersion" to "unknown",
                "capabilities" to mapOf("workspaceHookReviewUi" to true),
            )),
        )
        if (gen != handshakeGen) return  // 重建后跳过
        handshakeDone = true
    }

    // ────────────────────────── 订阅生命周期 ──────────────────────────

    /**
     * 订阅指定会话并拉取历史。必须在 IO 线程调用（Compose 的
     * AndroidUiDispatcher 在静态界面不产生帧，withTimeout 会被饿死）。
     */
    suspend fun openConversation(sessionId: String?, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && _activeSessionId.value == sessionId && _rows.value.isNotEmpty()) return@withContext
        ZemoteLogger.info("v4", "openConversation${if (force) " [force]" else ""} sessionId=$sessionId")
        opening = true
        try {
            openConversationInternal(sessionId)
        } finally {
            opening = false
        }
        // 不再在此处同步触发 rebuild；bridge 恢复事件会通过 init 中的 collector 异步处理
    }

    private suspend fun openConversationInternal(sessionId: String?) {
        unsubscribeConversation()
        _activeSessionId.value = sessionId
        clearPendingDeltas()
        setRows(emptyList())
        firstRowId = null
        totalCount = 0
        hasMore = false
        convSeq = 0
        convLogEpoch = null
        snapshotSeen = false
        _pendingPatch = null
        ackedRevisions.clear()
        synchronized(stagedFrames) { stagedFrames.clear() }
        synchronized(siStaged) { siStaged.clear() }
        _historyState.value = HistoryState.LOADING
        // 握手已有缓存，通常 < 100ms；超时设短避免阻塞
        runCatching { withTimeout(5_000) { ensureHandshake() } }
            .onFailure { log("[v4] handshake failed: $it") }
        if (!sessionScope.isActive) return
        // 模型/思考档位选项：后台加载，不阻塞界面
        sessionScope.launch { runCatching { prepareWorkspace() } }
        if (sessionId == null) {
            // 新对话：没有历史可加载
            _historyState.value = HistoryState.EMPTY
            return
        }
        // 订阅会话：异步启动，不阻塞界面；订阅失败时立刻降级为主动拉窗口，
        // 否则 UI 会一直停在"加载中"（旧实现就是这样静默失败的）
        sessionScope.launch {
            val ok = runCatching { subscribeConversation(sessionId) }.getOrDefault(false)
            if (!ok && _rows.value.isEmpty() && _historyState.value == HistoryState.LOADING) {
                log("[v4] subscribe failed, fallback to loadRows")
                // loadRows 内部会在软失败时把 _historyState 置为 FAILED，
                // 所以这里不再依赖异常来判断失败
                runCatching { withTimeout(15_000) { loadRows(sessionId, limit = 200) } }
                    .onFailure {
                        log("[v4] loadRows fallback failed: $it")
                        markHistoryLoadFailed("fallback threw: ${it.message}")
                    }
            }
        }
        sessionScope.launch {
            runCatching { openSessionsIndex() }
                .onFailure { log("[v4] sessions-index open failed: $it") }
        }
        // 安全网：2s 内无任何数据则主动拉一次历史窗口。
        // 只在状态仍是 LOADING 时触发 —— 否则会跟在 fallback 后面重复发一次同样的 RPC。
        sessionScope.launch {
            delay(2_000)
            if (sessionScope.isActive && _rows.value.isEmpty() &&
                _historyState.value == HistoryState.LOADING
            ) {
                log("[v4] 2s 内无数据，触发 loadRows 兜底")
                runCatching { withTimeout(10_000) { loadRows(sessionId, limit = 200) } }
                    .onFailure {
                        log("[v4] loadRows safety net failed: $it")
                        markHistoryLoadFailed("safety net threw: ${it.message}")
                    }
            }
        }
    }

    /** 历史加载失败后的手动重试：重新订阅 + 主动拉一次历史窗口 */
    suspend fun retryHistory(sessionId: String? = _activeSessionId.value) = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext
        _historyState.value = HistoryState.LOADING
        runCatching { withTimeout(5_000) { ensureHandshake() } }
            .onFailure { log("[v4] retryHistory handshake failed: $it") }
        val ok = runCatching { subscribeConversation(sid) }.getOrDefault(false)
        if (ok) {
            // 订阅成功 ≠ 数据到了：桌面端要预热会话运行时，快照可能几秒后才推。
            // 旧实现只看 subscribe 的返回值，于是「订阅成功但快照没来」时会永久停在
            // LOADING、页面一直转圈 —— 这是「历史持续加载失败」的另一半原因。
            var waited = 0L
            while (waited < 3_000 && _rows.value.isEmpty() && sessionScope.isActive) {
                delay(200)
                waited += 200
            }
        }
        if (_rows.value.isEmpty()) {
            runCatching { withTimeout(15_000) { loadRows(sid, limit = 200) } }
                .onFailure { log("[v4] retryHistory loadRows failed: $it") }
        }
        // loadRows 已经把「确实为空」置为 EMPTY，这里不要把它误判成失败
        if (_rows.value.isEmpty() && _historyState.value != HistoryState.EMPTY) {
            _historyState.value = HistoryState.FAILED
        }
    }

    /**
     * 订阅会话帧。返回是否拿到 ack.subscriptionId。
     * 桌面端可能要预热会话运行时（官方给 60s），超时过短会导致拿不到 ack，
     * 之后所有历史帧都进不来——表现为"会话一直加载不出来"。这里给足超时并重试一次。
     */
    private suspend fun subscribeConversation(sessionId: String): Boolean {
        if (!sessionScope.isActive) return false
        convCancel?.invoke()
        convCancel = null
        val listener = channels.addEventListener(
            ChannelClient.Channel.ZCODE_AGENT,
            "onDynamicConversationFrame",
            onEvent = ::handleConversationEvent,
            arg = scope(),
        )
        // 注册后立即保存 listener，无论 subscribeConversationV4 成功或失败都能正确清理
        convCancel = listener
        var callResult: Any? = null
        for (attempt in 0 until 2) {
            callResult = runCatching {
                call(
                    "subscribeConversationV4",
                    listOf(scope() + mapOf("sessionId" to sessionId)),
                    timeoutMs = SUBSCRIBE_TIMEOUT_MS,
                )
            }.getOrNull()
            if (callResult != null) break
            // scope 已取消（页面离开等）→ 静默退出，不打印噪声日志
            if (!sessionScope.isActive) {
                convCancel?.invoke(); convCancel = null
                return false
            }
            if (attempt == 0) {
                log("[v4] subscribeConversationV4 timed out, retrying once")
                delay(600)
            }
        }
        if (callResult == null) {
            log("[v4] subscribeConversationV4 failed after retry")
            convCancel?.invoke(); convCancel = null
            return false
        }
        val ack = (callResult as? Map<*, *>)?.get("ack") as? Map<*, *>
        convSubId = ack?.get("subscriptionId")?.toString()
        ack?.get("logEpoch")?.toString()?.let { convLogEpoch = it }
        if (convSubId == null) {
            log("[v4] subscribeConversationV4: missing ack.subscriptionId")
            convCancel?.invoke(); convCancel = null
            return false
        }
        // 应答前到达的帧按序回放
        val staged = synchronized(stagedFrames) {
            val copy = stagedFrames.toList()
            stagedFrames.clear()
            copy
        }
        staged.forEach { acceptLogicalFrame(it) }
        startWatchdog()
        return true
    }

    private fun unsubscribeConversation() {
        convWatchdog?.cancel()
        convWatchdog = null
        convCancel?.invoke()
        convCancel = null
        val id = convSubId
        convSubId = null
        _agentWorking.value = false
        _stopWorkId.value = null
        _pendingInteractions.value = emptyList()
        _backgroundWorks.value = emptyList()
        if (id != null) {
            // 使用 sessionScope 而非裸 CoroutineScope，确保 dispose 时能随会话一起取消
            sessionScope.launch {
                runCatching {
                    call("unsubscribeConversationV4", listOf(scope() + mapOf("subscriptionId" to id)), isActiveCheck = { sessionScope.isActive })
                }
            }
        }
    }

    // ────────────────────────── wire 帧层 ──────────────────────────

    private class FragmentAssembly(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        val createdAt = System.currentTimeMillis()
        var received = 0

        fun add(index: Int, data: ByteArray) {
            if (index !in parts.indices) return
            if (parts[index] == null) received += 1
            parts[index] = data
        }

        val complete: Boolean get() = received == count
        fun assemble(): ByteArray = parts.filterNotNull().reduce { a, b -> a + b }
    }

    private val fragments = ConcurrentHashMap<String, FragmentAssembly>()

    /** 上一次清理碎片重组缓冲的时间。流式期间每个帧都会走到这里，必须节流。 */
    private var lastFragmentPurgeAt = 0L

    /**
     * 丢弃超时未凑齐的分片。
     * 旧实现对**每个**入站帧都做一次全表 `removeIf` —— 流式输出时帧率很高，
     * 虽然通常表是空的，但每次都要走一遍 ConcurrentHashMap 的 entries 迭代。
     * 这里节流到最多 1s 一次；TTL 是 60s，节流不会导致碎片提前被清掉。
     */
    private fun purgeStaleFragments() {
        val now = System.currentTimeMillis()
        if (now - lastFragmentPurgeAt < 1_000) return
        lastFragmentPurgeAt = now
        fragments.entries.removeIf { now - it.value.createdAt > 60_000 }
    }

    /** fragment 帧重组：base64 分片按序拼接 → JSON → 逻辑帧 */
    private fun assembleFragment(wire: Map<*, *>): Map<*, *>? {
        val id = wire["logicalFrameId"]?.toString() ?: return null
        val index = (wire["fragmentIndex"] as? Number)?.toInt() ?: return null
        val count = (wire["fragmentCount"] as? Number)?.toInt() ?: return null
        val dataB64 = wire["dataBase64"]?.toString() ?: return null
        if (count < 1 || count > 64 || index < 0 || index >= count) return null
        val assembly = fragments.computeIfAbsent(id) { FragmentAssembly(count) }
        if (assembly.count != count) {
            fragments.remove(id)
            return null
        }
        val bytes = try {
            Base64.getDecoder().decode(dataB64)
        } catch (e: IllegalArgumentException) {
            fragments.remove(id)
            return null
        }
        assembly.add(index, bytes)
        if (!assembly.complete) return null
        fragments.remove(id)
        return try {
            val text = String(assembly.assemble(), Charsets.UTF_8).trim()
            jsonToNative(org.json.JSONTokener(text).nextValue()) as? Map<*, *>
        } catch (e: Exception) {
            log("[v4] bad logical frame: ${e.message}")
            null
        }
    }

    private fun jsonToNative(v: Any?): Any? = when (v) {
        is org.json.JSONObject -> {
            val m = LinkedHashMap<String, Any?>()
            for (k in v.keys()) m[k] = jsonToNative(v.opt(k))
            m
        }
        is org.json.JSONArray -> (0 until v.length()).map { jsonToNative(v.opt(it)) }
        else -> v
    }

    /** onDynamicConversationFrame 事件入口：拆 wire 封装（complete/fragment） */
    private fun handleConversationEvent(data: Any?) {
        val raw = (data as? List<*>)?.firstOrNull() ?: data
        val wire = raw as? Map<*, *> ?: return
        purgeStaleFragments()
        val topic = wire["topic"] as? String
        val inner = when (wire["kind"] as? String) {
            "complete" -> wire["frame"] as? Map<*, *>
            "fragment" -> assembleFragment(wire)
            // 兼容：无 wire 封装、直接下发逻辑帧的桌面版本
            else -> if (wire["payload"] != null) wire else null
        } ?: return
        if (topic != null && !topic.startsWith("conversation/")) return
        acceptLogicalFrame(inner)
    }

    private fun acceptLogicalFrame(frame: Map<*, *>) {
        val subId = convSubId
        if (subId == null) {
            synchronized(stagedFrames) { stagedFrames.add(frame) }
            return
        }
        if (frame["subscriptionId"]?.toString() != subId) return
        lastFrameAt = System.currentTimeMillis()
        applyConversationFrame(frame)
    }

    // ────────────────────────── 逻辑帧 → 状态 ──────────────────────────

    private fun applyConversationFrame(frame: Map<*, *>) {
        try {
            val payload = frame["payload"] as? Map<*, *> ?: return
            val toSeq = (frame["toSeq"] as? Number)?.toLong() ?: convSeq
            when (payload["kind"] as? String) {
                "snapshot" -> applySnapshot(payload["snapshot"], toSeq)
                "deltas" -> {
                    val fromSeq = (frame["fromSeq"] as? Number)?.toLong() ?: convSeq
                    if (fromSeq != convSeq) {
                        // seq 断层：本端丢帧，强制服务端补发快照
                        resyncConversation()
                        return
                    }
                    applyDeltas(payload["deltas"] as? List<*>)
                    convSeq = toSeq
                }
            }
        } catch (e: Exception) {
            log("[v4] frame error: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    /**
     * 快照：window 全量替换、窗口之前已加载的更早历史行保留（对齐官方
     * `_applySnapshot`），config/usage/control/revision 一并更新。
     */
    private fun applySnapshot(snapAny: Any?, toSeq: Long) {
        var snap = snapAny as? Map<*, *> ?: return
        convSeq = toSeq
        snapshotSeen = true
        // state.updated 先于 snapshot 到达时缓冲的补丁，落快照时合并回来
        val pending = _pendingPatch
        if (pending != null) {
            _pendingPatch = null
            val merged = LinkedHashMap<String, Any?>()
            snap.forEach { (k, v) -> merged[k.toString()] = v }
            merged.putAll(pending)
            snap = merged
        }
        convLogEpoch = snap["logEpoch"]?.toString()
        revision = (snap["revision"] as? Number)?.toLong() ?: revision
        (snap["config"] as? Map<*, *>)?.let(::mergeConfig)
        (snap["usage"] as? Map<*, *>)?.let(::mergeUsage)
        (snap["control"] as? Map<*, *>)?.let(::mergeControl)
        (snap["queue"] as? Map<*, *>)?.let(::mergeQueue)
        (snap["pendingInteractions"] as? List<*>)?.let(::mergeInteractions)
        (snap["backgroundWorks"] as? List<*>)?.let(::mergeBackgroundWorks)
        val rowsObj = snap["rows"] as? Map<*, *>
        if (rowsObj != null) {
            val window = (rowsObj["window"] as? List<*>)
                ?.mapNotNull(::parseRow)
                .orEmpty()
            // 窗口之前的更早历史行必须保留；window 为空时不能把已加载的历史清掉
            val older = if (window.isNotEmpty()) {
                val head = window.first().rowId
                _rows.value.filter { it.rowId < head }
            } else {
                _rows.value
            }
            setRows((older + window).sortedBy { it.rowId })
            totalCount = (rowsObj["totalCount"] as? Number)?.toLong() ?: _rows.value.size.toLong()
            firstRowId = (rowsObj["firstRowId"] as? Number)?.toLong()
            _historyState.value = if (_rows.value.isEmpty()) HistoryState.EMPTY else HistoryState.READY
        } else {
            setRows(emptyList())
            totalCount = 0
            firstRowId = null
            _historyState.value = HistoryState.EMPTY
        }
    }

    private var _pendingPatch: Map<String, Any?>? = null
    private var snapshotSeen = false

    /** 增量：row.appended / row.upserted / row.removed / row.delta / state.updated */
    private fun applyDeltas(ops: List<*>?) {
        if (ops == null) return
        for (raw in ops) {
            val m = raw as? Map<*, *> ?: continue
            when (m["op"] as? String) {
                "row.appended" -> {
                    flushPendingDeltas()
                    parseRow(m["row"])?.let { row ->
                        synchronized(rowsLock) {
                            val current = _rows.value
                            val last = current.lastOrNull()
                            if (last != null && row.rowId <= last.rowId) {
                                // 乱序 / 重放（如 resync 后服务端重发同一行）→ 退化为 upsert。
                                // 直接 append 会产生重复 rowId，LazyColumn 的 key 冲突会
                                // 直接抛异常或把条目错位，这是「行为与预期不符」的来源之一。
                                mergeRowLocked(row)
                            } else {
                                // 该行可能已有先到的流式 delta 被缓冲（row.delta 早于
                                // row.appended 到达），就地补上，否则这段文本要等下一次
                                // flush 才出现（流式看起来就是"卡一下才蹦出来"）。
                                val buffered = synchronized(pendingDeltas) { pendingDeltas.remove(row.rowId) }
                                val merged = if (buffered != null) {
                                    var r = row
                                    for ((path, sb) in buffered.parts) r = appendToRow(r, path, sb.toString())
                                    r
                                } else {
                                    row
                                }
                                publishRows(current + merged)
                                totalCount += 1
                                if (firstRowId == null) firstRowId = merged.rowId
                            }
                        }
                    }
                }
                "row.upserted" -> {
                    flushPendingDeltas()
                    parseRow(m["row"])?.let(::mergeRow)
                }
                "row.removed" -> {
                    // 保留 rowId < fromRowId 的行（对齐官方 fke 语义）
                    val from = (m["fromRowId"] as? Number)?.toLong() ?: continue
                    flushPendingDeltas()
                    synchronized(rowsLock) {
                        val before = _rows.value.size
                        publishRows(_rows.value.filter { it.rowId < from })
                        val removed = before - _rows.value.size
                        if (firstRowId != null && from <= firstRowId!!) {
                            totalCount = 0
                            firstRowId = null
                        } else {
                            totalCount = (totalCount - removed).coerceAtLeast(0)
                        }
                    }
                }
                "row.delta" -> {
                    val rid = (m["rowId"] as? Number)?.toLong()
                        ?: m["rowId"]?.toString()?.toLongOrNull() ?: continue
                    val path = m["path"]?.toString() ?: continue
                    val append = m["append"]?.toString() ?: continue
                    // 不再逐 token 重建列表：先缓冲，由 flushPendingDeltas 批量提交
                    synchronized(pendingDeltas) {
                        pendingDeltas.getOrPut(rid) { DeltaBuffer() }.append(path, append)
                    }
                    scheduleDeltaFlush()
                }
                "state.updated" -> {
                    val patch = m["patch"] as? Map<*, *> ?: continue
                    (patch["config"] as? Map<*, *>)?.let(::mergeConfig)
                    (patch["usage"] as? Map<*, *>)?.let(::mergeUsage)
                    (patch["control"] as? Map<*, *>)?.let(::mergeControl)
                    (patch["queue"] as? Map<*, *>)?.let(::mergeQueue)
                    (patch["pendingInteractions"] as? List<*>)?.let(::mergeInteractions)
                    (patch["backgroundWorks"] as? List<*>)?.let(::mergeBackgroundWorks)
                    (patch["revision"] as? Number)?.toLong()?.let { revision = it }
                    if (patch.containsKey("working")) {
                        _agentWorking.value = patch["working"] == true
                    }
                    // 快照未到达时缓冲补丁，快照落地时合并（否则 config/queue 等被静默丢弃）
                    if (!snapshotSeen) {
                        val typed = patch as? Map<String, Any?>
                            ?: patch.entries.associate { (k, v) -> k.toString() to v }
                        _pendingPatch = (_pendingPatch ?: emptyMap()) + typed
                    }
                }
            }
        }
        // 安全网：本轮若还有没落到行上的 delta（例如行是随后由 row.upserted 补进来的），
        // 排一次 flush 把它们提交掉。否则这段文本要等下一个 row.delta 才出现，
        // 流式末尾就会"卡住不动"。
        synchronized(pendingDeltas) {
            if (pendingDeltas.isNotEmpty()) scheduleDeltaFlush()
        }
    }

    // ────────────────────────── 流式 delta 批量提交 ──────────────────────────

    private fun scheduleDeltaFlush() {
        if (deltaFlushJob?.isActive == true) return
        deltaFlushJob = sessionScope.launch {
            delay(DELTA_FLUSH_INTERVAL_MS)
            flushPendingDeltas()
        }
    }

    /**
     * 把缓冲中的流式文本合并进 rows（每个字段一次拼接，而不是每个 token 一次）。
     * 内容变化不递增结构版本，UI 因此不会重新分组、不会重排整张列表。
     *
     * 注意：**只移除真正落到行上的那些 delta**。旧实现一进来就把整个
     * `pendingDeltas` 清空，凡是本轮没在 `rows` 里找到对应行的 delta 就被
     * 静默丢弃（流式期间 `row.delta` 先于 `row.appended` 到达、或行位于尚未
     * 加载的历史窗口里都会命中），表现为「AI 回复少了一段字」。
     */
    private fun flushPendingDeltas() {
        val batch: Map<Long, DeltaBuffer>
        synchronized(pendingDeltas) {
            if (pendingDeltas.isEmpty()) return
            batch = HashMap(pendingDeltas)
        }
        synchronized(rowsLock) {
            val current = _rows.value
            if (current.isEmpty()) return
            var changed = false
            val applied = ArrayList<Long>(batch.size)
            val updated = ArrayList<ConvRow>(current.size)
            for (row in current) {
                val buf = batch[row.rowId]
                if (buf == null) {
                    updated.add(row)
                    continue
                }
                var merged = row
                for ((path, sb) in buf.parts) merged = appendToRow(merged, path, sb.toString())
                if (merged !== row) changed = true
                applied.add(row.rowId)
                updated.add(merged)
            }
            synchronized(pendingDeltas) {
                applied.forEach { pendingDeltas.remove(it) }
                // 兜底：行始终没到（例如服务端只发了 delta）时不要让缓冲无限增长
                if (pendingDeltas.size > 512) pendingDeltas.clear()
            }
            if (changed) publishRows(updated)
        }
    }

    /** 丢弃未提交的流式缓冲（切换会话 / 释放时调用） */
    private fun clearPendingDeltas() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        synchronized(pendingDeltas) { pendingDeltas.clear() }
    }

    /** 追加流式文本：字段按行类型严格对应（对齐官方 dke） */
    private fun appendToRow(row: ConvRow, path: String, append: String): ConvRow = when (path) {
        "text" -> if (row.kind == ConvKinds.ASSISTANT_TEXT || row.kind == ConvKinds.REASONING) {
            row.copy(text = row.text + append)
        } else row
        "inputText" -> if (row.kind == ConvKinds.TOOL_CALL) {
            row.copy(inputText = row.inputText + append)
        } else row
        "output.text" -> if (row.kind == ConvKinds.TOOL_CALL) {
            row.copy(outputText = row.outputText + append)
        } else row
        "summaryText" -> if (row.kind == ConvKinds.SUBAGENT) {
            row.copy(summaryText = row.summaryText + append)
        } else row
        else -> row
    }

    /** 看门狗：运行中静默 20s 无帧 → resync（对齐官方 watchdog） */
    private fun startWatchdog() {
        convWatchdog?.cancel()
        lastFrameAt = System.currentTimeMillis()
        convWatchdog = sessionScope.launch {
            while (isActive) {
                delay(10_000)
                val quiet = System.currentTimeMillis() - lastFrameAt
                if (quiet < 20_000) continue
                val streaming = _rows.value.any { it.state == "streaming" }
                if (_agentWorking.value || streaming) resyncConversation()
            }
        }
    }

    private fun resyncConversation() {
        val id = convSubId
        if (id == null || resyncing || !sessionScope.isActive) return
        resyncing = true
        log("[v4] resync (gap) seq=$convSeq logEpoch=$convLogEpoch")
        sessionScope.launch {
            try {
                call(
                    "resyncConversationV4",
                    listOf(scope() + mapOf(
                        "subscriptionId" to id,
                        "forceSnapshot" to true,
                        "base" to mapOf("logEpoch" to convLogEpoch, "seq" to convSeq),
                    )),
                    isActiveCheck = { sessionScope.isActive },
                )
            } catch (e: Exception) {
                // 必须区分普通异常和协程取消（bridge swap 等导致）；两种情况都需要清除 resyncing
                log("[v4] resync failed: ${e.message}")
            } finally {
                resyncing = false
            }
        }
    }

    // ────────────────────────── 历史窗口 ──────────────────────────

    /**
     * 历史加载串行化。
     *
     * 打开会话时有三条兜底路径可能同时发起加载（订阅失败回退 / 2s 安全网 /
     * 用户手动重试），旧实现让它们并发跑：同一条请求被重复下发，
     * 先返回的页会被后返回的页覆盖，表现为历史时多时少甚至一片空白。
     */
    private val historyLoadMutex = Mutex()

    /**
     * 把「历史加载失败」显式落到 [HistoryState.FAILED]。
     *
     * 这是「会话历史持续加载失败、界面一直转圈」的根因修复点：
     * 旧实现里 `loadRows` 的**软失败**分支（响应结构不对 / 缺少 rows 字段）
     * 只写日志然后 `return _rows.value`，从不抛异常；而所有调用方都是
     * `runCatching { loadRows(...) }.onFailure { _historyState = FAILED }`，
     * 于是 FAILED 永远不会被设置 —— 页面永远停在 LOADING，连重试按钮都出不来。
     */
    private fun markHistoryLoadFailed(reason: String) {
        log("[v4] history load failed: $reason")
        if (_rows.value.isEmpty()) _historyState.value = HistoryState.FAILED
    }

    /** 历史行窗口（分页：beforeRowId 传当前最早一行的 rowId） */
    suspend fun loadRows(
        sessionId: String,
        limit: Int = 200,
        beforeRowId: String? = null,
        timeoutMs: Long = 15_000,
    ): List<ConvRow> = withContext(Dispatchers.IO) {
        if (!sessionScope.isActive) return@withContext _rows.value
        historyLoadMutex.withLock { loadRowsLocked(sessionId, limit, beforeRowId, timeoutMs) }
    }

    /** [loadRows] 的实际实现，调用方已持有 [historyLoadMutex]。 */
    private suspend fun loadRowsLocked(
        sessionId: String,
        limit: Int,
        beforeRowId: String?,
        timeoutMs: Long,
    ): List<ConvRow> {
        if (!sessionScope.isActive) return _rows.value
        // 三条兜底路径会重复发起初始加载：只要已经有人把历史填进来了就直接跳过，
        // 既省一次 RPC，也避免两条响应互相覆盖。
        if (beforeRowId == null && _rows.value.isNotEmpty()) return _rows.value

        val args = scope() + buildMap<String, Any> {
            put("sessionId", sessionId)
            put("limit", limit.toLong())
            if (beforeRowId != null) put("beforeRowId", beforeRowId)
        }
        ZemoteLogger.info("v4", "loadRows sessionId=$sessionId limit=$limit beforeRowId=$beforeRowId")

        val res = try {
            call("conversationRowsRangeV4", listOf(args), timeoutMs = timeoutMs) as? Map<*, *>
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            markHistoryLoadFailed("call threw: ${e.message}")
            return _rows.value
        }
        if (res == null) {
            markHistoryLoadFailed("unexpected response shape")
            return _rows.value
        }
        ZemoteLogger.info("v4", "loadRows response keys=${res.keys.toList()}")

        // 兼容两种响应形状：`{rows:[...], hasMore, atLogEpoch}` 与
        // `{rows:{rows:[...], hasMore, atLogEpoch}}`。旧实现只从 container 读字段，
        // 遇到外层形状时 hasMore / atLogEpoch 会从错误层级取（取不到 → 翻页提前终止）。
        val container = res["rows"] as? Map<*, *>
        val list = (container?.get("rows") as? List<*>) ?: (res["rows"] as? List<*>)
        if (list == null) {
            markHistoryLoadFailed("missing rows list (keys=${res.keys.toList()})")
            return _rows.value
        }
        fun field(name: String): Any? = container?.get(name) ?: res[name]

        // 官方语义：分页响应的 atLogEpoch 与当前快照不一致，说明期间发生过重建/resync，
        // 这一页属于旧纪元，必须整体丢弃，否则会把旧数据拼进当前时间线。
        val atLogEpoch = field("atLogEpoch")?.toString()
        val currentEpoch = convLogEpoch
        if (beforeRowId != null && atLogEpoch != null && currentEpoch != null && atLogEpoch != currentEpoch) {
            log("[v4] loadRows page epoch mismatch ($atLogEpoch != $currentEpoch), discarded")
            return _rows.value
        }

        val newRows = list.mapNotNull(::parseRow)
        ZemoteLogger.info("v4", "loadRows got ${newRows.size} rows raw")
        if (beforeRowId != null) {
            // 分页加载：只保留 rowId < beforeRowId 的新行，追加到已有行的头部
            val cursor = beforeRowId.toLongOrNull() ?: return _rows.value
            val older = newRows.filter { it.rowId < cursor }
            if (older.isEmpty()) {
                // 服务端确认没有更早的行了，避免 UI 反复触发加载
                hasMore = false
                return _rows.value
            }
            val combined = older.sortedBy { it.rowId } + _rows.value
            setRows(combined)
            // 只有还不知道服务端最早行时才回填（对齐官方 `firstRowId ?? row.rowId`）。
            // 这里绝不能用本地已加载的最早行去覆盖快照给的 firstRowId：那会把
            // hasOlderHistory 的兜底判断变成 `oldest > oldest`（恒 false），
            // 一旦服务端某次分页响应没带 hasMore，翻页就会提前终止、更早的历史再也拉不出来。
            if (firstRowId == null) firstRowId = combined.firstOrNull()?.rowId
        } else {
            // 初始加载：直接替换
            setRows(newRows.sortedBy { it.rowId })
            if (firstRowId == null) firstRowId = newRows.firstOrNull()?.rowId
        }
        totalCount = (field("totalCount") as? Number)?.toLong() ?: _rows.value.size.toLong()
        hasMore = (field("hasMore") as? Boolean) == true
        if (_rows.value.isNotEmpty()) {
            _historyState.value = HistoryState.READY
        } else if (_historyState.value != HistoryState.FAILED) {
            // 服务端明确返回了空窗口 → 这条会话确实没有历史（不是失败）
            _historyState.value = HistoryState.EMPTY
        }
        log("[v4] loadRows done: ${_rows.value.size} rows (firstRowId=$firstRowId total=$totalCount hasMore=$hasMore)")
        return _rows.value
    }

    /**
     * 是否还有更早的历史可加载。
     * 旧实现用服务端返回的 `firstRowId` 作为分页游标——那是"服务端最早的行"，
     * 拿它当 beforeRowId 去问"比它更早的行"必然返回空，翻页永远拿不到数据。
     * 正确做法：用当前已加载的最早一行 rowId 当游标。
     */
    val hasOlderHistory: Boolean
        get() {
            val oldest = _rows.value.firstOrNull()?.rowId ?: return false
            val serverFirst = firstRowId
            return hasMore || (serverFirst != null && oldest > serverFirst)
        }

    /** 向上翻页：加载当前最早行之前的更早历史，追加到列表头部。 */
    suspend fun loadOlderMessages(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val oldestRowId = _rows.value.firstOrNull()?.rowId ?: return@withContext false
        if (!hasOlderHistory) return@withContext false
        log("[v4] loadOlderMessages cursor=$oldestRowId size=${_rows.value.size} hasMore=$hasMore")
        val loaded = runCatching {
            withTimeout(15_000) { loadRows(sessionId, limit = 100, beforeRowId = oldestRowId.toString()) }
        }.getOrNull()
        loaded != null && _rows.value.isNotEmpty()
    }

    // ────────────────────────── 命令 ──────────────────────────

    /**
     * 发送用户文本。sessionId 为空时走官方首发路径：createSession 携带
     * firstInput（避免 send-before-subscribe 竞态），随后订阅新会话。
     * [attachments] 为 attachmentPut 返回的描述符（ref/fileName/mime/bytes）。
     *
     * 返回 [SendOutcome]：调用方必须据此判断是否真的发出去了（官方语义）。
     */
    suspend fun sendText(
        text: String,
        sessionId: String? = _activeSessionId.value,
        requestedDelivery: String = "startNow",
        attachments: List<Map<String, Any?>>? = null,
    ): SendOutcome = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext SendOutcome.Accepted(sessionId)
        var target = sessionId
        if (target == null) {
            // 首发走 createSession.firstInput：官方此路径不带 requestedDelivery
            val firstInput = buildMap<String, Any> {
                put("text", trimmed)
                if (!attachments.isNullOrEmpty()) put("attachments", attachments)
            }
            val res = sendCommand(null, "createSession", mapOf(
                "workspaceId" to workspaceKey,
                "firstInput" to firstInput,
            ))
            val map = res as? Map<*, *>
            val newId = extractNewSessionId(res)
            if (newId == null) {
                return@withContext SendOutcome.Rejected(
                    reasonCode = map?.get("reasonCode")?.toString(),
                    message = map?.get("message")?.toString(),
                )
            }
            _activeSessionId.value = newId
            ZemoteLogger.action("创建新会话: $newId")
            runCatching { subscribeConversation(newId) }
                .onFailure { log("[v4] subscribe(new) failed: $it") }
            return@withContext SendOutcome.Accepted(newId)
        }
        // requestedDelivery 必须带上：否则「排队」按钮和「发送」按钮在协议层完全一样
        val payload = buildMap<String, Any> {
            put("text", trimmed)
            if (requestedDelivery.isNotBlank()) put("requestedDelivery", requestedDelivery)
            if (!attachments.isNullOrEmpty()) put("attachments", attachments)
        }
        ZemoteLogger.info(
            "v4",
            "发送消息[$requestedDelivery]: ${trimmed.take(60)}${if (trimmed.length > 60) "…" else ""} 会话=$target",
        )
        var res = sendCommand(target, "sendText", payload) as? Map<*, *>
        if (res == null) {
            // sessionScope 已失效（页面离开/设备断开），如实上报而不是假装成功
            return@withContext SendOutcome.Rejected("session-inactive", null)
        }
        // 队列被占用：官方要求客户端明确处置方式。这里自动按「保留队列」重试一次
        // （绝不静默丢弃用户已排队的消息），仍失败就如实报错交给 UI 提示。
        if (res["reasonCode"]?.toString() == HELD_QUEUE_STALE) {
            val heldIds = _queueItems.value.map { it.queueItemId }
            if (heldIds.isNotEmpty()) {
                log("[v4] held queue conflict, retrying with keepQueueAndSend (${heldIds.size} items)")
                val retry = HashMap<String, Any>(payload).apply {
                    put("heldQueueDisposition", "keepQueueAndSend")
                    put("expectedHeldQueueItemIds", heldIds)
                }
                res = sendCommand(target, "sendText", retry) as? Map<*, *>
            }
        }
        val status = res?.get("status")?.toString()
        if (status == null || status in SEND_OK_STATUSES) {
            SendOutcome.Accepted(target)
        } else {
            log("[v4] sendText rejected: status=$status reason=${res?.get("reasonCode")} ${res?.get("message") ?: ""}")
            SendOutcome.Rejected(
                reasonCode = res?.get("reasonCode")?.toString() ?: status,
                message = res?.get("message")?.toString(),
            )
        }
    }

    /**
     * 仅创建会话（不带首发消息）。附件必须先有 sessionId 才能上传，
     * 带附件的新会话走：createSession → attachmentPut → sendText（对齐官方路径）。
     */
    suspend fun createSession(): String? = withContext(Dispatchers.IO) {
        ZemoteLogger.action("创建空白会话 (workspace=$workspaceKey)")
        val res = sendCommand(null, "createSession", mapOf("workspaceId" to workspaceKey))
        extractNewSessionId(res)?.also {
            _activeSessionId.value = it
            runCatching { subscribeConversation(it) }
                .onFailure { log("[v4] subscribe(new) failed: $it") }
        }
    }

    // ────────────────────────── 附件上传/下载 ──────────────────────────

    /**
     * 上传附件（官方 begin/chunk/commit 三段式，384KB 分片 + sha256 校验）。
     * 依赖握手返回的 connectionId。返回含 ref 的描述符。
     */
    suspend fun attachmentPut(
        sessionId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        onProgress: ((Float) -> Unit)? = null,
    ): AttachmentUpload = withContext(Dispatchers.IO) {
        runCatching { ensureHandshake() }
            .onFailure { log("[v4] handshake failed: $it") }
        val connId = connectionId
            ?: throw IllegalStateException("attachmentPut: missing connectionId")
        val uploadId = "upload-${UUID.randomUUID()}"
        val chunkBytes = 384 * 1024
        val totalChunks = ((bytes.size + chunkBytes - 1) / chunkBytes).coerceAtLeast(1)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val checksum = "sha256:" + digest.joinToString("") { "%02x".format(it) }
        val base = mapOf<String, Any>(
            "connectionId" to connId,
            "uploadId" to uploadId,
            "sessionId" to sessionId,
        )
        // 官方 TTe: s = {...t, sessionId, uploadId} — abort 时使用 base params
        val abortParams = scope() + mapOf(
            "sessionId" to sessionId,
            "uploadId" to uploadId,
        )
        var committed = false
        try {
            val startedAt = System.currentTimeMillis()
        log("[v4] attachmentPut begin: $fileName ${bytes.size}B chunks=$totalChunks")
        val beginRes = call(
            "attachmentBeginV4",
            listOf(scope() + base + mapOf(
                "fileName" to fileName,
                "mime" to mime,
                "totalBytes" to bytes.size.toLong(),
                "totalChunks" to totalChunks.toLong(),
                "checksum" to checksum,
            )),
            timeoutMs = 60_000,
            isActiveCheck = { sessionScope.isActive },
        ) as? Map<*, *>
        if (beginRes?.get("state") == "committed") {
            // 服务端已有同校验和内容，秒传
            log("[v4] attachmentPut committed(instant) $fileName")
            onProgress?.invoke(1f)
            return@withContext AttachmentUpload(beginRes["ref"]?.toString(), fileName, mime, bytes.size.toLong())
        }
        var chunkIndex = (beginRes?.get("nextChunkIndex") as? Number)?.toInt() ?: 0
        while (chunkIndex < totalChunks) {
            val start = chunkIndex * chunkBytes
            val end = minOf(start + chunkBytes, bytes.size)
            val b64 = Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end))
            val chunkStart = System.currentTimeMillis()
            val chunkRes = call(
                "attachmentChunkV4",
                listOf(scope() + base + mapOf(
                    "chunkIndex" to chunkIndex.toLong(),
                    "dataBase64" to b64,
                )),
                timeoutMs = 60_000,
                isActiveCheck = { sessionScope.isActive },
            ) as? Map<*, *>
            val next = (chunkRes?.get("nextChunkIndex") as? Number)?.toInt() ?: (chunkIndex + 1)
            if (next != chunkIndex + 1) throw IllegalStateException("fault.attachment.invalidServerProgress")
            chunkIndex = next
            log("[v4] attachmentPut chunk $chunkIndex/$totalChunks +${System.currentTimeMillis() - chunkStart}ms total=${System.currentTimeMillis() - startedAt}ms")
            onProgress?.invoke(chunkIndex.toFloat() / totalChunks)
        }
        onProgress?.invoke(1f)
        val commitRes = call("attachmentCommitV4", listOf(scope() + base), timeoutMs = 60_000, isActiveCheck = { sessionScope.isActive }) as? Map<*, *>
        log("[v4] attachmentPut committed $fileName in ${System.currentTimeMillis() - startedAt}ms ref=${commitRes?.get("ref")}")
        committed = true
        AttachmentUpload(commitRes?.get("ref")?.toString(), fileName, mime, bytes.size.toLong())
        } catch (e: Exception) {
            // 官方模式: if(u) try{await e.attachmentAbortV4(s)}catch...throw t
            if (!committed) {
                runCatching {
                    call("attachmentAbortV4", listOf(scope() + abortParams), isActiveCheck = { sessionScope.isActive })
                }.onFailure { log("[v4] attachmentAbortV4 failed: $it") }
            }
            throw e
        }
    }

    /** 读取附件内容（图片预览），分片拉取拼接 */
    suspend fun attachmentRead(sessionId: String, ref: String): AttachmentData = withContext(Dispatchers.IO) {
        runCatching { ensureHandshake() }
            .onFailure { log("[v4] handshake failed: $it") }
        val chunkBytes = 384 * 1024
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        var mediaType: String? = null
        for (round in 0 until 1024) {
            val res = call(
                "attachmentReadV4",
                listOf(scope() + mapOf(
                    "sessionId" to sessionId,
                    "ref" to ref,
                    "offset" to offset,
                    "limit" to chunkBytes.toLong(),
                )),
                isActiveCheck = { sessionScope.isActive },
            ) as? Map<*, *> ?: break
            if (mediaType == null) mediaType = res["mediaType"]?.toString()
            val data = res["dataBase64"]?.toString()
            if (!data.isNullOrEmpty()) out.write(Base64.getDecoder().decode(data))
            val next = (res["nextOffset"] as? Number)?.toLong() ?: break
            val total = (res["totalBytes"] as? Number)?.toLong()
            if (next <= offset) break
            offset = next
            if (total != null && offset >= total) break
        }
        AttachmentData(out.toByteArray(), mediaType)
    }

    /** 停止当前生成（官方 stop，payload 为空） */
    suspend fun stop(sessionId: String? = _activeSessionId.value) = withContext(Dispatchers.IO) {
        if (sessionId == null) return@withContext
        runCatching {
            sendCommand(sessionId, "stop", emptyMap())
            _stopWorkId.value = null
            _agentWorking.value = false
        }
    }

    // ── 排队消息操作（官方队列命令，均为 CAS 命令） ──

    /** 立即发送排队中的某条消息 */
    suspend fun sendQueuedNow(queueItemId: String): Boolean =
        queueCommand("sendQueuedNow", mapOf("queueItemId" to queueItemId))

    /** 编辑排队中的消息文本 */
    suspend fun editQueueItem(queueItemId: String, newText: String): Boolean =
        queueCommand("editQueueItem", mapOf("queueItemId" to queueItemId, "newText" to newText))

    /** 删除排队中的消息 */
    suspend fun deleteQueueItem(queueItemId: String): Boolean =
        queueCommand("deleteQueueItem", mapOf("queueItemId" to queueItemId))

    /** 队列排序（官方 reorderQueueItem，传完整有序 id 列表；UI 侧已做本地乐观排序） */
    suspend fun reorderQueueItem(orderedIds: List<String>): Boolean =
        queueCommand("reorderQueueItem", mapOf("queueItemIds" to orderedIds))

    /** 队列自动发送开关 */
    suspend fun setAutoDrain(enabled: Boolean): Boolean =
        queueCommand("setAutoDrain", mapOf("autoDrain" to enabled))

    private suspend fun queueCommand(type: String, payload: Map<String, Any?>): Boolean {
        val sessionId = _activeSessionId.value ?: return false
        return try {
            val res = sendCommand(sessionId, type, payload) as? Map<*, *>
            val status = res?.get("status")?.toString()
            status == null || status.startsWith("reject") == false
        } catch (e: Exception) {
            log("[v4] $type failed: ${e.message}")
            false
        }
    }

    // ── 交互响应（权限审批 / 用户输入 / 计划确认） ──

    /** 响应交互请求：权限审批选 optionId，用户输入填 freeText，计划确认选 action */
    suspend fun respondInteraction(
        requestId: String,
        optionId: String? = null,
        freeText: String? = null,
        action: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val sessionId = _activeSessionId.value ?: return@withContext false
        val answer = mutableMapOf<String, Any?>()
        if (optionId != null) answer["optionId"] = optionId
        if (freeText != null) answer["freeText"] = freeText
        if (action != null) answer["action"] = action
        runCatching {
            sendCommand(sessionId, "resolveInteraction", mapOf(
                "interactionId" to requestId,
                "answer" to answer,
            ))
            true
        }.onFailure { log("[v4] respondInteraction failed: $it") }.getOrDefault(false)
    }

    /** 取消后台任务（bash / subagent） */
    suspend fun cancelBackgroundWork(workId: String): Boolean = withContext(Dispatchers.IO) {
        val sessionId = _activeSessionId.value ?: return@withContext false
        runCatching {
            sendCommand(sessionId, "cancel", mapOf("workId" to workId))
            true
        }.onFailure { log("[v4] cancelBackgroundWork failed: $it") }.getOrDefault(false)
    }

    /**
     * 通用命令发送：CAS 命令带 baseRevision；服务端报 stale 时按
     * revisionAtDecision 重试一次（对齐官方 stale-revision 重试）。
     */
    private suspend fun sendCommand(
        sessionId: String?,
        type: String,
        payload: Map<String, Any?>,
        timeoutMs: Long = 30_000,
    ): Any? {
        runCatching { ensureHandshake() }
            .onFailure { log("[v4] handshake failed: $it") }
        if (!sessionScope.isActive) return null
        val baseRevision = if (sessionId != null) {
            maxOf(revision, ackedRevisions[sessionId] ?: 0L)
        } else 0L
        val envelope = buildMap<String, Any> {
            put("commandId", UUID.randomUUID().toString())
            put("clientId", CLIENT_ID)
            if (sessionId != null) put("sessionId", sessionId)
            if (sessionId != null && type in CAS_COMMANDS) put("baseRevision", baseRevision)
            put("type", type)
            put("payload", payload)
            put("issuedAt", System.currentTimeMillis())
        }
        var res = call("sendConversationCommandV4", listOf(scope() + mapOf("envelope" to envelope)), timeoutMs)
        val map = res as? Map<*, *>
        if (sessionId != null && map?.get("status") == "stale") {
            val serverRevision = (map["revisionAtDecision"] as? Number)?.toLong() ?: 0L
            log("[v4] command $type stale, retry at rev $serverRevision")
            if (serverRevision > (ackedRevisions[sessionId] ?: 0L)) {
                ackedRevisions[sessionId] = serverRevision
            }
            val retry = HashMap<String, Any>(envelope).apply {
                put("commandId", UUID.randomUUID().toString())
                put("baseRevision", serverRevision)
                put("issuedAt", System.currentTimeMillis())
            }
            res = call("sendConversationCommandV4", listOf(scope() + mapOf("envelope" to retry)), timeoutMs)
        }
        // 记录 ack 携带的 revision；已接受的命令使 revision +1，作为下次 CAS 基准
        val ack = res as? Map<*, *>
        if (sessionId != null) {
            val rev = (ack?.get("revisionAtDecision") as? Number)?.toLong() ?: return res
            val status = ack["status"]?.toString()
            val floor = if (status == "accepted" || status == "noop" || status == "duplicate") rev + 1 else rev
            if (floor > (ackedRevisions[sessionId] ?: 0L)) ackedRevisions[sessionId] = floor
        }
        return res
    }

    private fun extractNewSessionId(res: Any?): String? {
        val map = res as? Map<*, *> ?: return null
        if (map["status"] != "accepted") {
            log("[v4] createSession rejected: ${map["reasonCode"] ?: map["status"]} ${map["message"] ?: ""}")
            return null
        }
        val result = map["result"] as? Map<*, *> ?: return null
        val id = result["sessionId"]?.toString()
        return id?.takeIf { it.isNotEmpty() }
    }

    // ────────────────────────── 配置切换 ──────────────────────────

    /** 切换思考等级（官方 switchModelConfig；provider/model 取当前配置） */
    suspend fun setThought(level: String): Boolean = withContext(Dispatchers.IO) {
        val cfg = _convConfig.value ?: return@withContext false
        val provider = cfg.provider ?: return@withContext false
        val model = cfg.model ?: return@withContext false
        sendSwitchModelConfig(provider, model, level)
    }

    /** 切换模型（等级沿用当前值） */
    suspend fun setModel(provider: String, model: String): Boolean = withContext(Dispatchers.IO) {
        val thought = _convConfig.value?.thought ?: ""
        sendSwitchModelConfig(provider, model, thought)
    }

    private suspend fun sendSwitchModelConfig(provider: String, model: String, thought: String): Boolean {
        var res = runCatching {
            sendCommand(
                _activeSessionId.value, "switchModelConfig",
                mapOf("provider" to provider, "model" to model, "thought" to thought),
            )
        }.getOrNull() as? Map<*, *>
        // 不同模型家族思考档位不同（如 GLM：max/high/nothink；Turbo：enabled/off），
        // 报 Unsupported reasoning effort 时按另一家族默认档位重试
        val message = res?.get("message")?.toString() ?: ""
        if (message.contains("Unsupported reasoning effort")) {
            val fallback = if (thought == "enabled" || thought == "off") "max" else "enabled"
            log("[v4] switchModelConfig retry with thought=$fallback")
            res = runCatching {
                sendCommand(
                    _activeSessionId.value, "switchModelConfig",
                    mapOf("provider" to provider, "model" to model, "thought" to fallback),
                )
            }.getOrNull() as? Map<*, *>
        }
        val ok = res != null && res["status"]?.toString()?.startsWith("reject") != true
        if (ok) {
            _convConfig.update { (it ?: ConvConfig()).copy(provider = provider, model = model, thought = thought.ifEmpty { null }) }
        }
        return ok
    }

    // ────────────────────────── prepareWorkspace（模型/思考选项） ──────────────────────────

    /**
     * zcode-task.prepareWorkspace：返回 configOptions（模型/模式/思考档位）。
     * 模型选项 value 形如 "builtin:zai-coding-plan/GLM-5.2"，按最后一个 '/' 拆分。
     */
    suspend fun prepareWorkspace(refresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!refresh && _modelOptions.value.isNotEmpty()) return@withContext true
        if (!sessionScope.isActive) return@withContext false
        val res = runCatching {
            channels.call(ChannelClient.Channel.ZCODE_TASK, "prepareWorkspace", listOf(scope()), isActiveCheck = { sessionScope.isActive })
        }.getOrNull() as? Map<*, *> ?: return@withContext false
        val options = res["configOptions"] as? List<*> ?: return@withContext false
        log("[v4] prepareWorkspace: got ${options.size} configOptions entries")
        for (raw in options) {
            val o = raw as? Map<*, *> ?: continue
            val type = (o["id"] as? String) ?: (o["category"] as? String) ?: continue
            when (type) {
                "model" -> {
                    val parsed = (o["options"] as? List<*>).orEmpty().mapNotNull { v ->
                        val vm = v as? Map<*, *> ?: return@mapNotNull null
                        val value = vm["value"]?.toString() ?: return@mapNotNull null
                        val slash = value.lastIndexOf('/')
                        val provider = if (slash <= 0) value else value.substring(0, slash)
                        val model = if (slash <= 0) value else value.substring(slash + 1)
                        ModelOption(provider = provider, model = model, label = vm["name"]?.toString()?.ifBlank { null } ?: model)
                    }
                    log("[v4] prepareWorkspace: parsed ${parsed.size} model options")
                    if (parsed.isNotEmpty()) _modelOptions.value = parsed
                }
                "thought_level" -> {
                    val levels = (o["options"] as? List<*>).orEmpty().mapNotNull { v ->
                        (v as? Map<*, *>)?.get("value")?.toString()
                    }
                    if (levels.isNotEmpty()) {
                        prepThoughtLevels = levels
                        // 会话配置还没下发思考档位时，用 prepareWorkspace 的兜底
                        _convConfig.update { it?.copy(thoughtLevels = it.thoughtLevels.ifEmpty { levels }) }
                    }
                }
                else -> Unit // mode / 其他未实现类型，忽略不打印日志
            }
        }
        true
    }

    // ────────────────────────── sessions-index（实时会话列表） ──────────────────────────

    /** 订阅工作区实时会话列表（任务页数据源，runtimePolicy=existing-only） */
    suspend fun openSessionsIndex() = withContext(Dispatchers.IO) {
        if (siSubId != null) return@withContext
        runCatching { ensureHandshake() }
            .onFailure { log("[v4-si] handshake failed: $it"); return@withContext }
        if (!sessionScope.isActive) return@withContext
        siCancel = channels.addEventListener(
            ChannelClient.Channel.ZCODE_AGENT,
            "onDynamicSessionsIndexFrame",
            onEvent = ::handleSessionsIndexEvent,
            arg = scope(),
        )
        val res = runCatching {
            call(
                "subscribeSessionsIndexV4",
                listOf(scope() + mapOf("runtimePolicy" to "existing-only")),
                timeoutMs = 60_000,
                isActiveCheck = { sessionScope.isActive },
            )
        }.getOrNull()
        // scope 已取消 → 静默退出
        if (res == null) { siCancel?.invoke(); siCancel = null; return@withContext }
        val ack = (res as? Map<*, *>)?.get("ack") as? Map<*, *>
        siSubId = ack?.get("subscriptionId")?.toString()
        ack?.get("logEpoch")?.toString()?.let { siLogEpoch = it }
        if (siSubId == null) {
            log("[v4-si] subscribeSessionsIndexV4: missing ack.subscriptionId")
            siCancel?.invoke(); siCancel = null
            return@withContext
        }
        val staged = synchronized(siStaged) {
            val copy = siStaged.toList()
            siStaged.clear()
            copy
        }
        staged.forEach { applySessionsIndexFrame(it) }
    }

    private fun handleSessionsIndexEvent(data: Any?) {
        val raw = (data as? List<*>)?.firstOrNull() ?: data
        val wire = raw as? Map<*, *> ?: return
        val topic = wire["topic"] as? String
        val inner = when (wire["kind"] as? String) {
            "complete" -> wire["frame"] as? Map<*, *>
            "fragment" -> assembleFragment(wire)
            else -> if (wire["payload"] != null) wire else null
        } ?: return
        if (topic != null && !topic.startsWith("sessions-index/")) return
        val subId = siSubId
        if (subId == null) {
            synchronized(siStaged) { siStaged.add(inner) }
            return
        }
        if (inner["subscriptionId"]?.toString() != subId) return
        applySessionsIndexFrame(inner)
    }

    private fun applySessionsIndexFrame(frame: Map<*, *>) {
        try {
            val payload = frame["payload"] as? Map<*, *> ?: return
            val toSeq = (frame["toSeq"] as? Number)?.toLong() ?: siSeq
            when (payload["kind"] as? String) {
                "snapshot" -> {
                    val snap = payload["snapshot"] as? Map<*, *> ?: return
                    siLogEpoch = snap["logEpoch"]?.toString()
                    _sessionEntries.value = (snap["sessions"] as? List<*>)
                        .orEmpty()
                        .mapNotNull(::parseSessionEntry)
                        .sortedByDescending { it.lastActivityAt }
                    siSeq = toSeq
                }
                "deltas" -> {
                    val fromSeq = (frame["fromSeq"] as? Number)?.toLong() ?: siSeq
                    if (fromSeq != siSeq) {
                        resyncSessionsIndex()
                        return
                    }
                    for (d in payload["deltas"] as? List<*> ?: return) {
                        val m = d as? Map<*, *> ?: continue
                        when (m["op"] as? String) {
                            "session.upserted" -> parseSessionEntry(m["session"])?.let { entry ->
                                _sessionEntries.update { list ->
                                    (list.filterNot { it.sessionId == entry.sessionId } + entry)
                                        .sortedByDescending { it.lastActivityAt }
                                }
                            }
                            "session.removed" -> {
                                val id = m["sessionId"]?.toString()
                                _sessionEntries.update { list -> list.filterNot { it.sessionId == id } }
                            }
                        }
                    }
                    siSeq = toSeq
                }
            }
        } catch (e: Exception) {
            log("[v4-si] frame error: ${e.message}")
        }
    }

    private fun resyncSessionsIndex() {
        val id = siSubId
        if (id == null || siResyncing || !sessionScope.isActive) return
        siResyncing = true
        sessionScope.launch {
            try {
                call(
                    "resyncSessionsIndexV4",
                    listOf(scope() + mapOf(
                        "subscriptionId" to id,
                        "runtimePolicy" to "existing-only",
                        "base" to mapOf("logEpoch" to siLogEpoch, "seq" to siSeq),
                    )),
                    isActiveCheck = { sessionScope.isActive },
                )
            } catch (e: Exception) {
                log("[v4-si] resync failed: ${e.message}")
            } finally {
                siResyncing = false
            }
        }
    }

    private fun parseSessionEntry(raw: Any?): SessionEntry? {
        val m = raw as? Map<*, *> ?: return null
        val id = m["sessionId"]?.toString() ?: return null
        return SessionEntry(
            sessionId = id,
            title = m["title"]?.toString().orEmpty(),
            phase = m["phase"]?.toString().orEmpty(),
            lastAssistantPreview = m["lastAssistantPreview"]?.toString(),
            lastActivityAt = (m["lastActivityAt"] as? Number)?.toLong() ?: 0L,
            createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L,
            hasBackgroundWork = m["hasBackgroundWork"] == true,
        )
    }

    // ────────────────────────── 状态合并 ──────────────────────────

    private fun mergeConfig(m: Map<*, *>) {
        val levels = (m["thoughtLevels"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            .orEmpty()
            .ifEmpty { prepThoughtLevels }
        val followup = m["followupMode"]?.toString()
        _convConfig.value = ConvConfig(
            provider = m["provider"]?.toString(),
            model = m["model"]?.toString(),
            thought = m["thought"]?.toString().orEmpty().ifEmpty { null },
            thoughtLevels = levels,
            mode = m["mode"]?.toString(),
            followupMode = followup,
        )
        if (followup != null) _followupMode.value = followup
    }

    /** 队列状态：items + autoDrain（官方 queue 快照/补丁） */
    private fun mergeQueue(q: Map<*, *>) {
        _autoDrain.value = q["autoDrain"] != false
        _queueItems.value = (q["items"] as? List<*>)
            ?.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val id = m["queueItemId"]?.toString() ?: return@mapNotNull null
                QueueItem(
                    queueItemId = id,
                    text = m["text"]?.toString().orEmpty(),
                    createdAt = (m["createdAt"] as? Number)?.toLong(),
                )
            }
            .orEmpty()
    }

    /** 解析 pendingInteractions：权限审批 / 用户输入 / 计划确认 */
    private fun mergeInteractions(list: List<*>) {
        _pendingInteractions.value = list.mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val requestId = m["requestId"]?.toString() ?: return@mapNotNull null
            val payload = m["payload"] as? Map<*, *> ?: return@mapNotNull null
            val kind = payload["kind"]?.toString() ?: return@mapNotNull null
            val title = m["title"]?.toString()?.takeIf { it.isNotBlank() }
                ?: when (kind) {
                    "permission" -> payload["summary"]?.toString().orEmpty()
                    else -> ""
                }
            val body = m["body"]?.toString()
                ?: payload["summary"]?.toString()?.ifBlank { null }
                ?: payload["prompt"]?.toString()?.ifBlank { null }
                ?: ""
            val options = (payload["options"] as? List<*>)
                ?.mapNotNull { o ->
                    val om = o as? Map<*, *> ?: return@mapNotNull null
                    InteractionOption(
                        optionId = om["optionId"]?.toString() ?: return@mapNotNull null,
                        label = om["label"]?.toString().orEmpty(),
                        kind = om["kind"]?.toString() ?: "allowOnce",
                    )
                }
                .orEmpty()
            PendingInteraction(
                requestId = requestId,
                kind = kind,
                title = title,
                body = body,
                options = options,
                multiSelect = payload["multiSelect"] == true,
            )
        }
    }

    /** 解析后台任务 */
    private fun mergeBackgroundWorks(list: List<*>) {
        _backgroundWorks.value = list.mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val workId = m["workId"]?.toString() ?: return@mapNotNull null
            BackgroundWork(
                workId = workId,
                kind = m["kind"]?.toString() ?: "bash",
                title = m["title"]?.toString().orEmpty(),
                status = m["status"]?.toString() ?: "running",
                startedAt = (m["startedAt"] as? Number)?.toLong() ?: 0L,
                cancellable = m["cancellable"] == true,
            )
        }
    }

    private fun mergeControl(c: Map<*, *>) {        // 运行状态以 control.phase 为准（running/prewarming），并同步停止按钮
        val phase = c["phase"]?.toString()
        if (phase != null) {
            _agentWorking.value = phase == "running" || phase == "prewarming"
        }
        val canStop = c["canStop"] == true
        var fgId: String? = null
        (c["activeWorks"] as? List<*>)?.forEach { w ->
            (w as? Map<*, *>)?.get("foregroundExecutionId")?.toString()?.let { fgId = it }
        }
        _stopWorkId.value = if (canStop) (fgId ?: _stopWorkId.value) else null
    }

    private fun mergeUsage(m: Map<*, *>) {
        val cw = m["contextWindow"] as? Map<*, *> ?: m
        val cache = cw["cache"] as? Map<*, *>
        val breakdown = (cw["breakdown"] as? List<*>)
            ?.mapNotNull { b ->
                val bm = b as? Map<*, *> ?: return@mapNotNull null
                val src = bm["source"]?.toString() ?: return@mapNotNull null
                val chars = (bm["chars"] as? Number)?.toLong() ?: 0L
                src to chars
            }
            .orEmpty()
        _usage.value = ConvUsage(
            usedTokens = (cw["usedTokens"] as? Number)?.toLong() ?: 0L,
            maxTokens = (cw["maxTokens"] as? Number)?.toLong() ?: 0L,
            hitRate = (cache?.get("hitRate") as? Number)?.toDouble(),
            breakdown = breakdown,
        )
    }

    private fun parseRow(raw: Any?): ConvRow? {
        val m = raw as? Map<*, *> ?: return null
        // rowId 服务端是递增数字；visibility 过滤掉非可见行
        val rowId = (m["rowId"] as? Number)?.toLong() ?: m["rowId"]?.toString()?.toLongOrNull() ?: return null
        if (m["visibility"] != null && m["visibility"] != "visible") return null
        val kind = (m["kind"] as? String) ?: (m["type"] as? String) ?: return null
        if (kind == ConvKinds.TURN_HEADER) return null // 回合分隔头，不进时间线
        val output = m["output"] as? Map<*, *>
        val attachments = (m["attachments"] as? List<*>)
            ?.mapNotNull { a ->
                val am = a as? Map<*, *> ?: return@mapNotNull null
                val ref = am["ref"]?.toString() ?: return@mapNotNull null
                mapOf(
                    "ref" to ref,
                    "fileName" to (am["fileName"]?.toString() ?: "附件"),
                    "mime" to (am["mime"]?.toString() ?: "application/octet-stream"),
                )
            }
            .orEmpty()
        return ConvRow(
            rowId = rowId,
            kind = kind,
            text = m["text"]?.toString() ?: "",
            inputText = m["inputText"]?.toString() ?: "",
            outputText = output?.get("text")?.toString() ?: "",
            summaryText = m["summaryText"]?.toString() ?: "",
            toolName = (m["toolName"] as? String) ?: (m["tool"] as? String),
            toolStatus = m["status"]?.toString(),
            state = m["state"]?.toString(),
            additions = (m["additions"] as? Number)?.toInt(),
            issuedAt = (m["issuedAt"] as? Number)?.toLong() ?: (m["createdAt"] as? Number)?.toLong(),
            attachments = attachments,
            childSessionId = (m["childSessionId"] as? String)?.takeIf { it.isNotBlank() },
            subagentType = (m["subagentType"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 按 rowId 合并单行（`row.upserted` 路径）。
     *
     * 旧实现是 `_rows.value.associateBy { it.rowId }` + 全表 `sortedBy`：每来一次
     * upsert 就建一张 n 元素的 HashMap 再整体排序，O(n log n) 且分配 n 个节点。
     * 流式期间工具行的状态会被反复 upsert（running → complete），一次长会话
     * 每秒能做几十次，这就是「页面渲染慢」的主要来源之一。
     *
     * 现在改为二分定位 + 就地替换/插入：O(log n) 查找 + O(n) 复制（不可避免，
     * 因为要产出新的不可变列表），但不再有 map 分配与排序。
     * 前提不变量：`_rows.value` 始终按 rowId 升序（applySnapshot / loadRows /
     * row.appended / row.removed 都维持这个顺序）。
     */
    private fun mergeRow(row: ConvRow) {
        synchronized(rowsLock) { mergeRowLocked(row) }
    }

    /** 调用方必须已持有 [rowsLock]。 */
    private fun mergeRowLocked(row: ConvRow) {
        val current = _rows.value
        val idx = current.binarySearch { it.rowId.compareTo(row.rowId) }
        if (idx >= 0) {
            val old = current[idx]
            // 内容一致 → 保留旧实例，不发布（维持「未变行复用同一实例」不变量）
            if (old == row) return
            val updated = ArrayList<ConvRow>(current.size)
            updated.addAll(current)
            updated[idx] = row
            publishRows(updated)
        } else {
            val ins = -(idx + 1)
            val updated = ArrayList<ConvRow>(current.size + 1)
            updated.addAll(current.subList(0, ins))
            updated.add(row)
            updated.addAll(current.subList(ins, current.size))
            publishRows(updated)
        }
    }

    private suspend fun call(
        method: String,
        args: List<Any?>,
        timeoutMs: Long = 30_000,
        isActiveCheck: () -> Boolean = { sessionScope.isActive },
    ): Any? =
        channels.call(ChannelClient.Channel.ZCODE_AGENT, method, args, timeoutMs, isActiveCheck)

    fun dispose() {
        clearPendingDeltas()
        unsubscribeConversation()
        siCancel?.invoke()
        siCancel = null
        val id = siSubId
        siSubId = null
        if (id != null) {
            // 同样使用 sessionScope，确保 dispose 时能随会话一起取消
            sessionScope.launch {
                runCatching {
                    channels.call(
                        ChannelClient.Channel.ZCODE_AGENT,
                        "unsubscribeSessionsIndexV4",
                        listOf(scope() + mapOf("subscriptionId" to id, "runtimePolicy" to "existing-only")),
                        isActiveCheck = { sessionScope.isActive },
                    )
                }
            }
        }
        resyncing = false
        sessionScope.cancel()
        bridge.dispose()
    }
}
