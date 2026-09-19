package app.zemote.ui.logger

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内调试日志收集器。
 *
 * 用途：集中记录协议层（Channel / Relay / ConversationV4）的通信日志，
 * 以及用户操作行为，方便一键复制反馈。
 *
 * 线程安全，最多保留 [MAX_ENTRIES] 条；超出时丢弃最旧的。
 * 可通过 [enabled] 开关整体关闭（默认开启）。
 */
object ZemoteLogger {

    enum class Level(val symbol: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    data class LogEntry(
        val id: Int,
        val level: Level,
        val tag: String,
        val message: String,
        val timestamp: String,
    ) {
        val formatted get() = "[$timestamp] [$level] $tag: $message"
    }

    private const val MAX_ENTRIES = 1000

    /**
     * 单条日志的最大字符数。
     *
     * 环形缓冲要保留 1000 条，不设上限时**一条**几 MB 的 payload dump
     * （例如 `bootstrap` 的整份响应 toString）就会吃掉几十 MB 内存，
     * 并且把有用的日志全部挤出缓冲。这里统一截断，保护所有调用点。
     */
    private const val MAX_MESSAGE_CHARS = 4_000

    /** 日志发布节流间隔：避免每条日志都做一次 1000 元素列表快照 + Compose 状态写入 */
    private const val PUBLISH_INTERVAL_MS = 300L

    private val _entries = mutableStateOf<List<LogEntry>>(emptyList())
    private val _entriesFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: State<List<LogEntry>> get() = _entries
    val entriesFlow: StateFlow<List<LogEntry>> get() = _entriesFlow.asStateFlow()

    /** 总开关：设为 false 后所有写入被静默丢弃，用于设置页控制 */
    private val _enabled = AtomicBoolean(true)
    var enabled: Boolean
        get() = _enabled.get()
        set(value) = _enabled.set(value)

    private val _nextId = java.util.concurrent.atomic.AtomicInteger(0)
    private val _lock = Any()
    private val _queue = ArrayDeque<LogEntry>()
    private val _publishScheduled = AtomicBoolean(false)
    private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 线程安全的时间格式化。
     * 旧实现用的 `SimpleDateFormat` 不是线程安全的，而日志会从多个 IO 线程写入，
     * 可能格式化出错乱时间甚至抛异常。
     */
    private val _fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String) = log(Level.WARN, tag, message)
    fun error(tag: String, message: String) = log(Level.ERROR, tag, message)

    /** 记录用户操作行为（如打开会话、发送消息、切换到子智能体） */
    fun action(message: String) = log(Level.INFO, "action", message)

    private fun log(level: Level, tag: String, message: String) {
        if (!_enabled.get()) return
        val trimmed = message.trim()
        val text = if (trimmed.length > MAX_MESSAGE_CHARS) {
            trimmed.take(MAX_MESSAGE_CHARS) + "…[已截断，原长 ${trimmed.length} 字符]"
        } else {
            trimmed
        }
        val entry = LogEntry(
            id = _nextId.getAndIncrement(),
            level = level,
            tag = tag,
            message = text,
            timestamp = _fmt.format(java.time.LocalTime.now()),
        )
        synchronized(_lock) {
            _queue.addLast(entry)
            while (_queue.size > MAX_ENTRIES) _queue.removeFirst()
        }
        schedulePublish()
    }

    /**
     * 节流发布：高频日志（协议帧、流式输出）下，每条都重建列表既浪费 CPU
     * 又会触发大量 Compose 状态写入。这里合并到约 3 次/秒。
     */
    private fun schedulePublish() {
        if (!_publishScheduled.compareAndSet(false, true)) return
        _scope.launch {
            delay(PUBLISH_INTERVAL_MS)
            _publishScheduled.set(false)
            publish()
        }
    }

    private fun publish() {
        val snapshot = synchronized(_lock) { _queue.toList().asReversed() }
        _entries.value = snapshot
        _entriesFlow.value = snapshot
    }

    fun clear() {
        synchronized(_lock) { _queue.clear() }
        publish()
    }
}
