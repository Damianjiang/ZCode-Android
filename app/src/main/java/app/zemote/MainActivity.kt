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
import app.zemote.state.AccountStore
import app.zemote.state.AppSessionViewModel
import app.zemote.ui.navigation.ZemoteNavHost
import app.zemote.ui.theme.ThemeManager
import app.zemote.ui.theme.ZemoteTheme

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zemote_settings")

class MainActivity : ComponentActivity() {
    private val dataStore by lazy { application.dataStore }
    private val themeManager by lazy { ThemeManager(dataStore) }
    private val accountStore by lazy { AccountStore(dataStore = dataStore) }
    private val sessionViewModel by lazy { AppSessionViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
