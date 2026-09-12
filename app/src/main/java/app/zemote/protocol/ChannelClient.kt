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
 * 移动 relay 上的 rpc-frame 携带的是裸 value-list（不带 IPC framing 头）——
 * 已由 .proto_exp.py 裸 socket 实验证实：带 13 字节头的消息会被桌面端静默丢弃。
 *
 * Request: encodeValue([reqType, reqId, channelName, name], args) →
 *   sendBody() → RpcFrameTransport.sendMessage() → rpc-frame
 *
 * Response: rpc-frames → RpcFrameTransport reassemble → handleMessage 解码
 *   value-list → 按 reqId 匹配 completer。
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
        ZCODE_AGENT("zcode-agent"), ZCODE_SESSION("zcode-session"), ZCODE_TASK("zcode-task"),
    }

    private var lastRequestId = 0
    private val promiseHandlers = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, Any?>>>()
    private val eventHandlers = ConcurrentHashMap<Int, (Any?) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Called by [RpcFrameTransport.onMessage] with the assembled RAW value-list
     * (no framing header). Decodes the header to extract type, request id, and
     * payload data.
     */
    internal fun handleMessage(frame: ByteArray) {
        try {
            val reader = ValueReader(frame)
            val header = decodeValue(reader) as? List<Any?> ?: return
            if (header.isEmpty() || header[0] !is Number) return
            val type = (header[0] as Number).toInt()
            if (type == RES_INITIALIZE) {
                // 桌面端在收到首条消息后发来 Initialize([200]+null)，无需应答
                onLog?.invoke("[ipc] host initialize")
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
                    // EventFire: data 是 [eventFrame] 列表
                    onLog?.invoke("[ipc] EVENT_FIRE id=$id data=${data?.toString()?.let { if (it.length > 500) it.substring(0, 500) + "..." else it }}")
                    eventHandlers[id]?.invoke(data)
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("[ipc] invalid frame: $e")
        }
    }

    /**
     * Sends a request over [channel].[method] with [args] and returns the result.
     * Encodes the raw value-list and sends via [sendBody]（rpc-frame 化在
     * transport 内完成）。不等待握手：桌面端的 Initialize 是收到首条消息后才
     * 下发的，先等后发会死锁。
     */
    suspend fun call(
        channel: Channel,
        method: String,
        args: List<Any?> = emptyList(),
        timeoutMs: Long = 30_000L,
    ): Any? {
        val id = lastRequestId++
        val completer = CompletableDeferred<Pair<Int, Any?>>()
        promiseHandlers[id] = completer
        onLog?.invoke("[ipc] call ${channel.channelName}.$method id=$id")
        val writer = ValueWriter()
        encodeValue(writer, listOf(REQ_PROMISE, id, channel.channelName, method))
        encodeValue(writer, args)
        sendBody(writer.toByteArray())
        onLog?.invoke("[ipc] awaiting id=$id (timeout=${timeoutMs}ms)")
        val (resType, data) = try {
            kotlinx.coroutines.withTimeout(timeoutMs) { completer.await() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 协程被取消（页面离开等）时必须原样上抛，不能吞成超时
            promiseHandlers.remove(id)
            throw e
        } catch (e: Exception) {
            promiseHandlers.remove(id)
            onLog?.invoke("[ipc] ${channel.channelName}.$method failed (id=$id): ${e.message}")
            throw TimeoutException("${channel.channelName}.$method timed out")
        }
        onLog?.invoke("[ipc] resolved id=$id type=$resType")
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
            sendBody(writer.toByteArray())
        }
        send()
        return {
            eventHandlers.remove(id)
            if (sent) {
                val writer = ValueWriter()
                encodeValue(writer, listOf(REQ_EVENT_DISPOSE, id, channel.channelName, event))
                encodeValue(writer, null)
                sendBody(writer.toByteArray())
            }
        }
    }

    fun dispose() {
        scope.cancel()
        promiseHandlers.clear()
        eventHandlers.clear()
    }
}

class ChannelRpcError(message: String, val data: Any? = null) : Exception(message)
