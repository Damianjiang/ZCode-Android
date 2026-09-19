package app.zemote.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer

/** A workspace bridge session. Holds the rpc-frame transport and IPC channels. */
class BridgeSession(
    var bridge: Map<String, Any>,
    private val relayClient: RelayClient,
    private val onDispose: (BridgeSession) -> Unit,
    private val onLog: ((String) -> Unit)?,
) {
    val degraded = MutableStateFlow<String?>(null)

    private var _disposed = false
    private var _transport: RpcFrameTransport
    private var _channels: ChannelClient
    private var relayListenerScope: CoroutineScope? = null
    private var staleTimer: Job? = null
    private var lastMessageAt = System.currentTimeMillis()

    val workspaceKey: String? get() = bridge["workspaceKey"] as? String
    val initialTaskId: String? get() = bridge["initialTaskId"] as? String
    val bridgeSessionId: String get() = bridge["bridgeSessionId"] as? String ?: ""

    init {
        _transport = buildTransport(relayClient)
        _channels = buildChannels(_transport)
        _transport.onMessage = { frame ->
            lastMessageAt = System.currentTimeMillis()
            _channels.handleMessage(frame)
        }
        startRelayListener()
        startStaleTimer()
    }

    var isRecovering = false
    val isDisposed get() = _disposed

    companion object {
        const val STALE_RECOVERY_TIMEOUT_MS = 45_000L
    }

    private fun startStaleTimer() {
        staleTimer?.cancel()
        staleTimer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(STALE_RECOVERY_TIMEOUT_MS)
            if (_disposed) return@launch
            val elapsed = System.currentTimeMillis() - lastMessageAt
            if (elapsed >= STALE_RECOVERY_TIMEOUT_MS && degraded.value == null) {
                onLog?.invoke("[bridge] stale timer fired: ${elapsed}ms")
                degraded.value = "stale"
                onDispose(this@BridgeSession)
            }
        }
    }

    private fun startRelayListener() {
        relayListenerScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        relayListenerScope = scope
        scope.launch {
            relayClient.payloads
                .buffer(Channel.UNLIMITED)
                .collect { payload ->
                    val type = payload["zcode_type"] as? String
                    val bsid = payload["bridgeSessionId"] as? String
                    if (type in listOf("rpc-frame", "rpc-frame-ack") && bsid == bridgeSessionId) {
                        _transport.acceptPayload(payload)
                    }
                }
        }
    }

    private fun buildTransport(relay: RelayClient): RpcFrameTransport = RpcFrameTransport(
        bridgeSessionId = bridgeSessionId,
        bridgeGeneration = (bridge["bridgeGeneration"] as? Number)?.toInt(),
        recoveryId = bridge["recoveryId"] as? String,
        sendPayload = { relay.send(it) },
        onLog = onLog,
    )

    private fun buildChannels(transport: RpcFrameTransport): ChannelClient = ChannelClient(
        sendBody = { transport.sendMessage(it) },
        onLog = onLog,
    )

    internal fun swapBridge(newBridge: Map<String, Any>, newRelay: RelayClient) {
        bridge = newBridge
        _transport.dispose()
        _channels.dispose()
        relayListenerScope?.cancel()
        relayListenerScope = null
        staleTimer?.cancel()
        staleTimer = null
        _transport = buildTransport(newRelay)
        _channels = buildChannels(_transport)
        _channels.resetReady()
        _transport.onMessage = { frame ->
            lastMessageAt = System.currentTimeMillis()
            _channels.handleMessage(frame)
        }
        startRelayListener()
        startStaleTimer()
        lastMessageAt = System.currentTimeMillis()
    }

    fun dispose() {
        if (_disposed) return
        _disposed = true
        degraded.value = null
        staleTimer?.cancel()
        staleTimer = null
        relayListenerScope?.cancel()
        relayListenerScope = null
        _transport.dispose()
        _channels.dispose()
        onDispose(this)
    }
}
