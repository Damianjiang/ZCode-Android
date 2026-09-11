package app.zemote.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.zemote.state.AccountStore
import app.zemote.state.AppSessionViewModel
import app.zemote.ui.screens.AccountsScreen
import app.zemote.ui.screens.ChatScreen
import app.zemote.ui.screens.ChangelogScreen
import app.zemote.ui.screens.MainShellScreen
import app.zemote.ui.screens.SettingsScreen
import app.zemote.ui.screens.TasksScreen
import app.zemote.ui.theme.ThemeManager

// M3 强调曲线：进入用减速曲线「冲进来」，退出用加速曲线「加速离场」
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val AnimDuration = 340

private fun forwardEnter(): EnterTransition = slideInHorizontally(
    animationSpec = tween(AnimDuration, easing = EmphasizedDecelerate),
    initialOffsetX = { it / 4 },
) + fadeIn(tween(AnimDuration, easing = EmphasizedDecelerate))

private fun forwardExit(): ExitTransition = slideOutHorizontally(
    animationSpec = tween(AnimDuration, easing = EmphasizedAccelerate),
    targetOffsetX = { -it / 6 },
) + fadeOut(tween(AnimDuration, easing = EmphasizedAccelerate))

private fun backEnter(): EnterTransition = slideInHorizontally(
    animationSpec = tween(AnimDuration, easing = EmphasizedDecelerate),
    initialOffsetX = { -it / 6 },
) + fadeIn(tween(AnimDuration, easing = EmphasizedDecelerate))

private fun backExit(): ExitTransition = slideOutHorizontally(
    animationSpec = tween(AnimDuration, easing = EmphasizedAccelerate),
    targetOffsetX = { it / 4 },
) + fadeOut(tween(AnimDuration, easing = EmphasizedAccelerate))

sealed class Screen(val route: String) {
    object Accounts : Screen("accounts")
    object MainShell : Screen("main_shell/{accountId}") {
        fun createRoute(accountId: String) = "main_shell/$accountId"
    }
    object Tasks : Screen("tasks/{workspaceKey}") {
        fun createRoute(workspaceKey: String) = "tasks/$workspaceKey"
    }
    object Chat : Screen("chat/{workspaceKey}/{sessionId}") {
        fun createRoute(workspaceKey: String, sessionId: String) = "chat/$workspaceKey/$sessionId"
    }
    object Settings : Screen("settings")
    object Changelog : Screen("changelog")
}

@Composable
fun ZemoteNavHost(
    accountStore: AccountStore,
    sessionViewModel: AppSessionViewModel,
    themeManager: ThemeManager,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Accounts.route,
        enterTransition = { forwardEnter() },
        exitTransition = { forwardExit() },
        popEnterTransition = { backEnter() },
        popExitTransition = { backExit() },
    ) {
        composable(Screen.Accounts.route) {
            AccountsScreen(
                store = accountStore,
                session = sessionViewModel,
                onNavigateToShell = { account ->
                    navController.navigate(Screen.MainShell.createRoute(account.id))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        composable(Screen.MainShell.route) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: return@composable
            val account = sessionViewModel.currentAccount(accountId)
                ?: accountStore.accounts.value.firstOrNull { it.id == accountId }
                ?: return@composable
            MainShellScreen(
                account = account,
                session = sessionViewModel,
                store = accountStore,
                onBack = { navController.popBackStack() },
                onNavigateToTasks = { workspaceKey ->
                    navController.navigate(Screen.Tasks.createRoute(workspaceKey))
                },
                onNavigateToChat = { workspaceKey, sessionId ->
                    navController.navigate(Screen.Chat.createRoute(workspaceKey, sessionId))
                },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
            )
        }
        composable(Screen.Tasks.route) { backStackEntry ->
            val workspaceKey = backStackEntry.arguments?.getString("workspaceKey") ?: return@composable
            TasksScreen(
                workspaceKey = workspaceKey,
                session = sessionViewModel,
                onBack = { navController.popBackStack() },
                onOpenSession = { sessionId ->
                    navController.navigate(Screen.Chat.createRoute(workspaceKey, sessionId ?: "new"))
                },
            )
        }
        composable(Screen.Chat.route) { backStackEntry ->
            val workspaceKey = backStackEntry.arguments?.getString("workspaceKey") ?: return@composable
            val sessionId = backStackEntry.arguments?.getString("sessionId")?.takeIf { it != "new" }
            ChatScreen(
                workspaceKey = workspaceKey,
                sessionId = sessionId,
                session = sessionViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenChangelog = { navController.navigate(Screen.Changelog.route) },
                themeManager = themeManager,
            )
        }
        composable(Screen.Changelog.route) {
            ChangelogScreen(onBack = { navController.popBackStack() })
        }
    }
}
