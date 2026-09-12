package app.zemote.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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

/** 从 bootstrap 响应解析任务列表 */
suspend fun fetchTasksFromBootstrap(client: ZemoteClient): List<TaskEntry> {
    val res = client.bootstrap()
    val tasks = res["tasks"] as? List<*> ?: return emptyList()
    return tasks.mapNotNull { t ->
        val m = t as? Map<*, *> ?: return@mapNotNull null
        val id = m["taskId"]?.toString() ?: return@mapNotNull null
        TaskEntry(
            taskId = id,
            title = m["title"]?.toString() ?: "未命名任务",
            status = m["displayStatus"]?.toString(),
            workspacePath = m["workspacePath"]?.toString(),
            workspaceLabel = m["workspaceLabel"]?.toString(),
            updatedAt = (m["updatedAt"] as? Number)?.toLong(),
        )
    }
}

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

/**
 * Conversation V4 仓库：封装从官方 Web 客户端逆向出的 zcode-agent 通道 RPC。
 *
 * 会话列表（任务页）走 bootstrap 的 tasks 字段，见 [fetchTasksFromBootstrap]。
 * 对话行（聊天页）走订阅推送 + 历史窗口：
 *  - subscribeConversationV4({scope, sessionId}) → {ack:{subscriptionId}}
 *  - onDynamicConversationFrame 事件推送 ops：row.appended / row.upserted /
 *    row.removed / row.delta / state.updated
 *  - conversationRowsRangeV4({scope, sessionId, beforeRowId?, limit}) → 历史行窗口
 *  - sendConversationCommandV4({scope, envelope}) → 发送（sendText / createSession）
 */
class ConversationV4Session private constructor(
    val client: ZemoteClient,
    val bridge: BridgeSession,
    val workspaceKey: String,
    private val scopeParams: Map<String, Any>? = null,
) {
    companion object {
        private val CLIENT_ID = UUID.randomUUID().toString()

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
    private var conversationSubscribed = false
    private var conversationCancel: (() -> Unit)? = null
    private var handshakeDone = false

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

    // ── scope 构造：优先用 bootstrap 返回的完整工作区 map ──
    private fun scope(): Map<String, Any> {
        if (scopeParams != null) return scopeParams
        return buildMap {
            put("workspacePath", workspaceKey)
            put("workspaceIdentity", workspaceKey)
        }
    }

    /**
     * 订阅指定会话并拉取历史。必须在 IO 线程调用（Compose 的
     * AndroidUiDispatcher 在静态界面不产生帧，withTimeout 会被饿死）。
     */
    suspend fun openConversation(sessionId: String?) = withContext(Dispatchers.IO) {
        if (_activeSessionId.value == sessionId && _rows.value.isNotEmpty()) return@withContext
        _activeSessionId.value = sessionId
        _rows.value = emptyList()
        _loading.value = true
        if (!conversationSubscribed) {
            // 桌面端 assertReady 要求先 hello 握手，否则报 fault.connection.handshakeRequired
            runCatching { ensureHandshake() }
                .onFailure { client.onLog?.invoke("[v4] handshake failed: $it") }
            val subScope = buildMap<String, Any> {
                putAll(scope())
                if (sessionId != null) put("sessionId", sessionId)
            }
            conversationCancel = channels.addEventListener(
                ChannelClient.Channel.ZCODE_AGENT,
                "onDynamicConversationFrame",
                onEvent = { frame -> applyConversationFrame(frame) },
                arg = subScope,
            )
            runCatching {
                call("subscribeConversationV4", listOf(subScope))
            }.onFailure {
                client.onLog?.invoke("[v4] subscribeConversationV4 failed: $it")
            }
            conversationSubscribed = true
        }
        if (sessionId != null) {
            runCatching { loadRows(sessionId, limit = 40) }
                .onFailure { client.onLog?.invoke("[v4] loadRows failed: $it") }
        }
        _loading.value = false
    }

    /** hello + clientHello 握手（每个 bridge 连接一次） */
    private suspend fun ensureHandshake() {
        if (handshakeDone) return
        call("helloConversationV4", emptyList())
        call(
            "initializeConversationV4",
            listOf(mapOf<String, Any>(
                "kind" to "clientHello",
                "protocolVersion" to 3L,
                "clientId" to CLIENT_ID,
                "clientKind" to "web",
                "appVersion" to "unknown",
                "capabilities" to mapOf<String, Any>("workspaceHookReviewUi" to true),
            )),
        )
        handshakeDone = true
    }

    /** 历史行窗口（分页：beforeRowId 传当前最早一行的 rowId） */
    suspend fun loadRows(sessionId: String, limit: Int = 120, beforeRowId: String? = null): List<ConvRow> = withContext(Dispatchers.IO) {
        val args = scope() + buildMap<String, Any> {
            put("sessionId", sessionId)
            put("limit", limit.toLong())
            if (beforeRowId != null) put("beforeRowId", beforeRowId)
        }
        val res = call("conversationRowsRangeV4", listOf(args)) as? Map<*, *> ?: return@withContext _rows.value
        val container = (res["rows"] as? Map<*, *>) ?: res
        val list = container["rows"] as? List<*> ?: return@withContext _rows.value
        mergeRows(list.mapNotNull(::parseRow))
        _rows.value
    }

    /**
     * 发送用户文本。sessionId 为空时先 createSession 并返回新 sessionId。
     * AI 回复中传 requestedDelivery = "queue"（官方排队语义）。
     */
    suspend fun sendText(
        text: String,
        sessionId: String? = _activeSessionId.value,
        requestedDelivery: String = "startNow",
    ): String? = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext sessionId
        var target = sessionId
        if (target == null) {
            val res = call(
                "sendConversationCommandV4",
                listOf(scope() + mapOf("envelope" to envelope(null, "createSession", mapOf<String, Any>("workspaceIdentity" to workspaceKey)))),
            ) as? Map<*, *>
            target = extractNewSessionId(res) ?: return@withContext null
            _activeSessionId.value = target
        }
        val payload = mapOf<String, Any>(
            "text" to trimmed,
            "requestedDelivery" to requestedDelivery,
        )
        call(
            "sendConversationCommandV4",
            listOf(scope() + mapOf("envelope" to envelope(target, "sendText", payload))),
        )
        target
    }

    /**
     * 停止当前生成（官方 stop 命令）。
     */
    suspend fun stop(sessionId: String? = _activeSessionId.value) = withContext(Dispatchers.IO) {
        if (sessionId == null) return@withContext
        runCatching {
            call(
                "sendConversationCommandV4",
                listOf(scope() + mapOf("envelope" to envelope(sessionId, "stop", emptyMap()))),
            )
        }
    }

    // ── 信封 ──
    private fun envelope(sessionId: String?, type: String, payload: Map<String, Any>): Map<String, Any> = buildMap {
        put("commandId", UUID.randomUUID().toString())
        put("clientId", CLIENT_ID)
        if (sessionId != null) put("sessionId", sessionId)
        put("type", type)
        put("payload", payload)
        put("issuedAt", System.currentTimeMillis())
    }

    private fun extractNewSessionId(res: Map<*, *>?): String? {
        val result = res?.get("result") as? Map<*, *> ?: res ?: return null
        if (result["type"] == "createSession") return result["sessionId"]?.toString()
        return null
    }

    // ── 订阅帧处理（真实帧形：{topic, subscriptionId, payload:{kind:'snapshot'|'deltas', ...}}，
    //    EventFire 的 value2 是参数列表（[frame]））──
    private fun applyConversationFrame(frame: Any?) {
        val f = (frame as? List<*>)?.firstOrNull() ?: frame
        val map = f as? Map<*, *> ?: return
        val payload = (map["payload"] as? Map<*, *>) ?: map
        when (payload["kind"]) {
            "snapshot" -> applySnapshot(payload["snapshot"] ?: payload["state"])
            "deltas" -> applyOps(payload["deltas"] as? List<*>)
            else -> applyOps((payload["ops"] ?: map["ops"]) as? List<*>)
        }
    }

    /** 快照：全量替换行 + 状态（config/usage 递归查找，兼容不同嵌套层级） */
    private fun applySnapshot(snap: Any?) {
        findMapWithKeys(snap, "config")?.let { st ->
            (st["config"] as? Map<*, *>)?.let(::mergeConfig)
            (st["usage"] as? Map<*, *>)?.let(::mergeUsage)
        }
        findMapWithKeys(snap, "window", "totalCount")?.let { box ->
            val list = (box["window"] as? List<*>)?.mapNotNull(::parseRow)
            if (list != null) _rows.value = list.sortedBy { it.rowId }
        }
    }

    /** 增量：row.appended / row.upserted / row.removed / row.delta / state.updated */
    private fun applyOps(ops: List<*>?) {
        if (ops == null) return
        for (raw in ops) {
            val m = raw as? Map<*, *> ?: continue
            when (m["op"] as? String) {
                "row.appended", "row.upserted" -> parseRow(m["row"])?.let(::mergeRow)
                "row.removed" -> {
                    val from = (m["fromRowId"] as? Number)?.toLong()
                        ?: m["fromRowId"]?.toString()?.toLongOrNull() ?: continue
                    _rows.update { list -> list.filterNot { it.rowId >= from } }
                }
                "row.delta" -> {
                    val rid = (m["rowId"] as? Number)?.toLong()
                        ?: m["rowId"]?.toString()?.toLongOrNull() ?: continue
                    val path = m["path"]?.toString() ?: continue
                    val append = m["append"]?.toString() ?: continue
                    _rows.update { list ->
                        list.map { row ->
                            when {
                                row.rowId != rid -> row
                                path == "text" -> row.copy(text = row.text + append)
                                path == "inputText" -> row.copy(inputText = row.inputText + append)
                                path == "output.text" -> row.copy(outputText = row.outputText + append)
                                path == "summaryText" -> row.copy(summaryText = row.summaryText + append)
                                else -> row
                            }
                        }
                    }
                }
                "state.updated" -> {
                    val patch = m["patch"] as? Map<*, *> ?: continue
                    (patch["config"] as? Map<*, *>)?.let(::mergeConfig)
                    (patch["usage"] as? Map<*, *>)?.let(::mergeUsage)
                    if (patch.containsKey("working")) {
                        _agentWorking.value = patch["working"] == true
                    }
                }
            }
        }
    }

    /** 递归查找同时含有指定键的 map（快照嵌套层级不固定，防御式解析） */
    private fun findMapWithKeys(node: Any?, vararg keys: String): Map<*, *>? {
        if (node is Map<*, *>) {
            if (keys.all { node.containsKey(it) }) return node
            for (v in node.values) {
                findMapWithKeys(v, *keys)?.let { return it }
            }
        } else if (node is List<*>) {
            for (v in node) {
                findMapWithKeys(v, *keys)?.let { return it }
            }
        }
        return null
    }

    private fun mergeConfig(m: Map<*, *>) {
        val levels = (m["thoughtLevels"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            .orEmpty()
        _convConfig.value = ConvConfig(
            provider = m["provider"]?.toString(),
            model = m["model"]?.toString(),
            thought = m["thought"]?.toString().orEmpty().ifEmpty { null },
            thoughtLevels = levels,
            mode = m["mode"]?.toString(),
        )
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

    /**
     * 切换思考等级（官方 switchModelConfig 命令；provider/model 取当前配置）。
     */
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
        val env = envelope(
            _activeSessionId.value, "switchModelConfig",
            mapOf("provider" to provider, "model" to model, "thought" to thought),
        )
        return try {
            call("sendConversationCommandV4", listOf(scope() + mapOf("envelope" to env)))
            _convConfig.update { (it ?: ConvConfig()).copy(provider = provider, model = model, thought = thought.ifEmpty { null }) }
            true
        } catch (e: Exception) {
            client.onLog?.invoke("[v4] switchModelConfig failed: ${e.message}")
            false
        }
    }

    private fun parseRow(raw: Any?): ConvRow? {
        val m = raw as? Map<*, *> ?: return null
        // rowId 服务端是递增数字；visibility 过滤掉非可见行
        val rowId = (m["rowId"] as? Number)?.toLong() ?: m["rowId"]?.toString()?.toLongOrNull() ?: return null
        if (m["visibility"] != null && m["visibility"] != "visible") return null
        val kind = (m["kind"] as? String) ?: (m["type"] as? String) ?: return null
        if (kind == ConvKinds.TURN_HEADER) return null // 回合分隔头，不进时间线
        val output = m["output"] as? Map<*, *>
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
        conversationCancel?.invoke()
        bridge.dispose()
    }
}
