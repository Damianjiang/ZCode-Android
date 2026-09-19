package app.zemote.state

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * 隐私设置持久化（SharedPreferences）。
 * 包括优化Agent体验等隐私相关选项。
 */
object PrivacySettings {

    object Keys {
        const val OPTIMIZE_AGENT_EXPERIENCE = "optimize_agent_experience"
    }

    private const val FILE = "zemote_privacy_settings"

    @Volatile
    private var contextRef: Context? = null

    fun init(context: Context) {
        this.contextRef = context.applicationContext
    }

    /**
     * 是否启用Agent体验优化
     * 对应官方Web的 settings.privacy.toggle_optimize_agent_experience
     */
    var optimizeAgentExperience: Boolean
        get() = contextRef?.getSharedPreferences(FILE, MODE_PRIVATE)
            ?.getBoolean(Keys.OPTIMIZE_AGENT_EXPERIENCE, true) ?: true
        set(value) {
            contextRef?.getSharedPreferences(FILE, MODE_PRIVATE)?.edit()
                ?.putBoolean(Keys.OPTIMIZE_AGENT_EXPERIENCE, value)
                ?.apply()
        }
}
