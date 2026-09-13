package app.zemote.protocol

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
import kotlinx.coroutines.withContext
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
suspend fun fetchTasksFromBootstrap(client: ZemoteClient, workspaceKey: String? = null): List<TaskEntry> {
    val res = client.bootstrap()
    val tasks = res["tasks"] as? List<*> ?: return emptyList()
    return tasks.mapNotNull { t ->
        val m = t as? Map<*, *> ?: return@mapNotNull null
        val id = m["taskId"]?.toString() ?: return@mapNotNull null
        // bootstrap 的 tasks 是全局的，必须按工作区过滤，否则混入其他目录的会话
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

        private val CLIENT_ID = UUID.randomUUID().toString()

        /** CAS 命令集合：信封必须携带 baseRevision（对齐官方 eAe） */
        private val CAS_COMMANDS = setOf(
            "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
            "setAssistantFeedback", "sendQueuedNow", "editQueueItem",
            "reorderQueueItem", "deleteQueueItem", "setAutoDrain",
            "switchModelConfig", "switchCollaborationMode", "setFollowupMode",
            "pauseGoal", "resumeGoal",
        )

        /** 打开 workspace bridge（可绑定 taskId）并创建会话仓库。 */
        suspend fun open(
            client: ZemoteClient,
            workspaceKey: String,
            taskId: String? = null,
            scopeParams: Map<String, Any>? = null,
        ): ConversationV4Session = withContext(Dispatchers.IO) {
            val bridge = client.openBridge(workspaceKey, taskId)
            ConversationV4Session(client, bridge, workspaceKey, scopeParams)
        }
    }

    private val channels get() = bridge.channelsClient
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 状态（UI 绑定） ──
    private val _rows = MutableStateFlow<List<ConvRow>>(emptyList())
    val rows: StateFlow<List<ConvRow>> = _rows.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _agentWorking = MutableStateFlow(false)
    val agentWorking: StateFlow<Boolean> = _agentWorking.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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

    // ── 内部协议状态 ──
    private var handshakeDone = false
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
    private var firstRowId: Long? = null
    private var totalCount = 0L

    // openConversation 进行中标志：恢复重建必须避让，否则会拆掉刚建立的订阅，
    // 导致历史/模型等数据永远拉不到
    @Volatile private var opening = false
    private var rebuildPending = false
    private var rebuilding = false

    init {
        // bridge 重连/重开后：旧 channel 栈与订阅全部作废，重置握手并整体重建。
        // 若此刻 openConversation 正在进行，则挂起重待办，等它完成后再重建。
        sessionScope.launch {
            bridge.recovered.collect { count ->
                if (count <= 0 || bridge.isDisposed) return@collect
                if (opening) {
                    rebuildPending = true
                    log("[v4] bridge recovered during open, rebuild deferred")
                    return@collect
                }
                rebuildSubscriptions()
            }
        }
    }

    private suspend fun rebuildSubscriptions() {
        if (rebuilding) return
        rebuilding = true
        try {
            log("[v4] bridge recovered, rebuilding subscriptions")
            val hadSessionsIndex = siSubId != null
            val activeId = _activeSessionId.value
            handshakeDone = false
            connectionId = null
            convWatchdog?.cancel()
            convCancel?.invoke(); convCancel = null
            convSubId = null
            siCancel?.invoke(); siCancel = null
            siSubId = null
            convSeq = 0
            siSeq = 0
            snapshotSeen = false
            _pendingPatch = null
            synchronized(stagedFrames) { stagedFrames.clear() }
            synchronized(siStaged) { siStaged.clear() }
            runCatching { ensureHandshake() }
                .onFailure { log("[v4] handshake after recovery failed: $it"); return }
            if (activeId != null) {
                runCatching { subscribeConversation(activeId) }
                    .onFailure { log("[v4] resubscribe failed: $it") }
                runCatching { loadRows(activeId, limit = 200) }
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
        if (handshakeDone) return
        val hello = call("helloConversationV4", emptyList()) as? Map<*, *>
        connectionId = hello?.get("connectionId")?.toString()
        call(
            "initializeConversationV4",
            listOf(mapOf<String, Any>(
                "kind" to "clientHello",
                "protocolVersion" to 3L,
                "clientId" to CLIENT_ID,
                "clientKind" to "mobileApp",
                "appVersion" to PROTOCOL_APP_VERSION,
            )),
        )
        handshakeDone = true
    }

    // ────────────────────────── 订阅生命周期 ──────────────────────────

    /**
     * 订阅指定会话并拉取历史。必须在 IO 线程调用（Compose 的
     * AndroidUiDispatcher 在静态界面不产生帧，withTimeout 会被饿死）。
     */
    suspend fun openConversation(sessionId: String?) = withContext(Dispatchers.IO) {
        if (_activeSessionId.value == sessionId && _rows.value.isNotEmpty()) return@withContext
        opening = true
        try {
            openConversationInternal(sessionId)
        } finally {
            opening = false
        }
        // 打开期间若有 bridge 恢复事件被搁置，现在补做重建
        if (rebuildPending && !bridge.isDisposed) {
            rebuildPending = false
            rebuildSubscriptions()
        }
    }

    private suspend fun openConversationInternal(sessionId: String?) {
        // 总超时兜底：任何一步卡住都保证 loading 收敛，界面不会永远转圈
        kotlinx.coroutines.withTimeout(100_000) {
            try {
                unsubscribeConversation()
                _activeSessionId.value = sessionId
                _rows.value = emptyList()
                firstRowId = null
                totalCount = 0
                convSeq = 0
                convLogEpoch = null
                snapshotSeen = false
                _pendingPatch = null
                ackedRevisions.clear()
                synchronized(stagedFrames) { stagedFrames.clear() }
                _loading.value = true
                runCatching { ensureHandshake() }
                    .onFailure { log("[v4] handshake failed: $it") }
                // 模型/思考档位选项（prepareWorkspace），后台加载不阻塞会话打开；
                // 首次拿到空结果时自动重试一次（桌面端冷启动时可能返回空）
                sessionScope.launch {
                    runCatching { prepareWorkspace() }
                    if (_modelOptions.value.isEmpty()) {
                        delay(3000)
                        runCatching { prepareWorkspace() }
                    }
                }
                if (sessionId != null) {
                    runCatching { subscribeConversation(sessionId) }
                        .onFailure { log("[v4] subscribe failed: $it") }
                    try {
                        loadRows(sessionId, limit = 200)
                    } catch (e: Exception) {
                        log("[v4] loadRows failed: ${e.message}")
                    }
                    // 兜底：桌面端会话运行时可能未预热，首拉为空时自动补拉两次
                    if (_rows.value.isEmpty()) {
                        repeat(2) { attempt ->
                            delay(if (attempt == 0) 2500L else 5000L)
                            if (_rows.value.isNotEmpty()) return@repeat
                            log("[v4] history empty, retry #$attempt")
                            runCatching { resyncConversation() }
                            try {
                                loadRows(sessionId, limit = 200)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun subscribeConversation(sessionId: String) {
        convCancel?.invoke()
        convCancel = null
        val listener = channels.addEventListener(
            ChannelClient.Channel.ZCODE_AGENT,
            "onDynamicConversationFrame",
            onEvent = ::handleConversationEvent,
            arg = scope(),
        )
        // 桌面端可能需要预热会话运行时，订阅要给足超时（官方 60s）
        val res = runCatching {
            call("subscribeConversationV4", listOf(scope() + mapOf("sessionId" to sessionId)), timeoutMs = 60_000)
        }.getOrNull()
        convCancel = listener
        val ack = (res as? Map<*, *>)?.get("ack") as? Map<*, *>
        convSubId = ack?.get("subscriptionId")?.toString()
        ack?.get("logEpoch")?.toString()?.let { convLogEpoch = it }
        if (convSubId == null) {
            log("[v4] subscribeConversationV4: missing ack.subscriptionId")
            return
        }
        // 应答前到达的帧按序回放
        val staged = synchronized(stagedFrames) {
            val copy = stagedFrames.toList()
            stagedFrames.clear()
            copy
        }
        staged.forEach { acceptLogicalFrame(it) }
        startWatchdog()
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
        if (id != null) {
            // 尽力通知桌面端退订；bridge 销毁时服务端订阅随之消亡，失败可忽略
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    call("unsubscribeConversationV4", listOf(scope() + mapOf("subscriptionId" to id)))
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

    private fun purgeStaleFragments() {
        val now = System.currentTimeMillis()
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
        val rowsObj = snap["rows"] as? Map<*, *>
        if (rowsObj != null) {
            val window = (rowsObj["window"] as? List<*>)?.mapNotNull(::parseRow).orEmpty()
            val head = window.firstOrNull()?.rowId
            val older = if (head != null) _rows.value.filter { it.rowId < head } else emptyList()
            _rows.value = (older + window).sortedBy { it.rowId }
            totalCount = (rowsObj["totalCount"] as? Number)?.toLong() ?: _rows.value.size.toLong()
            firstRowId = (rowsObj["firstRowId"] as? Number)?.toLong()
        } else {
            _rows.value = emptyList()
            totalCount = 0
            firstRowId = null
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
                "row.appended" -> parseRow(m["row"])?.let { row ->
                    _rows.update { list ->
                        if (list.any { it.rowId == row.rowId }) list
                        else list + row
                    }
                    totalCount += 1
                    if (firstRowId == null) firstRowId = row.rowId
                }
                "row.upserted" -> parseRow(m["row"])?.let(::mergeRow)
                "row.removed" -> {
                    // 保留 rowId < fromRowId 的行（对齐官方 fke 语义）
                    val from = (m["fromRowId"] as? Number)?.toLong() ?: continue
                    val before = _rows.value.size
                    _rows.update { list -> list.filter { it.rowId < from } }
                    val removed = before - _rows.value.size
                    if (firstRowId != null && from <= firstRowId!!) {
                        totalCount = 0
                        firstRowId = null
                    } else {
                        totalCount = (totalCount - removed).coerceAtLeast(0)
                    }
                }
                "row.delta" -> {
                    val rid = (m["rowId"] as? Number)?.toLong()
                        ?: m["rowId"]?.toString()?.toLongOrNull() ?: continue
                    val path = m["path"]?.toString() ?: continue
                    val append = m["append"]?.toString() ?: continue
                    _rows.update { list ->
                        list.map { row ->
                            if (row.rowId != rid) row else appendToRow(row, path, append)
                        }
                    }
                }
                "state.updated" -> {
                    val patch = m["patch"] as? Map<*, *> ?: continue
                    (patch["config"] as? Map<*, *>)?.let(::mergeConfig)
                    (patch["usage"] as? Map<*, *>)?.let(::mergeUsage)
                    (patch["control"] as? Map<*, *>)?.let(::mergeControl)
                    (patch["queue"] as? Map<*, *>)?.let(::mergeQueue)
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
        if (id == null || resyncing) return
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
                )
            } catch (e: Exception) {
                log("[v4] resync failed: ${e.message}")
            } finally {
                resyncing = false
            }
        }
    }

    // ────────────────────────── 历史窗口 ──────────────────────────

    /** 历史行窗口（分页：beforeRowId 传当前最早一行的 rowId） */
    suspend fun loadRows(sessionId: String, limit: Int = 200, beforeRowId: String? = null): List<ConvRow> = withContext(Dispatchers.IO) {
        val args = scope() + buildMap<String, Any> {
            put("sessionId", sessionId)
            put("limit", limit.toLong())
            if (beforeRowId != null) put("beforeRowId", beforeRowId)
        }
        val res = call("conversationRowsRangeV4", listOf(args)) as? Map<*, *> ?: run {
            log("[v4] loadRows: unexpected response shape")
            return@withContext _rows.value
        }
        val container = (res["rows"] as? Map<*, *>) ?: res
        val list = container["rows"] as? List<*> ?: run {
            log("[v4] loadRows: missing rows list")
            return@withContext _rows.value
        }
        mergeRows(list.mapNotNull(::parseRow))
        log("[v4] loadRows got ${list.size} rows (total=${_rows.value.size})")
        _rows.value
    }

    // ────────────────────────── 命令 ──────────────────────────

    /**
     * 发送用户文本。sessionId 为空时走官方首发路径：createSession 携带
     * firstInput（避免 send-before-subscribe 竞态），随后订阅新会话。
     * [attachments] 为 attachmentPut 返回的描述符（ref/fileName/mime/bytes）。
     */
    suspend fun sendText(
        text: String,
        sessionId: String? = _activeSessionId.value,
        requestedDelivery: String = "startNow",
        attachments: List<Map<String, Any?>>? = null,
    ): String? = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext sessionId
        var target = sessionId
        if (target == null) {
            val firstInput = buildMap<String, Any> {
                put("text", trimmed)
                if (!attachments.isNullOrEmpty()) put("attachments", attachments)
            }
            val res = sendCommand(null, "createSession", mapOf(
                "workspaceId" to workspaceKey,
                "firstInput" to firstInput,
            ))
            target = extractNewSessionId(res) ?: return@withContext null
            _activeSessionId.value = target
            runCatching { subscribeConversation(target) }
                .onFailure { log("[v4] subscribe(new) failed: $it") }
        } else {
            // 队列语义由桌面端 inputRouting 决定，sendText 只带 text/attachments（官方行为）
            val payload = buildMap<String, Any> {
                put("text", trimmed)
                if (!attachments.isNullOrEmpty()) put("attachments", attachments)
            }
            sendCommand(target, "sendText", payload)
        }
        target
    }

    /**
     * 仅创建会话（不带首发消息）。附件必须先有 sessionId 才能上传，
     * 带附件的新会话走：createSession → attachmentPut → sendText（对齐官方路径）。
     */
    suspend fun createSession(): String? = withContext(Dispatchers.IO) {
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
            ) as? Map<*, *>
            val next = (chunkRes?.get("nextChunkIndex") as? Number)?.toInt() ?: (chunkIndex + 1)
            if (next != chunkIndex + 1) throw IllegalStateException("fault.attachment.invalidServerProgress")
            chunkIndex = next
            log("[v4] attachmentPut chunk $chunkIndex/$totalChunks +${System.currentTimeMillis() - chunkStart}ms total=${System.currentTimeMillis() - startedAt}ms")
            onProgress?.invoke(chunkIndex.toFloat() / totalChunks)
        }
        onProgress?.invoke(1f)
        val commitRes = call("attachmentCommitV4", listOf(scope() + base), timeoutMs = 60_000) as? Map<*, *>
        log("[v4] attachmentPut committed $fileName in ${System.currentTimeMillis() - startedAt}ms ref=${commitRes?.get("ref")}")
        AttachmentUpload(commitRes?.get("ref")?.toString(), fileName, mime, bytes.size.toLong())
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
        val res = runCatching {
            channels.call(ChannelClient.Channel.ZCODE_TASK, "prepareWorkspace", listOf(scope()))
        }.getOrNull() as? Map<*, *> ?: return@withContext false
        val options = res["configOptions"] as? List<*> ?: return@withContext false
        for (raw in options) {
            val o = raw as? Map<*, *> ?: continue
            when (o["id"]?.toString()) {
                "model" -> {
                    val parsed = (o["options"] as? List<*>).orEmpty().mapNotNull { v ->
                        val vm = v as? Map<*, *> ?: return@mapNotNull null
                        val value = vm["value"]?.toString() ?: return@mapNotNull null
                        val slash = value.lastIndexOf('/')
                        val provider = if (slash <= 0) value else value.substring(0, slash)
                        val model = if (slash <= 0) value else value.substring(slash + 1)
                        ModelOption(provider = provider, model = model, label = vm["name"]?.toString()?.ifBlank { null } ?: model)
                    }
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
            )
        }.getOrNull()
        val ack = (res as? Map<*, *>)?.get("ack") as? Map<*, *>
        siSubId = ack?.get("subscriptionId")?.toString()
        ack?.get("logEpoch")?.toString()?.let { siLogEpoch = it }
        if (siSubId == null) {
            log("[v4-si] subscribeSessionsIndexV4: missing ack.subscriptionId")
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
        if (id == null || siResyncing) return
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
        )
    }

    private fun mergeRows(incoming: List<ConvRow>) {
        val byId = _rows.value.associateBy { it.rowId }.toMutableMap()
        incoming.forEach { byId[it.rowId] = it }
        _rows.value = byId.values.sortedBy { it.rowId }
    }

    private fun mergeRow(row: ConvRow) = mergeRows(listOf(row))

    private suspend fun call(method: String, args: List<Any?>, timeoutMs: Long = 30_000): Any? =
        channels.call(ChannelClient.Channel.ZCODE_AGENT, method, args, timeoutMs)

    fun dispose() {
        unsubscribeConversation()
        siCancel?.invoke()
        siCancel = null
        val id = siSubId
        siSubId = null
        if (id != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    channels.call(
                        ChannelClient.Channel.ZCODE_AGENT,
                        "unsubscribeSessionsIndexV4",
                        listOf(scope() + mapOf("subscriptionId" to id, "runtimePolicy" to "existing-only")),
                    )
                }
            }
        }
        sessionScope.cancel()
        bridge.dispose()
    }
}
