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
    const val IMAGE = "image"
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

    private val _rows = MutableStateFlow<List<ConvRow>>(emptyList())
    val rows: StateFlow<List<ConvRow>> = _rows.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _agentWorking = MutableStateFlow(false)
    val agentWorking: StateFlow<Boolean> = _agentWorking.asStateFlow()

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
            runCatching { loadRows(sessionId, limit = 120) }
                .onFailure { client.onLog?.invoke("[v4] loadRows failed: $it") }
        }
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
     * 停止当前生成。官方命令集合（sendText/sendGoalCommand/…）中未见独立 stop
     * 类型，此处按惯例发送 interrupt，属尽力而为。
     */
    suspend fun stop(sessionId: String? = _activeSessionId.value) = withContext(Dispatchers.IO) {
        if (sessionId == null) return@withContext
        runCatching {
            call(
                "sendConversationCommandV4",
                listOf(scope() + mapOf("envelope" to envelope(sessionId, "interrupt", emptyMap()))),
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

    // ── 订阅帧处理（与官方 ops schema 对应）──
    private fun applyConversationFrame(frame: Any?) {
        val ops = (frame as? Map<*, *>)?.get("ops") as? List<*> ?: return
        for (op in ops) {
            val m = op as? Map<*, *> ?: continue
            when (m["op"] as? String) {
                "row.appended", "row.upserted" -> parseRow(m["row"])?.let(::mergeRow)
                "row.removed" -> {
                    val from = m["fromRowId"]?.toString() ?: continue
                    _rows.update { list -> list.filterNot { it.rowId >= from } }
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

    private suspend fun call(method: String, args: List<Any?>, timeoutMs: Long = 30_000): Any? =
        channels.call(ChannelClient.Channel.ZCODE_AGENT, method, args, timeoutMs)

    fun dispose() {
        conversationCancel?.invoke()
        bridge.dispose()
    }
}
