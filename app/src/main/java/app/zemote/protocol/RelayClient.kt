package app.zemote.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class RelayState {
    IDLE, CONNECTING, AUTHENTICATING, WAITING, PAIRED, RECONNECTING, ERROR, KICKED, CLOSED
}

class RelayFailure(val reason: String, override val message: String? = null) : Exception("$reason${message?.let { ": $it" } ?: ""}")

class RelayClient(
    private val params: ZemoteConnectionParams,
    private val onLog: ((String) -> Unit)? = null,
) {
    companion object {
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val HEARTBEAT_ACK_TIMEOUT_MS = 30_000L
        const val WAITING_TIMEOUT_MS = 30_000L
        const val RECONNECT_WAIT_TIMEOUT_MS = 20_000L
        const val DEAD_LINK_THRESHOLD_MS = 25_000L
    }

    private val _state = MutableStateFlow(RelayState.IDLE)
    val state: StateFlow<RelayState> = _state

    private val _payloads = MutableSharedFlow<Map<String, Any>>(replay = 0, extraBufferCapacity = 256)
    val payloads: Flow<Map<String, Any>> = _payloads

    private val _failures = MutableSharedFlow<RelayFailure>(replay = 0, extraBufferCapacity = 8)
    val failures: Flow<RelayFailure> = _failures

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var socketGen = 0
    private var connectInFlight = false
    private var wasPaired = false
    private var intentionallyClosed = false
    private var disposed = false
    private var reconnectAttempt = 0
    private var heartbeatTick = 0
    private var staleProbeSent = false
    private var kickRecoveryAttempted = 0
    private var lastInboundAt = System.currentTimeMillis()
    private var lastPairStatusAckAt = System.currentTimeMillis()

    private val outboundQueue = mutableListOf<Map<String, Any>>()

    private var heartbeatJob: Job? = null
    private var waitingJob: Job? = null
    private var reconnectJob: Job? = null
    private var rewaitJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inboundQueue = Channel<Map<String, Any>>(Channel.UNLIMITED)
    private val inboundDispatcher: Job = scope.launch {
        for (payload in inboundQueue) {
            _payloads.emit(payload)
        }
    }

    fun send(payload: Map<String, Any>) {
        if (_state.value != RelayState.PAIRED || webSocket == null) {
            if (outboundQueue.size < 100) {
                onLog?.invoke("[relay] queued (${_state.value}): ${payload["zcode_type"]}")
                outboundQueue.add(payload)
            }
            return
        }
        sendFrame(mapOf(
            "type" to "data",
            "payload" to payload,
            "client_ts" to System.currentTimeMillis()
        ))
    }

    fun poke() {
        if (disposed || intentionallyClosed) return
        if (_state.value == RelayState.PAIRED) {
            if (System.currentTimeMillis() - lastInboundAt > DEAD_LINK_THRESHOLD_MS) {
                onLog?.invoke("[relay] poke: stale link, reconnecting")
                scheduleReconnect()
                return
            }
            sendFrame(mapOf(
                "type" to "pair_status_query",
                "device_sid" to params.deviceSid,
                "client_ts" to System.currentTimeMillis()
            ))
        } else if (_state.value !in listOf(RelayState.IDLE, RelayState.CLOSED, RelayState.KICKED)) {
            onLog?.invoke("[relay] poke: state=${_state.value}, forcing reconnect")
            reconnectJob?.cancel()
            reconnectJob = null
            scope.launch { connect() }
        }
    }

    suspend fun start() {
        disposed = false
        intentionallyClosed = false
        reconnectAttempt = 0
        kickRecoveryAttempted = 0
        _state.value = RelayState.CONNECTING
        connect()
    }

    private suspend fun connect() {
        if (connectInFlight || disposed) return
        connectInFlight = true
        val gen = ++socketGen
        webSocket?.cancel()
        webSocket = null
        lastPairStatusAckAt = System.currentTimeMillis()
        lastInboundAt = System.currentTimeMillis()
        val uri = params.relayWsUri
        onLog?.invoke("[relay] connecting $uri")
        val request = Request.Builder().url(uri).build()
        try {
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (socketGen != gen || disposed) return
                    connectInFlight = false
                    _state.value = RelayState.AUTHENTICATING
                    sendAuthInit()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socketGen != gen || disposed) return
                    lastInboundAt = System.currentTimeMillis()
                    handleRawMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (disposed) return
                    stopHeartbeat()
                    clearWaitingTimer()
                    val mapped = relayCloseReason(code)
                    onLog?.invoke("[relay] closed code=$code reason=$reason mapped=$mapped")
                    if (intentionallyClosed) return
                    if (wasPaired || mapped == "desktop-disconnected") {
                        scheduleReconnect()
                    } else {
                        _state.value = RelayState.ERROR
                        scope.launch { _failures.emit(RelayFailure(mapped ?: "relay-unavailable", reason)) }
                    }
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    if (socketGen != gen || disposed) return
                    connectInFlight = false
                    onLog?.invoke("[relay] connect failed: ${error.message}")
                    if (gen == socketGen) {
                        _state.value = RelayState.ERROR
                        scope.launch { _failures.emit(RelayFailure("connect-failed", error.message)) }
                    }
                }
            })
        } catch (e: Exception) {
            connectInFlight = false
            onLog?.invoke("[relay] connect exception: ${e.message}")
            if (gen == socketGen) {
                _state.value = RelayState.ERROR
                scope.launch { _failures.emit(RelayFailure("connect-exception", e.message)) }
            }
        }
    }

    private fun sendAuthInit() {
        sendFrame(mapOf(
            "type" to "auth_init",
            "role" to "terminal",
            "device_sid" to params.deviceSid,
            "meta" to mapOf(
                "platform" to "android",
                "version" to (params.appVersion ?: "web"),
                "name" to "Zemote"
            ),
            "client_ts" to System.currentTimeMillis()
        ))
    }

    private fun sendFrame(frame: Map<String, Any>) {
        val ws = webSocket
        if (ws == null) return
        val json = gson.toJson(frame)
        ws.send(json)
    }

    private fun handleRawMessage(text: String) {
        val decoded = try { gson.fromJson(text, com.google.gson.JsonElement::class.java) } catch (e: Exception) {
            onLog?.invoke("[relay] bad frame: $e")
            return
        }
        if (!decoded.isJsonObject) return
        val obj = decoded.asJsonObject
        val type = obj.get("type")?.asString ?: return
        when (type) {
            "auth_challenge" -> {
                val nonce = obj.get("nonce")?.asString ?: ""
                val proof = Proof.calculate(params.passHash, nonce, "terminal", params.deviceSid)
                sendFrame(mapOf(
                    "type" to "auth_response",
                    "device_sid" to params.deviceSid,
                    "proof" to proof,
                    "client_ts" to System.currentTimeMillis()
                ))
            }
            "auth_ack", "pair_status_ack" -> {
                val status = obj.get("pair_status")?.asString
                lastPairStatusAckAt = System.currentTimeMillis()
                staleProbeSent = false
                applyPairStatus(status)
            }
            "data" -> {
                val payload = obj.get("payload")
                if (payload?.isJsonObject == true) {
                    val map = gson.fromJson(payload, Map::class.java) as? Map<String, Any>
                    if (map != null) inboundQueue.trySend(map)
                }
            }
            "error" -> handleRelayError(obj.get("code")?.asString, obj.get("message")?.asString)
        }
    }

    private fun applyPairStatus(status: String?) {
        lastPairStatusAckAt = System.currentTimeMillis()
        staleProbeSent = false
        when (status) {
            "waiting" -> {
                if (wasPaired) {
                    clearWaitingTimer()
                    _state.value = RelayState.WAITING
                    startHeartbeat()
                    rewaitJob?.cancel()
                    rewaitJob = scope.launch {
                        delay(RECONNECT_WAIT_TIMEOUT_MS)
                        if (wasPaired && _state.value == RelayState.WAITING && !disposed) {
                            onLog?.invoke("[relay] re-pair stuck in waiting, reconnecting")
                            reconnect()
                        }
                    }
                } else {
                    _state.value = RelayState.WAITING
                    startWaitingTimer()
                }
            }
            "matched" -> {
                rewaitJob?.cancel()
                reconnectAttempt = 0
                kickRecoveryAttempted = 0
                clearWaitingTimer()
                _state.value = RelayState.PAIRED
                wasPaired = true
                startHeartbeat()
                flushOutboundQueue()
            }
        }
    }

    private fun handleRelayError(code: String?, message: String?) {
        onLog?.invoke("[relay] error: $code $message")
        if (code == "KICKED") {
            if (disposed) return
            kickRecoveryAttempted++
            scope.launch { _failures.emit(RelayFailure("session-conflict", message)) }
            webSocket?.close(1000, "kicked")
            _state.value = RelayState.RECONNECTING
            val delayMs = (1000L shl (kickRecoveryAttempted - 1).coerceIn(0, 3)).coerceAtMost(8000L)
            onLog?.invoke("[relay] kicked by another client, reclaiming in ${delayMs}ms (attempt $kickRecoveryAttempted)")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                reconnect()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_state.value !in listOf(RelayState.PAIRED, RelayState.WAITING)) continue
                heartbeatTick++
                if (_state.value == RelayState.WAITING && heartbeatTick % 2 == 1) continue
                if (System.currentTimeMillis() - lastPairStatusAckAt > HEARTBEAT_ACK_TIMEOUT_MS) {
                    if (!staleProbeSent) {
                        staleProbeSent = true
                        onLog?.invoke("[relay] heartbeat stale, probing")
                    } else {
                        staleProbeSent = false
                        onLog?.invoke("[relay] heartbeat ack timeout, reconnecting")
                        reconnect()
                        return@launch
                    }
                } else {
                    staleProbeSent = false
                }
                sendFrame(mapOf(
                    "type" to "pair_status_query",
                    "device_sid" to params.deviceSid,
                    "client_ts" to System.currentTimeMillis()
                ))
            }
        }
    }

    private fun stopHeartbeat() { heartbeatJob?.cancel() }

    private fun startWaitingTimer() {
        clearWaitingTimer()
        waitingJob = scope.launch {
            delay(WAITING_TIMEOUT_MS)
            if (_state.value == RelayState.WAITING && !wasPaired) {
                _state.value = RelayState.ERROR
                scope.launch {
                    _failures.emit(RelayFailure(
                        "invalid-mobile-connection",
                        "Desktop did not match this mobile connection before the waiting timeout."
                    ))
                }
            }
        }
    }

    private fun clearWaitingTimer() {
        waitingJob?.cancel()
        rewaitJob?.cancel()
    }

    private fun flushOutboundQueue() {
        if (outboundQueue.isEmpty()) return
        onLog?.invoke("[relay] flushing ${outboundQueue.size} queued payload(s)")
        val queued = outboundQueue.toList().also { outboundQueue.clear() }
        for (payload in queued) {
            sendFrame(mapOf(
                "type" to "data",
                "payload" to payload,
                "client_ts" to System.currentTimeMillis()
            ))
        }
    }

    private fun scheduleReconnect() {
        if (disposed || intentionallyClosed) return
        _state.value = RelayState.RECONNECTING
        val delayMs = ((1000L shl reconnectAttempt.coerceIn(0, 4))).coerceIn(1000L, 15000L)
        reconnectAttempt++
        onLog?.invoke("[relay] reconnect in ${delayMs}ms (attempt $reconnectAttempt)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!disposed) connect()
        }
    }

    private fun reconnect() {
        if (disposed || intentionallyClosed || connectInFlight) return
        reconnectJob?.cancel()
        _state.value = RelayState.RECONNECTING
        scope.launch { connect() }
    }

    suspend fun dispose() {
        disposed = true
        intentionallyClosed = true
        stopHeartbeat()
        clearWaitingTimer()
        reconnectJob?.cancel()
        inboundQueue.close()
        inboundDispatcher.cancel()
        webSocket?.close(1000, "disposed")
        _state.value = RelayState.CLOSED
    }
}

fun relayCloseReason(code: Int): String? = when (code) {
    4004 -> "session-not-found"
    4009 -> "session-conflict"
    4010 -> "desktop-disconnected"
    4011 -> "session-expired"
    4012 -> "workspace-closed"
    4013 -> "invalid-mobile-connection"
    else -> null
}
