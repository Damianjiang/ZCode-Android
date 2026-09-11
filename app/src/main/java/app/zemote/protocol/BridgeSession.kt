package app.zemote.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A workspace bridge session. Holds the rpc-frame transport and IPC channels. */
class BridgeSession(
    var bridge: Map<String, Any>,
    private val relayClient: RelayClient,
    private val onDispose: (BridgeSession) -> Unit,
    private val onLog: ((String) -> Unit)?,
) {
    val degraded = MutableStateFlow<String?>(null)
    val recovered = MutableStateFlow(0)

    private var _disposed = false
    private var _transport: RpcFrameTransport
    private var _channels: ChannelClient
    /** CoroutineScope for the relay-payloads listener; restarted on swapBridge. */
    private var relayListenerScope: CoroutineScope? = null

    val workspaceKey: String? get() = bridge["workspaceKey"] as? String
    val initialTaskId: String? get() = bridge["initialTaskId"] as? String
    val bridgeSessionId: String get() = bridge["bridgeSessionId"] as? String ?: ""

    init {
        _transport = buildTransport(relayClient)
        _channels = buildChannels(_transport)
        // Wire assembled IPC bodies → channel client
        _transport.onMessage = { frame -> _channels.handleMessage(frame) }
        // Listen for relay payloads and route rpc-frame(-ack) to the transport
        startRelayListener()
    }

    var isRecovering = false
    val isDisposed get() = _disposed

    private fun startRelayListener() {
        // Cancel any existing listener before starting a new one
        relayListenerScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        relayListenerScope = scope
        scope.launch {
            relayClient.payloads.collect { payload ->
                val type = payload["zcode_type"] as? String
                // 临时诊断：打印所有非 rpc-frame 的载荷类型
                if (type != null && type != "rpc-frame" && type != "rpc-frame-ack") {
                    onLog?.invoke("[relay] payload type=$type keys=${payload.keys}")
                }
                val bsid = payload["bridgeSessionId"] as? String
                if (type in listOf("rpc-frame", "rpc-frame-ack")) {
                    if (bsid == bridgeSessionId) {
                        _transport.acceptPayload(payload)
                    } else {
                        // 临时诊断：bridge id 不匹配的帧（可能被丢弃的响应）
                        onLog?.invoke("[relay] DROPPED $type bsid=$bsid (mine=$bridgeSessionId) seq=${payload["messageSeq"]}/${payload["ackMessageSeq"]}")
                    }
                }
            }
        }
    }

    private fun buildTransport(relay: RelayClient): RpcFrameTransport = RpcFrameTransport(
        bridgeSessionId = bridgeSessionId,
        // bridge map 来自 JSON，数字一律是 Double，必须用 Number 安全转换
        bridgeGeneration = (bridge["bridgeGeneration"] as? Number)?.toInt(),
        recoveryId = bridge["recoveryId"] as? String,
        sendPayload = { relay.send(it) },
        onLog = onLog,
    )

    private fun buildChannels(transport: RpcFrameTransport): ChannelClient = ChannelClient(
        sendBody = { transport.sendMessage(it) },
        onLog = onLog,
    )

    /**
     * Swaps in a newly-opened bridge (used after reopen during recovery).
     * Rebuilds the transport + channel stack and restarts the relay listener
     * so it targets the new bridgeSessionId.
     */
    internal fun swapBridge(newBridge: Map<String, Any>, newRelay: RelayClient) {
        bridge = newBridge
        _transport.dispose()
        _channels.dispose()
        relayListenerScope?.cancel()
        relayListenerScope = null
        _transport = buildTransport(newRelay)
        _channels = buildChannels(_transport)
        // Re-wire: assembled IPC bodies → new channel client
        _transport.onMessage = { frame -> _channels.handleMessage(frame) }
        // Start fresh listener for the new bridge session
        startRelayListener()
    }

    val channelsClient: ChannelClient get() = _channels

    fun dispose() {
        if (_disposed) return
        _disposed = true
        degraded.value = null
        relayListenerScope?.cancel()
        relayListenerScope = null
        _transport.dispose()
        _channels.dispose()
        onDispose(this)
    }
}

/** Conversation V4 protocol transport over the zcode-agent channel. */
class ConversationTransport(
    private val session: BridgeSession,
    private val scopeParams: Map<String, Any>,
    private val onLog: ((String) -> Unit)?,
) {
    companion object {
        const val PROTOCOL_APP_VERSION = "3.6.5"
    }

    private val clientId = generateUuid()
    private var handshaken = false
    private val appVersion = PROTOCOL_APP_VERSION

    var connectionId: String? = null

    private val scopeKey: String
        get() = scopeParams["workspaceIdentity"] as? String ?: scopeParams["workspacePath"] as? String ?: ""

    suspend fun initialize() {
        if (handshaken) return
        session.channelsClient.call(
            ChannelClient.Channel.ZCODE_AGENT,
            "helloConversationV4",
            listOf(scopeParams, clientId, appVersion),
        )
        handshaken = true
    }

    suspend fun subscribe(workspaceKey: String, sessionId: String? = null) {
        val subScope = buildMap<String, Any> {
            put("workspaceIdentity", workspaceKey)
            if (sessionId != null) put("sessionId", sessionId)
        }
        session.channelsClient.call(
            ChannelClient.Channel.ZCODE_AGENT,
            "subscribeConversationV4",
            listOf(subScope),
        )
    }

    suspend fun sendCommand(envelope: Map<String, Any>) {
        session.channelsClient.call(
            ChannelClient.Channel.ZCODE_AGENT,
            "sendConversationCommandV4",
            listOf(scopeParams, envelope),
        )
    }
}
