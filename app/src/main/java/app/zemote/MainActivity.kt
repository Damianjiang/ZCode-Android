package app.zemote

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.zemote.crash.CrashHandler
import app.zemote.state.AccountStore
import app.zemote.state.AppSessionViewModel
import app.zemote.ui.navigation.ZemoteNavHost
import app.zemote.ui.screens.CrashScreen
import app.zemote.ui.theme.ThemeManager
import app.zemote.ui.theme.ZemoteTheme

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zemote_settings")

class MainActivity : ComponentActivity() {
    private val dataStore by lazy { application.dataStore }
    private val themeManager by lazy { ThemeManager(dataStore) }
    private val accountStore by lazy { AccountStore(dataStore = dataStore) }
    private val sessionViewModel by lazy { AppSessionViewModel(application) }

    override fun attachBaseContext(newBase: Context) {
        // 语言设置：默认跟随系统，可在设置中手动指定中文/英文
        super.attachBaseContext(app.zemote.state.LanguagePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.zemote.state.AppSettings.init(this)
        // 权限延迟到扫码页申请，避免启动时弹窗打断用户
        // 检测上次崩溃：存在崩溃报告则直接进入崩溃页
        val crashLog = CrashHandler.read(this)
        if (crashLog != null) {
            setContent {
                ZemoteTheme(themeManager = themeManager) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CrashScreen(
                            log = crashLog,
                            onRestart = {
                                CrashHandler.clear(this)
                                recreate()
                            },
                        )
                    }
                }
            }
            return
        }

        setContent {
            ZemoteTheme(themeManager = themeManager) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZemoteNavHost(
                        accountStore = accountStore,
                        sessionViewModel = sessionViewModel,
                        themeManager = themeManager,
                    )
                }
            }
        }
    }
}
