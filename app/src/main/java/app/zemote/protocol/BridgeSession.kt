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
    val recovered = MutableStateFlow(0)

    private var _disposed = false
    private var _transport: RpcFrameTransport
    private var _channels: ChannelClient
    /** CoroutineScope for the relay-payloads listener; restarted on swapBridge. */
    private var relayListenerScope: CoroutineScope? = null
    /** Timer that triggers forced bridge recovery if no frames arrive within the deadline. */
    private var staleTimer: Job? = null
    /** Last time a complete assembled message was received. */
    private var lastMessageAt = System.currentTimeMillis()

    val workspaceKey: String? get() = bridge["workspaceKey"] as? String
    val initialTaskId: String? get() = bridge["initialTaskId"] as? String
    val bridgeSessionId: String get() = bridge["bridgeSessionId"] as? String ?: ""

    init {
        _transport = buildTransport(relayClient)
        _channels = buildChannels(_transport)
        // Wire assembled IPC bodies → channel client
        _transport.onMessage = { frame ->
            lastMessageAt = System.currentTimeMillis()
            _channels.handleMessage(frame)
        }
        // Listen for relay payloads and route rpc-frame(-ack) to the transport
        startRelayListener()
        startStaleTimer()
    }

    var isRecovering = false
    val isDisposed get() = _disposed

    companion object {
        /** Trigger stale recovery after 45s of no received frames. Mirrors web client's replayBufferGraceMs. */
        const val STALE_RECOVERY_TIMEOUT_MS = 45_000L
    }

    private fun startStaleTimer() {
        staleTimer?.cancel()
        staleTimer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(STALE_RECOVERY_TIMEOUT_MS)
            if (_disposed) return@launch
            val elapsed = System.currentTimeMillis() - lastMessageAt
            if (elapsed >= STALE_RECOVERY_TIMEOUT_MS && degraded.value == null) {
                onLog?.invoke("[bridge] stale timer fired: ms since last frame, triggering recovery")
                degraded.value = "stale-no-frames-ms"
                // Notify parent to recover
                onDispose(this@BridgeSession)
            }
        }
    }

    private fun startRelayListener() {
        // Cancel any existing listener before starting a new one
        relayListenerScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        relayListenerScope = scope
        scope.launch {
            relayClient.payloads
                // ── 关键：把「重活」与 relay 的 SharedFlow 解耦 ──
                // 下面 collect 体里的 acceptPayload 会在**收集协程内同步**做：
                //   Base64 解码（单帧最大 512KB → 683KB 字符串）、CRC32 校验、
                //   整条 IPC 消息的 value-list 解码、以及会话帧的状态合并。
                // 一条多兆的历史响应能让这个协程几十到几百毫秒回不到 collect，
                // 而 relay 的 _payloads 是 extraBufferCapacity=256、默认 SUSPEND 的
                // SharedFlow —— 慢订阅者会把整个缓冲区拖满，emit 随即挂起，
                // **整条 relay 入站管线停摆**，后续所有帧（含新的会话帧）只能排队。
                // 这就是「通过 bridge 获取信息很慢」的主因。
                // buffer(UNLIMITED) 插入一个只搬运引用的中转通道：SharedFlow 的 emit
                // 立刻返回，慢解码不再反压到 relay。
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
        staleTimer?.cancel()
        staleTimer = null
        _transport = buildTransport(newRelay)
        _channels = buildChannels(_transport)
        // 重置 ready：新 bridge 的 Initialize 帧未到达，需重新等待
        _channels.resetReady()
        // Re-wire: assembled IPC bodies → new channel client
        _transport.onMessage = { frame ->
            lastMessageAt = System.currentTimeMillis()
            _channels.handleMessage(frame)
        }
        // Start fresh listener for the new bridge session
        startRelayListener()
        startStaleTimer()
        // Reset stale tracking for the new bridge
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
