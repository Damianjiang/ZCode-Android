package app.zemote.state

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * 全局应用设置（SharedPreferences 持久化）。
 * 所有键定义在 [Keys] 内。
 */
object AppSettings {

    object Keys {
        const val MAX_MESSAGES = "max_messages"
    }

    private const val DEFAULT_MAX_MESSAGES = 100
    private const val FILE = "zemote_settings"

    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /** 最多展示的历史消息条数（默认 200） */
    // 缓存 SharedPreferences 实例，避免每次读写都重新打开文件
    private val prefs by lazy { context.getSharedPreferences(FILE, MODE_PRIVATE) }

    var maxMessages: Int
        get() = prefs.getInt(Keys.MAX_MESSAGES, DEFAULT_MAX_MESSAGES)
        set(value) {
            val vs = value.coerceIn(50, 1000)
            prefs.edit().putInt(Keys.MAX_MESSAGES, vs).apply()
        }
}
