package app.zemote.state

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.res.Configuration
import java.util.Locale

/**
 * 界面语言设置。默认跟随系统（自动检测手机语言），也可手动指定中文或英文。
 * 语言选择保存在独立 SharedPreferences 中，attachBaseContext 阶段同步读取。
 */
object LanguagePrefs {
    private const val FILE = "zemote_lang"
    private const val KEY = "lang"

    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    val options = listOf(SYSTEM, ZH, EN)

    fun get(context: Context): String =
        context.getSharedPreferences(FILE, MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, value: String) {
        context.getSharedPreferences(FILE, MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    /** 按语言设置包装 Context；跟随系统时原样返回（系统已带 locale）。 */
    fun wrap(context: Context): Context {
        val lang = get(context)
        val locale = when (lang) {
            ZH -> Locale.SIMPLIFIED_CHINESE
            EN -> Locale.ENGLISH
            else -> return context
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
