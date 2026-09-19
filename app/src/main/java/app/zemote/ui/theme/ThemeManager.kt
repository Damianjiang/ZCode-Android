package app.zemote.ui.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 主题设置快照：模式（浅色/深色/跟随系统）+ 动态取色 + 品牌色盘 */
data class ThemeState(
    val mode: ThemeManager.ThemeMode = ThemeManager.ThemeMode.FOLLOW_SYSTEM,
    val dynamicColor: Boolean = true,
    val palette: String = "iris",
)

/**
 * 主题管理器。设置项通过 DataStore 异步持久化，[state] 作为唯一可观察数据源，
 * 读写都不阻塞主线程（旧实现 runBlocking 读写在组合期间会卡 UI，已废弃）。
 */
class ThemeManager(private val dataStore: DataStore<Preferences>) {

    enum class ThemeMode { LIGHT, DARK, FOLLOW_SYSTEM }

    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
        private val PALETTE_KEY = stringPreferencesKey("theme_palette")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val state: StateFlow<ThemeState> = dataStore.data.map { prefs ->
        val mode = prefs[THEME_MODE_KEY]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.FOLLOW_SYSTEM
        ThemeState(
            mode = mode,
            dynamicColor = prefs[DYNAMIC_COLOR_KEY] ?: true,
            palette = prefs[PALETTE_KEY] ?: "iris",
        )
    }.stateIn(scope, SharingStarted.Eagerly, ThemeState())

    fun setMode(mode: ThemeMode) {
        scope.launch { dataStore.edit { it[THEME_MODE_KEY] = mode.name } }
    }

    fun setDynamicColor(enabled: Boolean) {
        scope.launch { dataStore.edit { it[DYNAMIC_COLOR_KEY] = enabled } }
    }

    fun setPalette(key: String) {
        scope.launch { dataStore.edit { it[PALETTE_KEY] = key } }
    }
}
