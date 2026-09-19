package app.zemote.state

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * AI 模型设置持久化（SharedPreferences）。
 * 包括模型提供商、模型 ID、思考等级等选项。
 */
object AISettings {

    object Keys {
        const val MODEL_PROVIDER = "model_provider"
        const val MODEL_ID = "model_id"
        const val THOUGHT_LEVEL = "thought_level"
    }

    private const val FILE = "zemote_ai_settings"

    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * 支持的模型提供商列表
     * 对应官方 Web 中的 bigmodel / zcode 等 provider
     */
    enum class ModelProvider(val id: String, val displayName: String) {
        BIGMODEL("bigmodel", "BigModel"),
        ZCODE("zcode", "ZCode"),
    }

    /**
     * 思考等级
     * 对应官方 Web 的 ThoughtLevel: auto / light / deep
     */
    enum class ThoughtLevel(val id: String, val displayName: String) {
        AUTO("auto", "Auto"),
        LIGHT("light", "Light"),
        DEEP("deep", "Deep"),
    }

    /** 当前选中的模型提供商 */
    var modelProvider: ModelProvider
        get() {
            val id = context.getSharedPreferences(FILE, MODE_PRIVATE)
                .getString(Keys.MODEL_PROVIDER, ModelProvider.BIGMODEL.id)
                ?: ModelProvider.BIGMODEL.id
            return ModelProvider.values().firstOrNull { it.id == id } ?: ModelProvider.BIGMODEL
        }
        set(value) {
            context.getSharedPreferences(FILE, MODE_PRIVATE).edit()
                .putString(Keys.MODEL_PROVIDER, value.id)
                .apply()
        }

    /** 当前选中的模型 ID */
    var modelId: String
        get() = context.getSharedPreferences(FILE, MODE_PRIVATE)
            .getString(Keys.MODEL_ID, "") ?: ""
        set(value) {
            context.getSharedPreferences(FILE, MODE_PRIVATE).edit()
                .putString(Keys.MODEL_ID, value)
                .apply()
        }

    /** 当前选中的思考等级 */
    var thoughtLevel: ThoughtLevel
        get() {
            val id = context.getSharedPreferences(FILE, MODE_PRIVATE)
                .getString(Keys.THOUGHT_LEVEL, ThoughtLevel.AUTO.id)
                ?: ThoughtLevel.AUTO.id
            return ThoughtLevel.values().firstOrNull { it.id == id } ?: ThoughtLevel.AUTO
        }
        set(value) {
            context.getSharedPreferences(FILE, MODE_PRIVATE).edit()
                .putString(Keys.THOUGHT_LEVEL, value.id)
                .apply()
        }
}
