package app.zemote.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.net.Uri
import app.zemote.state.AccountStore
import app.zemote.state.AppSessionViewModel
import app.zemote.ui.screens.AccountsScreen
import app.zemote.ui.screens.AISettingsScreen
import app.zemote.ui.screens.CacheCleanScreen
import app.zemote.ui.screens.ChatScreen
import app.zemote.ui.screens.ChangelogScreen
import app.zemote.ui.screens.FeedbackScreen
import app.zemote.ui.screens.LogScreen
import app.zemote.ui.screens.MainScreen
import app.zemote.ui.screens.MainShellScreen
import app.zemote.ui.screens.PersonalizeScreen
import app.zemote.ui.screens.TasksScreen
import app.zemote.ui.theme.ThemeManager
import kotlinx.coroutines.launch

/**
 * M3「Fade Through」转场：新页面淡入 + 轻微放大，旧页面快速淡出。
 * 相比双向滑动更顺滑，没有背景闪动。
 */
private const val DurIn = 260
private const val DurOut = 110
private val Decelerate = CubicBezierEasing(0.1f, 0.7f, 0.1f, 1f)
private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private fun enterThrough(): EnterTransition = fadeIn(tween(DurIn, delayMillis = DurOut / 2)) +
    scaleIn(initialScale = 0.92f, animationSpec = tween(DurIn, easing = Decelerate))

private fun exitThrough(): ExitTransition = fadeOut(tween(DurOut, easing = Accelerate))

/**
 * 导航路由。
 *
 * ⚠️ `workspaceKey` 常常是**文件系统路径**（`MainShellScreen` 取
 * `workspaceIdentity ?: workspacePath`，任务列表又优先用 `workspacePath`），
 * 所以它可能包含 `/`、`\`、空格、`#`、`?` 等字符。
 *
 * 旧实现把路径**原样拼进路由字符串**（`"tasks/$workspaceKey"`）。Navigation 是按
 * `/` 切分路径段来匹配 `{占位符}` 的：`/home/me/proj` 会被切成 3 段，而模板
 * `tasks/{workspaceKey}` 只接受 1 段 —— 匹配失败，`navigate()` 直接抛
 * `IllegalArgumentException: Navigation destination that matches request ... cannot be found`。
 * 也就是说**只要桌面端报的是 POSIX 风格路径，点进任务页就会崩**。
 *
 * 修复：构建路由时对每个参数 `Uri.encode`（`/` → `%2F`，保证只占一段），
 * 读取时 `Uri.decode` 还原。Navigation 内部对 path 参数也会解码一次，
 * 但对普通路径来说重复解码是幂等的（不含 `%` 的串解码后不变）。
 */
sealed class Screen(val route: String) {
    object Main : Screen("main")
    object Personalize : Screen("personalize")
    object AISettings : Screen("ai_settings")
    object Feedback : Screen("feedback")
    object Changelog : Screen("changelog")
    object Log : Screen("log")
    object CacheClean : Screen("cache_clean")
    object QrScan : Screen("qr_scan")
    object MainShell : Screen("main_shell/{accountId}") {
        fun createRoute(accountId: String) = "main_shell/${Uri.encode(accountId)}"
    }
    object Tasks : Screen("tasks/{workspaceKey}") {
        fun createRoute(workspaceKey: String) = "tasks/${Uri.encode(workspaceKey)}"
    }
    object Chat : Screen("chat/{workspaceKey}/{sessionId}") {
        fun createRoute(workspaceKey: String, sessionId: String) =
            "chat/${Uri.encode(workspaceKey)}/${Uri.encode(sessionId)}"
    }
    object Subagent : Screen("subagent/{workspaceKey}/{childSessionId}/{parentSessionId}") {
        fun createRoute(workspaceKey: String, childSessionId: String, parentSessionId: String) =
            "subagent/${Uri.encode(workspaceKey)}/${Uri.encode(childSessionId)}/${Uri.encode(parentSessionId)}"
    }
}

/** 读取导航 path 参数并还原（与 [Screen.createRoute] 的 `Uri.encode` 配对）。 */
private fun android.os.Bundle?.arg(key: String): String? =
    this?.getString(key)?.let { Uri.decode(it) }

@Composable
fun ZemoteNavHost(
    accountStore: AccountStore,
    sessionViewModel: AppSessionViewModel,
    themeManager: ThemeManager,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Main.route,
        enterTransition = { enterThrough() },
        exitTransition = { exitThrough() },
        popEnterTransition = { enterThrough() },
        popExitTransition = { exitThrough() },
    ) {
        composable(Screen.Main.route) {
            MainScreen(
                store = accountStore,
                session = sessionViewModel,
                onNavigateToShell = { account ->
                    navController.navigate(Screen.MainShell.createRoute(account.id))
                },
                onOpenPersonalize = { navController.navigate(Screen.Personalize.route) },
                onOpenAISettings = { navController.navigate(Screen.AISettings.route) },
                onOpenFeedback = { navController.navigate(Screen.Feedback.route) },
                onOpenChangelog = { navController.navigate(Screen.Changelog.route) },
                onOpenLogs = { navController.navigate(Screen.Log.route) },
                onOpenCacheClean = { navController.navigate(Screen.CacheClean.route) },
                onScan = { navController.navigate(Screen.QrScan.route) },
            )
        }
        composable(Screen.QrScan.route) {
            app.zemote.ui.screens.QrScanScreen(
                onBack = { navController.popBackStack() },
                onResult = { url ->
                    sessionViewModel.scannedPairingUrl = url
                    navController.popBackStack()
                },
            )
        }
        composable(Screen.Personalize.route) {
            PersonalizeScreen(onBack = { navController.popBackStack() }, themeManager = themeManager)
        }
        composable(Screen.AISettings.route) {
            AISettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Feedback.route) {
            FeedbackScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Changelog.route) {
            ChangelogScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Log.route) {
            LogScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.CacheClean.route) {
            CacheCleanScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.MainShell.route) { backStackEntry ->
            val accountId = backStackEntry.arguments.arg("accountId") ?: return@composable
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
            )
        }
        composable(Screen.Tasks.route) { backStackEntry ->
            val workspaceKey = backStackEntry.arguments.arg("workspaceKey") ?: return@composable
            TasksScreen(
                workspaceKey = workspaceKey,
                session = sessionViewModel,
                onBack = { navController.popBackStack() },
                onOpenSession = { entry ->
                    // 每个任务用自己所属 workspace 打开（任务可能来自不同目录）
                    val wk = entry?.workspacePath ?: workspaceKey
                    navController.navigate(Screen.Chat.createRoute(wk, entry?.taskId ?: "new"))
                },
            )
        }
        composable(Screen.Chat.route) { backStackEntry ->
            val workspaceKey = backStackEntry.arguments.arg("workspaceKey") ?: return@composable
            val sessionId = backStackEntry.arguments.arg("sessionId")?.takeIf { it != "new" }
            ChatScreen(
                workspaceKey = workspaceKey,
                sessionId = sessionId,
                session = sessionViewModel,
                onBack = { navController.popBackStack() },
                onOpenSubagent = { wk, cid, pid ->
                    navController.navigate(Screen.Subagent.createRoute(wk, cid, pid))
                },
            )
        }
        composable(Screen.Subagent.route) { backStackEntry ->
            val workspaceKey = backStackEntry.arguments.arg("workspaceKey") ?: return@composable
            val childSessionId = backStackEntry.arguments.arg("childSessionId") ?: return@composable
            val parentSessionId = backStackEntry.arguments.arg("parentSessionId")
            val scope = rememberCoroutineScope()
            ChatScreen(
                workspaceKey = workspaceKey,
                sessionId = childSessionId,
                session = sessionViewModel,
                onBack = {
                    // 回退时强制恢复父会话（同 bridge，切换 active session）
                    if (parentSessionId != null) {
                        val acc = sessionViewModel.activeId
                        if (acc != null) {
                            scope.launch {
                                runCatching {
                                    sessionViewModel.conversationFor(acc, workspaceKey)
                                        ?.openConversation(parentSessionId, force = true)
                                }
                            }
                        }
                    }
                    navController.popBackStack()
                },
                readOnly = true,
            )
        }
    }
}
