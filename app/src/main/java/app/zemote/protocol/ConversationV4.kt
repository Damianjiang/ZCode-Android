package app.zemote.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 会话列表条目（任务页） */
data class SessionEntry(
    val sessionId: String,
    val topic: String? = null,
    val status: String? = null,
    val updatedAt: Long? = null,
    val running: Boolean = false,
)

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

/** 对话时间线行（聊天页）。kind 见 [ConvKinds]。 */
data class ConvRow(
    val rowId: String,
    val kind: String,
    val text: String = "",
    val inputText: String = "",
    val outputText: String = "",
    val summaryText: String = "",
    val toolName: String? = null,
    val additions: Int? = null,
    val issuedAt: Long? = null,
)

object ConvKinds {
    const val USER_INPUT = "userInput"
    const val ASSISTANT_TEXT = "assistantText"
    const val REASONING = "reasoning"
    const val TOOL_CALL = "toolCall"
    const val SUBAGENT = "subagent"
}

/**
 * 会话订阅帧里的 ops（与官方 Web 客户端 Ole schema 对应）。
 * row.appended / row.upserted / row.removed / row.delta / state.updated
 */
private data class ConvOp(val op: String, val row: ConvRow?, val fromRowId: String?, val deltaRowId: String?, val deltaPath: String?, val deltaAppend: String?)

/**
 * Conversation V4 仓库：封装官方 Web 客户端逆向出的 zcode-agent 通道 RPC。
 *
 * 调用序列（与官方 `gb()` / subscribe 流程一致）：
 *  1. helloConversationV4() → initializeConversationV4(clientHello)
 *  2. subscribeSessionsIndexV4 + onDynamicSessionsIndexFrame 事件 → 会话列表
 *  3. subscribeConversationV4 + onDynamicConversationFrame 事件 → 对话行 ops
 *  4. conversationRowsRangeV4 → 拉取历史行窗口
 *  5. sendConversationCommandV4(sendText/createSession) → 发送
 */
class ConversationV4Session private constructor(
    val client: ZemoteClient,
    val bridge: BridgeSession,
    val workspaceKey: String,
    private val scopeParams: Map<String, Any>? = null,
) {
    companion object {
        private const val APP_VERSION = "unknown"
        private val CLIENT_ID = UUID.randomUUID().toString()

        // ── 3. 对话订阅 + 历史拉取（bridge 绑定 taskId 后服务端才会推送对话）──
        suspend fun open(
            client: ZemoteClient,
            workspaceKey: String,
            taskId: String? = null,
            scopeParams: Map<String, Any>? = null,
        ): ConversationV4Session = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val bridge = client.openBridge(workspaceKey, taskId)
            val session = ConversationV4Session(client, bridge, workspaceKey, scopeParams)
            session.startSessionsIndex()
            session
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels get() = bridge.channelsClient

    private var handshaken = false
    private var connectionId: String? = null
    private var conversationSubscribed = false
    private var sessionsCancel: (() -> Unit)? = null
    private var conversationCancel: (() -> Unit)? = null
    private var disposed = false

    private val _sessions = MutableStateFlow<List<SessionEntry>>(emptyList())
    val sessions: StateFlow<List<SessionEntry>> = _sessions.asStateFlow()

    private val _rows = MutableStateFlow<List<ConvRow>>(emptyList())
    val rows: StateFlow<List<ConvRow>> = _rows.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _agentWorking = MutableStateFlow(false)
    val agentWorking: StateFlow<Boolean> = _agentWorking.asStateFlow()

    // ── scope 构造：优先用 bootstrap 返回的完整工作区 map（旧方言 hello 需要）──
    private fun scope(): Map<String, Any> {
        if (scopeParams != null) return scopeParams
        return buildMap {
            put("workspacePath", workspaceKey)
            put("workspaceIdentity", workspaceKey)
        }
    }

    // ── 1. 握手（多通道 × 多方言探测）──
    private suspend fun handshake() {
        if (handshaken) return
        val scopeMap = scope()
        val appVer = client.params.appVersion ?: "3.8.1"
        client.onLog?.invoke("[v4] handshake start (scope=$scopeMap)")

        val candidates = listOf("zcode-agent", "agent", "zcode", "zcode-session")
        // 方案 A（旧方言）：hello 携带 [scope, clientId, appVersion]
        for (ch in candidates) {
            val res = runCatching {
                callOn(ch, "helloConversationV4", listOf(scopeMap, CLIENT_ID, appVer), timeoutMs = 6_000)
            }.getOrNull()
            client.onLog?.invoke("[v4] probe ch=$ch old → $res")
            if (res != null) {
                probeChannel = ch
                handshaken = true
                return
            }
        }
        // 方案 B（新版 Web 方言）：hello() 无参
        for (ch in candidates) {
            val res = runCatching {
                callOn(ch, "helloConversationV4", emptyList(), timeoutMs = 6_000)
            }.getOrNull()
            client.onLog?.invoke("[v4] probe ch=$ch new → $res")
            if (res != null) {
                probeChannel = ch
                handshaken = true
                return
            }
        }
        client.onLog?.invoke("[v4] all hello probes failed")
    }

    @Volatile
    private var probeChannel: String? = null

    private suspend fun callOn(channelName: String, method: String, args: List<Any?>, timeoutMs: Int): Any? {
        val channel = ChannelClient.Channel.entries.firstOrNull { it.channelName == channelName }
            ?: return null
        return channels.call(channel, method, args, timeoutMs.toLong())
    }

    // ── 2. 会话列表订阅 ──
    private suspend fun startSessionsIndex() {
        sessionsCancel = channels.addEventListener(
            ChannelClient.Channel.ZCODE_AGENT,
            "onDynamicSessionsIndexFrame",
            onEvent = { frame ->
                client.onLog?.invoke("[v4] sessions frame: $frame")
                applySessionsFrame(frame)
            },
            // 官方把 scope 作为监听参数传给服务端，服务端据此推送对应工作区
            arg = scope(),
        )
        runCatching {
            val res = call(
                "subscribeSessionsIndexV4",
                listOf(scope() + mapOf("runtimePolicy" to "existing-only")),
            )
            client.onLog?.invoke("[v4] subscribeSessionsIndexV4 → $res")
        }.onFailure { client.onLog?.invoke("[v4] subscribeSessionsIndexV4 failed: $it") }
    }

    // ── 3. 对话订阅 + 历史拉取 ──
    // 注意：必须在 IO 上执行。Compose 的 AndroidUiDispatcher 在静态界面不产生帧，
    // withTimeout 的定时恢复会被饿死（表现为 RPC 永远挂起）。
    suspend fun openConversation(sessionId: String?) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        _activeSessionId.value = sessionId
        _rows.value = emptyList()
        if (!conversationSubscribed) {
            val subScope = buildMap<String, Any> {
                putAll(scope())
                if (sessionId != null) put("sessionId", sessionId)
            }
            conversationCancel = channels.addEventListener(
                ChannelClient.Channel.ZCODE_AGENT,
                "onDynamicConversationFrame",
                onEvent = { frame ->
                    client.onLog?.invoke("[v4] conversation frame: $frame")
                    applyConversationFrame(frame)
                },
                arg = subScope,
            )
            runCatching {
                val res = call("subscribeConversationV4", listOf(subScope))
                client.onLog?.invoke("[v4] subscribeConversationV4 → $res")
            }.onFailure { client.onLog?.invoke("[v4] subscribeConversationV4 failed: $it") }
            conversationSubscribed = true
        }
        if (sessionId != null) {
            runCatching { loadRows(sessionId, limit = 120) }
                .onFailure { client.onLog?.invoke("[v4] loadRows failed: $it") }
        }
    }

    /** 历史行窗口（分页：beforeRowId 传最早一行 rowId） */
    suspend fun loadRows(sessionId: String, limit: Int = 120, beforeRowId: String? = null): List<ConvRow> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val args = scope() + buildMap<String, Any> {
            put("sessionId", sessionId)
            put("limit", limit.toLong())
            if (beforeRowId != null) put("beforeRowId", beforeRowId)
        }
        val res = call("conversationRowsRangeV4", listOf(args)) as? Map<*, *> ?: return@withContext _rows.value
        val window = (res["rows"] as? Map<*, *>) ?: res
        val list = (window["rows"] as? List<*>) ?: (res["rows"] as? List<*>) ?: return@withContext _rows.value
        val parsed = list.mapNotNull { parseRow(it) }
        mergeRows(parsed)
        _rows.value
    }

    // ── 4. 发送 ──
    private fun envelope(sessionId: String?, type: String, payload: Map<String, Any>): Map<String, Any> = buildMap {
        put("commandId", UUID.randomUUID().toString())
        put("clientId", CLIENT_ID)
        if (sessionId != null) put("sessionId", sessionId)
        put("type", type)
        put("payload", payload)
        put("issuedAt", System.currentTimeMillis())
    }

    /** 发送用户文本。sessionId 为空时先 createSession，返回（新）sessionId。 */
    suspend fun sendText(
        text: String,
        sessionId: String? = _activeSessionId.value,
        requestedDelivery: String = "startNow",
    ): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
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

    // ── 帧处理 ──
    private fun applySessionsFrame(frame: Any?) {
        val entries = mutableListOf<SessionEntry>()
        walkMaps(frame) { m ->
            val sid = m["sessionId"] as? String
            if (!sid.isNullOrEmpty() && m.keys.any { it == "topic" || it == "status" || it == "updatedAt" || it == "title" }) {
                entries.add(parseSession(m))
            }
        }
        if (entries.isNotEmpty()) {
            val byId = _sessions.value.associateBy { it.sessionId }.toMutableMap()
            entries.forEach { byId[it.sessionId] = it }
            _sessions.value = byId.values.sortedWith(
                compareByDescending<SessionEntry> { it.running }.thenByDescending { it.updatedAt ?: 0L }
            )
        }
    }

    private fun parseSession(m: Map<*, *>): SessionEntry {
        val status = (m["status"] as? String) ?: (m["state"] as? String)
        val updated = (m["updatedAt"] as? Number)?.toLong()
            ?: (m["lastActivityAt"] as? Number)?.toLong()
            ?: (m["timestamp"] as? Number)?.toLong()
        return SessionEntry(
            sessionId = m["sessionId"]?.toString() ?: "",
            topic = (m["topic"] as? String) ?: (m["title"] as? String) ?: (m["name"] as? String),
            status = status,
            updatedAt = updated,
            running = status?.contains("running", ignoreCase = true) == true
                    || status?.contains("active", ignoreCase = true) == true
                    || m["running"] == true,
        )
    }

    private fun applyConversationFrame(frame: Any?) {
        val root = frame as? Map<*, *> ?: return
        val ops = root["ops"] as? List<*> ?: return
        for (op in ops) {
            val m = op as? Map<*, *> ?: continue
            when (m["op"] as? String) {
                "row.appended", "row.upserted" -> parseRow(m["row"])?.let { mergeRow(it) }
                "row.removed" -> {
                    val from = m["fromRowId"] as? String ?: continue
                    _rows.update { list -> list.filterNot { it.rowId >= from && it.rowId.startsWith(from.take(8)) } }
                }
                "row.delta" -> {
                    val rid = m["rowId"]?.toString() ?: continue
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
                    if (patch.containsKey("working")) {
                        _agentWorking.value = patch["working"] == true
                    }
                }
            }
        }
    }

    private fun parseRow(raw: Any?): ConvRow? {
        val m = raw as? Map<*, *> ?: return null
        val rowId = m["rowId"]?.toString() ?: return null
        val kind = (m["kind"] as? String) ?: (m["type"] as? String) ?: return null
        val output = m["output"] as? Map<*, *>
        return ConvRow(
            rowId = rowId,
            kind = kind,
            text = m["text"]?.toString() ?: "",
            inputText = m["inputText"]?.toString() ?: "",
            outputText = output?.get("text")?.toString() ?: "",
            summaryText = m["summaryText"]?.toString() ?: "",
            toolName = (m["toolName"] as? String) ?: (m["tool"] as? String),
            additions = (m["additions"] as? Number)?.toInt(),
            issuedAt = (m["issuedAt"] as? Number)?.toLong() ?: (m["createdAt"] as? Number)?.toLong(),
        )
    }

    private fun mergeRows(incoming: List<ConvRow>) {
        val byId = _rows.value.associateBy { it.rowId }.toMutableMap()
        incoming.forEach { byId[it.rowId] = it }
        _rows.value = byId.values.toList()
    }

    private fun mergeRow(row: ConvRow) = mergeRows(listOf(row))

    private fun extractNewSessionId(res: Map<*, *>?): String? {
        val result = res?.get("result") as? Map<*, *> ?: res
        if ((result?.get("type") as? String) == "createSession") {
            return result["sessionId"]?.toString()
        }
        return null
    }

    /** 深度优先收集所有含 sessionId 的 map（会话帧结构未完全公开，做防御性解析） */
    private fun walkMaps(node: Any?, visit: (Map<*, *>) -> Unit) {
        when (node) {
            is Map<*, *> -> {
                visit(node)
                node.values.forEach { walkMaps(it, visit) }
            }
            is List<*> -> node.forEach { walkMaps(it, visit) }
        }
    }

    private suspend fun call(method: String, args: List<Any?>, timeoutMs: Int = 30_000): Any? =
        channels.call(ChannelClient.Channel.ZCODE_AGENT, method, args, timeoutMs.toLong())

    fun dispose() {
        if (disposed) return
        disposed = true
        sessionsCancel?.invoke()
        conversationCancel?.invoke()
        bridge.dispose()
    }
}
