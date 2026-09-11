package app.zemote.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/**
 * High-level facade replicating the web client's otn() flow.
 * relay connect → pair → bootstrap → workspace-bridge → channel RPC.
 */
class ZemoteClient(
    val params: ZemoteConnectionParams,
    val onLog: ((String) -> Unit)? = null,
) {
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val relay = RelayClient(params, onLog)

    private val _workspaceListUpdated = MutableSharedFlow<Map<String, Any>>(replay = 0, extraBufferCapacity = 16)
    val workspaceListUpdated: Flow<Map<String, Any>> = _workspaceListUpdated

    private val _state = MutableStateFlow<ZemoteClientState>(ZemoteClientState.IDLE)
    val state: StateFlow<ZemoteClientState> = _state.asStateFlow()

    enum class ZemoteClientState { IDLE, CONNECTING, PAIRING, PAIRED, ERROR, KICKED, CLOSED }

    private val activeBridges = ConcurrentHashMap<String, BridgeSession>()
    private val pendingMatchers = ConcurrentHashMap<String, (Map<String, Any>) -> Boolean>()
    private val pendingCompleters = ConcurrentHashMap<String, CompletableDeferred<Map<String, Any>>>()
    private var bridgeGeneration = 0

    suspend fun connect() {
        _state.value = ZemoteClientState.CONNECTING
        relay.start()
    }

    /**
     * Waits until the relay reports `matched` (paired with the desktop).
     */
    suspend fun waitPaired(timeoutMs: Long = 60_000L) {
        if (_state.value == ZemoteClientState.PAIRED) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (relay.state.value == RelayState.PAIRED) {
                _state.value = ZemoteClientState.PAIRED
                return
            }
            if (relay.state.value == RelayState.ERROR || relay.state.value == RelayState.KICKED) {
                throw TimeoutException("relay error: ${relay.state.value}")
            }
            delay(100)
        }
        throw TimeoutException("pairing timeout")
    }

    /**
     * Opens a workspace bridge. Mirrors the web client's `openBridge()` flow.
     * Returns a [BridgeSession] that owns the rpc-frame transport + IPC channel stack.
     */
    suspend fun openBridge(workspaceKey: String, taskId: String? = null): BridgeSession {
        val bridgeSessionId = generateRequestId("bridge")
        val generation = ++bridgeGeneration
        val requestId = generateRequestId("workspace-bridge")
        val payload = buildMap<String, Any> {
            put("zcode_type", "workspace-bridge-open")
            put("requestId", requestId)
            put("bridgeSessionId", bridgeSessionId)
            put("bridgeGeneration", generation)
            put("workspaceKey", workspaceKey)
            if (taskId != null) put("taskId", taskId)
        }
        val response = request(payload, match = { p ->
            val t = p["zcode_type"] as? String
            val bsid = p["bridgeSessionId"] as? String
            (t == "workspace-bridge-ready" || t == "workspace-bridge-error") && bsid == bridgeSessionId
        })
        if (response["zcode_type"] == "workspace-bridge-error") {
            throw IOException("workspace-bridge-error: ${response["error"]}")
        }
        @Suppress("UNCHECKED_CAST")
        val bridge = (response["bridge"] as? Map<String, Any>) ?: emptyMap()
        onLog?.invoke("[bridge] ready: $bridge")
        val session = BridgeSession(
            bridge = bridge,
            relayClient = relay,
            onDispose = { activeBridges.remove(bridge["bridgeSessionId"] as? String) },
            onLog = onLog,
        )
        activeBridges[bridge["bridgeSessionId"] as? String ?: ""] = session
        sendMobileViewState(
            workspaceKey = bridge["workspaceKey"] as? String ?: workspaceKey,
            taskId = bridge["initialTaskId"] as? String ?: taskId,
        )
        return session
    }

    /**
     * Sends a request over relay payloads and waits for the matching response.
     * Mirrors the web client's `k()` helper: every pending matcher is tested
     * against every payload; responses are NOT guaranteed to echo our requestId.
     */
    private suspend fun request(
        payload: Map<String, Any>,
        timeoutMs: Long = 30_000L,
        match: (Map<String, Any>) -> Boolean,
    ): Map<String, Any> {
        val requestId = payload["requestId"] as? String ?: throw IllegalArgumentException("missing requestId")
        val completer = CompletableDeferred<Map<String, Any>>()
        pendingMatchers[requestId] = match
        pendingCompleters[requestId] = completer
        relay.send(payload)
        return try {
            withTimeout(timeoutMs) { completer.await() }
        } finally {
            pendingMatchers.remove(requestId)
            pendingCompleters.remove(requestId)
        }
    }

    /** bootstrap-request → bootstrap-response (workspaces overview). */
    suspend fun bootstrap(): Map<String, Any> {
        val id = generateRequestId("bootstrap")
        val res = request(mapOf("zcode_type" to "bootstrap-request", "requestId" to id), match = { p ->
            (p["zcode_type"] as? String) == "bootstrap-response" && p["requestId"] == id
        })
        return (res["result"] as? Map<String, Any>) ?: res
    }

    /** workspace-list-request → workspace-list-response. */
    suspend fun listWorkspaces(): Any? {
        val id = generateRequestId("workspace-list")
        val res = request(mapOf("zcode_type" to "workspace-list-request", "requestId" to id), match = { p ->
            (p["zcode_type"] as? String) == "workspace-list-response" && p["requestId"] == id
        })
        return res["result"]
    }

    /** workspace-reconnect-request → workspace-reconnect-response. */
    suspend fun reconnectWorkspace(workspaceKey: String): Map<String, Any> {
        val id = generateRequestId("workspace-reconnect")
        return request(mapOf(
            "zcode_type" to "workspace-reconnect-request",
            "requestId" to id,
            "workspaceKey" to workspaceKey,
        ), match = { p ->
            (p["zcode_type"] as? String) == "workspace-reconnect-response" &&
                    p["requestId"] == id && p["workspaceKey"] == workspaceKey
        })
    }

    /**
     * Reopens a degraded/dead bridge: new `workspace-bridge-open` (fresh
     * bridgeSessionId, bumped generation, carries recoveryId), then swaps
     * the stack into the existing [BridgeSession].
     */
    private suspend fun reopenBridge(session: BridgeSession) {
        val oldBridge = session.bridge
        val bridgeSessionId = generateRequestId("bridge")
        val generation = ++bridgeGeneration
        val requestId = generateRequestId("workspace-bridge")
        onLog?.invoke("[bridge] reopen ${session.workspaceKey} (gen $generation)")
        val payload = buildMap<String, Any> {
            put("zcode_type", "workspace-bridge-open")
            put("requestId", requestId)
            put("bridgeSessionId", bridgeSessionId)
            put("bridgeGeneration", generation)
            val recId = oldBridge["recoveryId"] as? String
            if (recId != null) put("recoveryId", recId as Any)
            val wk = session.workspaceKey ?: ""
            put("workspaceKey", wk as Any)
        }
        val response = request(payload, match = { p ->
            val t = p["zcode_type"] as? String
            val bsid = p["bridgeSessionId"] as? String
            (t == "workspace-bridge-ready" || t == "workspace-bridge-error") && bsid == bridgeSessionId
        })
        if (response["zcode_type"] == "workspace-bridge-error") {
            throw IOException("workspace-bridge-error: ${response["error"]}")
        }
        @Suppress("UNCHECKED_CAST")
        val bridge = (response["bridge"] as? Map<String, Any>) ?: emptyMap()
        session.swapBridge(bridge, relay)
        onLog?.invoke("[bridge] reopened: $bridge")
    }

    fun pokeRelay() { relay.poke() }

    /** mobile-view-state-update (mirrors `N()` in the web client). */
    fun sendMobileViewState(workspaceKey: String, taskId: String? = null) {
        val viewState = linkedMapOf<String, Any?>()
        viewState["activeWorkspaceKey"] = workspaceKey
        if (taskId != null) viewState["activeTaskId"] = taskId
        viewState["updatedAt"] = System.currentTimeMillis()
        val deviceInfo = mapOf(
            "platform" to "android",
            "version" to (params.appVersion ?: "web"),
            "name" to "Zemote",
        )
        relay.send(
            mapOf(
                "zcode_type" to "mobile-view-state-update",
                "viewState" to viewState.mapValues { it.value as Any },
                "deviceInfo" to deviceInfo,
            )
        )
    }

    init {
        // Collect relay payloads and dispatch to matchers + bridge sessions.
        // This is the single dispatcher — BridgeSessions listen directly for
        // rpc-frame messages to avoid duplication.
        relayScope.launch {
            relay.payloads.collect { payload ->
                dispatchPayload(payload)
            }
        }
    }

    private fun dispatchPayload(payload: Map<String, Any>) {
        val type = payload["zcode_type"] as? String
        when (type) {
            "workspace-list-updated" -> {
                val result = payload["result"]
                if (result is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    relayScope.launch { _workspaceListUpdated.emit(result as Map<String, Any>) }
                }
                return
            }
            "bridge-degraded" -> {
                val bsid = payload["bridgeSessionId"] as? String
                val reason = payload["reason"] as? String
                onLog?.invoke("[bridge] degraded: $bsid reason=$reason")
                activeBridges.values.filter { it.bridge["bridgeSessionId"] == bsid }.forEach { it.degraded.value = reason }
                if (bsid != null) relayScope.launch { recoverActiveBridges() }
                return
            }
            "rpc-frame", "rpc-frame-ack" -> {
                // Each BridgeSession has its own relay.payloads collector that
                // routes frames to its transport. Nothing to do here.
                return
            }
        }
        // Fallback: pending matcher logic (mirrors the web client's `k()` helper)
        // Every pending matcher is tested against every payload.
        val done = mutableListOf<String>()
        pendingMatchers.forEach { (reqId, matcher) ->
            val completer = pendingCompleters[reqId]
            if (completer != null && !completer.isCompleted && matcher(payload)) {
                done.add(reqId)
                completer.complete(payload)
            }
        }
        done.forEach {
            pendingMatchers.remove(it)
            pendingCompleters.remove(it)
        }
    }

    /**
     * Retries bridge recovery until it succeeds, so a degraded bridge never
     * strands commands ("can't send after reconnect").
     */
    private suspend fun recoverActiveBridges() {
        onLog?.invoke("[bridge] recovering ${activeBridges.size} bridge(s)")
        for (session in activeBridges.values.toList()) {
            if (session.isRecovering) continue
            session.isRecovering = true
            relayScope.launch {
                try {
                    for (attempt in 1..15) {
                        if (session.isDisposed) return@launch
                        // 1) Cheap path: workspace-reconnect-request
                        val workspaceKey = session.workspaceKey
                        if (workspaceKey != null) {
                            try {
                                val res = reconnectWorkspace(workspaceKey)
                                if (res["success"] == true) {
                                    onLog?.invoke("[bridge] reconnected $workspaceKey")
                                    session.degraded.value = null
                                    session.recovered.value = session.recovered.value + 1
                                    session.isRecovering = false
                                    return@launch
                                }
                            } catch (e: Exception) {
                                onLog?.invoke("[bridge] reconnect-request failed: $e")
                            }
                        } else {
                            session.degraded.value = null
                            session.recovered.value = session.recovered.value + 1
                            session.isRecovering = false
                            return@launch
                        }
                        if (session.isDisposed) return@launch
                        onLog?.invoke("[bridge] recovery attempt $attempt failed, retrying")
                        delay(3000)
                    }
                    // 2) Full reopen after exhausting retries
                    val wk = session.workspaceKey
                    if (wk != null && !session.isDisposed) {
                        try {
                            reopenBridge(session)
                            session.degraded.value = null
                            session.recovered.value = session.recovered.value + 1
                        } catch (e: Exception) {
                            session.degraded.value = "reopen-failed: $e"
                        }
                    }
                } finally { session.isRecovering = false }
            }
        }
    }

    suspend fun dispose() {
        relayScope.cancel()
        for (session in activeBridges.values.toList()) session.dispose()
        activeBridges.clear()
        relay.dispose()
        _state.value = ZemoteClientState.CLOSED
    }
}
