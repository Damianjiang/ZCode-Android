package app.zemote.ui.screens

import app.zemote.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import app.zemote.state.Account
import app.zemote.state.AccountStore
import app.zemote.state.AppSessionViewModel

private data class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@Composable
private fun mainTabs(): List<MainTab> = listOf(
    MainTab(stringResource(R.string.nav_devices), Icons.Rounded.Devices, Icons.Outlined.Devices),
    MainTab(stringResource(R.string.nav_settings), Icons.Rounded.Settings, Icons.Outlined.Settings),
)

/**
 * 底部导航栏主页面：设备 / 设置 两个 Tab。
 * 工作区、任务、对话等二级页面从 NavHost 压栈显示（无底部栏）。
 */
@Composable
fun MainScreen(
    store: AccountStore,
    session: AppSessionViewModel,
    onNavigateToShell: (Account) -> Unit,
    onOpenPersonalize: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                mainTabs().forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                if (tab == index) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.title,
                            )
                        },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(220)) + androidx.compose.animation.scaleIn(
                    initialScale = 0.96f,
                    animationSpec = tween(220),
                )) togetherWith fadeOut(tween(150))
            },
            label = "mainTabs",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (current) {
                    0 -> AccountsScreen(store = store, session = session, onNavigateToShell = onNavigateToShell)
                    else -> SettingsScreen(
                        onOpenPersonalize = onOpenPersonalize,
                        onOpenChangelog = onOpenChangelog,
                    )
                }
            }
        }
    }
}
