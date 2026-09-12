package app.zemote.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.state.Account
import app.zemote.state.AccountStore
import app.zemote.state.AppSessionViewModel
import app.zemote.state.ConnectionState
import app.zemote.ui.component.DeviceAvatar
import app.zemote.ui.component.StatusDot
import app.zemote.ui.component.WorkspaceAvatar
import app.zemote.ui.component.statusPresentation

/**
 * 连接后主界面：进入即自动发起连接，状态机驱动
 * 连接动画 → 工作区列表 / 错误重试 的全过程切换。
 */
@Composable
fun MainShellScreen(
    account: Account,
    session: AppSessionViewModel,
    store: AccountStore,
    onBack: () -> Unit,
    onNavigateToTasks: (String) -> Unit,
    onNavigateToChat: (String, String) -> Unit,
) {
    val uiState by session.uiState.collectAsState()
    val accounts by store.accounts.collectAsState()
    var showDeviceSheet by remember { mutableStateOf(false) }

    // 进入页面自动连接（重复调用安全：内部有互斥与复用）
    LaunchedEffect(account.id) { session.connect(account) }

    val status = uiState.statuses[account.id] ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 + 设备信息 + 切换/设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            DeviceAvatar(id = account.id, iconSize = 18, corner = 12)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                account.params?.sourceHost?.let { host ->
                    Text(
                        host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { showDeviceSheet = true }) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "切换设备")
            }
        }

        AnimatedContent(
            targetState = status.state,
            transitionSpec = {
                (fadeIn(tween(300)) + scaleIn(initialScale = 0.96f, animationSpec = tween(300)))
                    .togetherWith(fadeOut(tween(220)) + scaleOut(targetScale = 0.96f, animationSpec = tween(220)))
            },
            label = "shellState",
        ) { state ->
            when (state) {
                ConnectionState.CONNECTING -> ConnectingContent(account)
                ConnectionState.ERROR -> ErrorContent(
                    message = status.message ?: "连接失败",
                    onRetry = { session.connect(account) },
                )
                else -> {
                    val client = session.clientOf(account.id)
                    if (client == null) {
                        ErrorContent(message = "设备尚未连接", onRetry = { session.connect(account) })
                    } else {
                        WorkspaceList(
                            client = client,
                            session = session,
                            accountId = account.id,
                            onOpen = { ws ->
                                val key = ws["workspaceIdentity"] as? String
                                    ?: ws["workspacePath"] as? String
                                    ?: ws["workspaceKey"] as? String
                                    ?: ws["key"] as? String
                                    ?: ws["id"] as? String
                                if (key != null) onNavigateToTasks(key)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDeviceSheet) {
        DeviceSwitchSheet(
            accounts = accounts,
            activeId = uiState.activeId,
            statuses = uiState.statuses,
            onSwitch = {
                showDeviceSheet = false
                session.switchTo(it)
            },
            onDisconnect = { session.disconnect(it) },
            onAddDevice = {
                showDeviceSheet = false
                onBack()
            },
            onDismiss = { showDeviceSheet = false },
        )
    }
}

@Composable
private fun ConnectingContent(account: Account) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val presentation = statusPresentation(ConnectionState.CONNECTING, null)
        Box(contentAlignment = Alignment.Center) {
            StatusDot(
                color = MaterialTheme.colorScheme.primary,
                pulsing = true,
                size = 96,
            )
            DeviceAvatar(id = account.id, iconSize = 34, corner = 24)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("正在配对「${account.label}」", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "与桌面端安全握手中，首次连接最长需 90 秒",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(52.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("连接失败", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("重试连接")
        }
    }
}

@Composable
private fun WorkspaceList(
    client: app.zemote.protocol.ZemoteClient,
    session: AppSessionViewModel,
    accountId: String,
    onOpen: (Map<String, Any>) -> Unit,
) {
    var workspaces by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            val result = client.bootstrap()
            android.util.Log.d("Zemote", "[bootstrap] $result")
            @Suppress("UNCHECKED_CAST")
            workspaces = (result["workspaces"] as? List<Map<String, Any>>) ?: emptyList()
            // 缓存每个工作区的原始 map（V4 会话握手的 scopeParams 需要）
            workspaces.forEach { ws ->
                val key = (ws["workspaceIdentity"] as? String)
                    ?: (ws["workspacePath"] as? String)
                    ?: return@forEach
                session.cacheWorkspaceScope(accountId, key, ws)
            }
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

    LaunchedEffect(client, retryKey) { refresh() }

    // 连接恢复后自动重新加载（断线重连、被其他客户端抢占后抢回等场景）
    LaunchedEffect(client) {
        var wasPaired = false
        client.state.collect { st ->
            if (st == app.zemote.protocol.ZemoteClient.ZemoteClientState.PAIRED) {
                if (wasPaired) refresh()
                wasPaired = true
            }
        }
    }

    when {
        loading -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("正在获取工作区…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error != null -> ErrorContent(message = "加载工作区失败: $error", onRetry = { retryKey++ })
        workspaces.isEmpty() -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "桌面端没有打开的工作区",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "工作区",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(workspaces, key = { w ->
                (w["workspaceIdentity"] as? String ?: w["workspacePath"] as? String ?: w.hashCode().toString())
            }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    onClick = { onOpen(workspace) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: Map<String, Any>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = workspace["workspaceIdentity"] as? String
        ?: workspace["workspacePath"] as? String ?: ""
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceAvatar(key = key, icon = Icons.Rounded.Folder)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = (workspace["label"] as? String)
                    ?: (workspace["workspacePath"] as? String)?.split("[\\\\/]".toRegex())?.lastOrNull { it.isNotEmpty() }
                    ?: workspace["workspaceIdentity"] as? String ?: "未知工作区"
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val path = workspace["workspacePath"] as? String ?: ""
                val kind = workspace["kind"] as? String ?: ""
                Text(
                    listOf(kind, path).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
