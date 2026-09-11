package app.zemote.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/**
 * Channel RPC client mirroring the web client's Pne.
 *
 * Request: encodeValue([reqType, reqId, channelName, name], args) +
 *   IpcFraming.encode() → sendBody() → RpcFrameTransport.sendMessage() → rpc-frames
 *
 * Response: rpc-frames → RpcFrameTransport.assemble → IpcFraming.decode(header) +
 *   decodeValue(data) → match by reqId → complete completer
 */
class ChannelClient(
    private val sendBody: (ByteArray) -> Unit,
    private val onLog: ((String) -> Unit)? = null,
) {
    companion object {
        const val REQ_PROMISE = 100
        const val REQ_PROMISE_CANCEL = 101
        const val REQ_EVENT_LISTEN = 102
        const val REQ_EVENT_DISPOSE = 103
        const val RES_INITIALIZE = 200
        const val RES_PROMISE_SUCCESS = 201
        const val RES_PROMISE_ERROR = 202
        const val RES_PROMISE_ERROR_OBJ = 203
        const val RES_EVENT_FIRE = 204
    }

    enum class Channel(val channelName: String) {
        FILE("file"), SYSTEM("system"), TERMINAL("terminal"), GIT("git"),
        GIT_CHECKPOINT("git-checkpoint"), SETTING("setting"), CREDENTIAL("credential"),
        ZCODE_AGENT("zcode-agent"),
    }

    private var lastRequestId = 0
    private val ready = CompletableDeferred<Unit>()
    private val promiseHandlers = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, Any?>>>()
    private val eventHandlers = ConcurrentHashMap<Int, (Any?) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun awaitReady(timeoutMs: Long = 30_000L) {
        try { kotlinx.coroutines.withTimeout(timeoutMs) { ready.await() } }
        catch (e: Exception) { throw TimeoutException("channel init timeout ($timeoutMs ms)") }
    }

    /**
     * Called by [RpcFrameTransport.onMessage] with the FULL assembled IPC frame
     * (13-byte framing header + encoded value-list). Decodes the header to
     * extract type, request id, and payload data.
     */
    internal fun handleMessage(frame: ByteArray) {
        try {
            val reader = ValueReader(frame)
            val header = decodeValue(reader) as? List<Any?> ?: return
            if (header.isEmpty() || header[0] !is Number) return
            val type = (header[0] as Number).toInt()
            if (type == RES_INITIALIZE) {
                onLog?.invoke("[ipc] initialized")
                if (!ready.isCompleted) ready.complete(Unit)
                return
            }
            if (header.size < 2 || header[1] !is Number) return
            val id = (header[1] as Number).toInt()
            val data = decodeValue(reader)
            when (type) {
                RES_PROMISE_SUCCESS, RES_PROMISE_ERROR, RES_PROMISE_ERROR_OBJ -> {
                    promiseHandlers.remove(id)?.complete(Pair(type, data))
                }
                RES_EVENT_FIRE -> {
                    eventHandlers[id]?.invoke(data)
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("[ipc] invalid frame: $e")
        }
    }

    /**
     * Sends a request over [channel].[method] with [args] and returns the result.
     * Encodes the value-list, prepends the 13-byte IPC framing header, then
     * sends via [sendBody] (which wraps it in rpc-frames).
     */
    suspend fun call(
        channel: Channel,
        method: String,
        args: List<Any?> = emptyList(),
        timeoutMs: Long = 30_000L,
    ): Any? {
        awaitReady(timeoutMs)
        val id = lastRequestId++
        val completer = CompletableDeferred<Pair<Int, Any?>>()
        promiseHandlers[id] = completer
        onLog?.invoke("[ipc] call ${channel.channelName}.$method id=$id")
        val writer = ValueWriter()
        encodeValue(writer, listOf(REQ_PROMISE, id, channel.channelName, method))
        encodeValue(writer, args)
        // Prepend 13-byte IPC framing header so the assembled message is complete
        sendBody(IpcFraming.encode(writer.toByteArray()))
        val (resType, data) = try {
            completer.await()
        } catch (e: Exception) {
            throw TimeoutException("${channel.channelName}.$method timed out")
        }
        return when (resType) {
            RES_PROMISE_SUCCESS -> data
            RES_PROMISE_ERROR, RES_PROMISE_ERROR_OBJ -> throw ChannelRpcError(data?.toString() ?: "unknown error", data)
            else -> data
        }
    }

    /**
     * Registers an event listener for [event] on [channel].
     * Returns a cancel function that unsubscribes.
     */
    fun addEventListener(
        channel: Channel,
        event: String,
        onEvent: (Any?) -> Unit,
        arg: Any? = null,
    ): () -> Unit {
        val id = lastRequestId++
        var sent = false
        eventHandlers[id] = onEvent
        fun send() {
            if (sent) return
            sent = true
            onLog?.invoke("[ipc] listen ${channel.channelName}.$event id=$id")
            val writer = ValueWriter()
            encodeValue(writer, listOf(REQ_EVENT_LISTEN, id, channel.channelName, event))
            encodeValue(writer, arg)
            sendBody(IpcFraming.encode(writer.toByteArray()))
        }
        scope.launch {
            try { awaitReady() } catch (_: Exception) {}
            send()
        }
        return {
            eventHandlers.remove(id)
            if (sent) {
                val writer = ValueWriter()
                encodeValue(writer, listOf(REQ_EVENT_DISPOSE, id, channel.channelName, event))
                encodeValue(writer, null)
                sendBody(IpcFraming.encode(writer.toByteArray()))
            }
        }
    }

    fun dispose() {
        scope.cancel()
        promiseHandlers.clear()
        eventHandlers.clear()
        if (!ready.isCompleted) ready.completeExceptionally(Throwable("disposed"))
    }
}

class ChannelRpcError(message: String, val data: Any? = null) : Exception(message)
