package app.zemote.ui.logger

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
    private val _entries = mutableStateOf<List<LogEntry>>(emptyList())
    private val _entriesFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: State<List<LogEntry>> get() = _entries
    val entriesFlow: StateFlow<List<LogEntry>> get() = _entriesFlow.asStateFlow()

    /** 总开关：设为 false 后所有写入被静默丢弃，用于设置页控制 */
    private val _enabled = AtomicBoolean(true)
    var enabled: Boolean
        get() = _enabled.get()
        set(value) = _enabled.set(value)

    private var _nextId = 0
    private val _lock = Any()
    private val _queue = ConcurrentLinkedQueue<LogEntry>()
    private val _fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String) = log(Level.WARN, tag, message)
    fun error(tag: String, message: String) = log(Level.ERROR, tag, message)

    /** 记录用户操作行为（如打开会话、发送消息、切换到子智能体） */
    fun action(message: String) = log(Level.INFO, "action", message)

    private fun log(level: Level, tag: String, message: String) {
        if (!_enabled.get()) return
        val entry = LogEntry(
            id = _nextId++,
            level = level,
            tag = tag,
            message = message.trim(),
            timestamp = _fmt.format(java.util.Date()),
        )
        _queue.add(entry)
        synchronized(_lock) {
            while (_queue.size > MAX_ENTRIES) _queue.poll()
            val snapshot = _queue.toList().reversed()
            _entries.value = snapshot
            _entriesFlow.value = snapshot
        }
    }

    fun clear() {
        synchronized(_lock) { _queue.clear(); _entries.value = emptyList() }
    }
}
