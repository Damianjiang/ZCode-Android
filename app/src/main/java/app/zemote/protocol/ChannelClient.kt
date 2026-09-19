package app.zemote.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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

    private val requestIdSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun nextRequestId(): Int = requestIdSeq.getAndIncrement()

    @JvmField
    var initialized = false

    fun resetReady() {
        initialized = false
    }

    private val promiseHandlers = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, Any?>>>()
    private val eventHandlers = ConcurrentHashMap<Int, (Any?) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal fun handleMessage(frame: ByteArray) {
        try {
            val reader = ValueReader(frame)
            val header = decodeValue(reader) as? List<Any?> ?: return
            if (header.isEmpty() || header[0] !is Number) return
            val type = (header[0] as Number).toInt()
            if (type == RES_INITIALIZE) {
                onLog?.invoke("[ipc] initialized")
                initialized = true
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

    private suspend fun awaitReady(
        timeoutMs: Long = 30_000L,
        isActiveCheck: () -> Boolean = { scope.isActive },
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!initialized) {
            if (!isActiveCheck()) return
            if (!scope.isActive) return
            if (System.currentTimeMillis() >= deadline) {
                throw TimeoutException("channel init timeout (no Initialize frame from desktop)")
            }
            kotlinx.coroutines.delay(50)
        }
    }

    suspend fun call(
        channel: Channel,
        method: String,
        args: List<Any?> = emptyList(),
        timeoutMs: Long = 30_000L,
        isActiveCheck: () -> Boolean = { scope.isActive },
    ): Any? {
        awaitReady(30_000L, isActiveCheck)
        if (!isActiveCheck()) return null
        val id = nextRequestId()
        val completer = CompletableDeferred<Pair<Int, Any?>>()
        promiseHandlers[id] = completer
        val writer = ValueWriter()
        encodeValue(writer, listOf(REQ_PROMISE, id, channel.channelName, method))
        encodeValue(writer, args)
        sendBody(writer.toByteArray())
        if (ZemoteLogger.enabled) ZemoteLogger.debug("ipc", "→ ${channel.channelName}.$method id=$id")
        if (!isActiveCheck()) {
            promiseHandlers.remove(id)
            return null
        }
        val (resType, data) = try {
            kotlinx.coroutines.withTimeout(timeoutMs) { completer.await() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            promiseHandlers.remove(id)
            throw e
        } catch (e: Exception) {
            promiseHandlers.remove(id)
            if (e.message?.contains("composition") == true) {
                return null
            }
            onLog?.invoke("[ipc] ${channel.channelName}.$method failed (id=$id): ${e.message}")
            throw TimeoutException("${channel.channelName}.$method timed out")
        }
        if (ZemoteLogger.enabled) ZemoteLogger.debug("ipc", "← ${channel.channelName}.$method id=$id type=$resType")
        return when (resType) {
            RES_PROMISE_SUCCESS -> data
            RES_PROMISE_ERROR, RES_PROMISE_ERROR_OBJ -> throw ChannelRpcError(data?.toString() ?: "unknown error", data)
            else -> data
        }
    }

    fun addEventListener(
        channel: Channel,
        event: String,
        onEvent: (Any?) -> Unit,
        arg: Any? = null,
    ): () -> Unit {
        val id = nextRequestId()
        val sent = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        eventHandlers[id] = onEvent
        scope.launch {
            try {
                awaitReady(30_000L)
            } catch (_: Exception) {
                eventHandlers.remove(id)
                return@launch
            }
            if (disposed.get()) {
                eventHandlers.remove(id)
                return@launch
            }
            sent.set(true)
            onLog?.invoke("[ipc] listen ${channel.channelName}.$event id=$id")
            val writer = ValueWriter()
            encodeValue(writer, listOf(REQ_EVENT_LISTEN, id, channel.channelName, event))
            encodeValue(writer, arg)
            sendBody(writer.toByteArray())
        }
        return {
            if (disposed.compareAndSet(false, true)) {
                eventHandlers.remove(id)
                if (sent.get()) {
                    val writer = ValueWriter()
                    encodeValue(writer, listOf(REQ_EVENT_DISPOSE, id, channel.channelName, event))
                    encodeValue(writer, null)
                    sendBody(writer.toByteArray())
                }
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
