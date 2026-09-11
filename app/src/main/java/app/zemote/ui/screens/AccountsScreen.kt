package app.zemote.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.state.Account
import app.zemote.state.AccountStore
import app.zemote.state.AppSessionViewModel
import app.zemote.state.ConnectionState
import app.zemote.ui.component.DeviceAvatar
import app.zemote.ui.component.StatusDot
import app.zemote.ui.component.statusPresentation
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(
    store: AccountStore,
    session: AppSessionViewModel,
    onNavigateToShell: (Account) -> Unit,
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Account?>(null) }
    val accounts by store.accounts.collectAsState()
    val uiState by session.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { store.load() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hero 头部：大标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "设备",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "连接你的桌面 ZCode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedContent(
                targetState = accounts.isEmpty(),
                transitionSpec = {
                    (fadeIn(tween(280)) + scaleIn(initialScale = 0.94f, animationSpec = tween(280)))
                        .togetherWith(fadeOut(tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)))
                },
                label = "accountsContent",
            ) { isEmpty ->
                if (isEmpty) {
                    EmptyStateContent(
                        onAdd = { showAddSheet = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(accounts, key = { it.id }) { account ->
                            val status = uiState.statuses[account.id]
                            AccountCard(
                                account = account,
                                state = status?.state ?: ConnectionState.IDLE,
                                message = status?.message,
                                active = uiState.activeId == account.id,
                                onClick = { onNavigateToShell(account) },
                                onDisconnect = { session.disconnect(account.id) },
                                onRename = { renameTarget = account },
                                onDelete = {
                                    if (session.isConnected(account.id)) session.disconnect(account.id)
                                    scope.launch {
                                        store.remove(account.id)
                                        snackHost.showSnackbar("已删除「${account.label}」")
                                    }
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showAddSheet = true },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("添加设备") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        )

        SnackbarHost(
            hostState = snackHost,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showAddSheet) {
        AddDeviceBottomSheet(
            onDismiss = { showAddSheet = false },
            onScan = {
                showAddSheet = false
                scope.launch { snackHost.showSnackbar("扫码功能即将上线，请先粘贴链接添加") }
            },
            onUrlSubmit = { url, label ->
                showAddSheet = false
                if (url.trim().isEmpty()) return@AddDeviceBottomSheet
                scope.launch {
                    store.addAccount(url.trim(), label.takeIf { it.isNotBlank() })
                    snackHost.showSnackbar("设备已添加")
                }
            }
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.label,
            onConfirm = { newLabel ->
                scope.launch { store.rename(target.id, newLabel) }
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun EmptyStateContent(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 渐变光斑插画
        Box(
            modifier = Modifier.size(132.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        ),
                        RoundedCornerShape(44.dp),
                    )
            )
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(32.dp))
            )
            Icon(
                Icons.Rounded.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text("还没有设备", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "把桌面 ZCode 的远程控制链接粘贴进来，\n或扫描配对二维码，随时随地去连。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加第一台设备")
        }
    }
}

@Composable
private fun AccountCard(
    account: Account,
    state: ConnectionState,
    message: String?,
    active: Boolean,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = statusPresentation(state, message)
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceAvatar(id = account.id, iconSize = 24)

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = presentation.color,
                        pulsing = presentation.pulsing,
                        size = 7,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        presentation.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = presentation.color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 连接中转圈；已连接显示断开；否则显示闪电连接按钮
            when (state) {
                ConnectionState.CONNECTING -> {
                    StatusDot(color = MaterialTheme.colorScheme.tertiary, pulsing = true, size = 10)
                }
                ConnectionState.CONNECTED -> {
                    Surface(
                        onClick = onDisconnect,
                        shape = CircleShape,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Rounded.LinkOff,
                                contentDescription = "断开",
                                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                else -> {
                    Surface(
                        onClick = onClick,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Rounded.Bolt,
                                contentDescription = "连接",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名设备") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeviceBottomSheet(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onUrlSubmit: (String, String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("添加设备", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("远程控制 URL") },
                placeholder = {
                    Text(
                        "https://…?sid=…&hash=…&t=…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                minLines = 2,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                supportingText = { Text("从桌面 ZCode 复制远程控制链接") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("设备名称（可选）") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onScan,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("扫码添加")
                }
                Button(
                    onClick = { if (url.isNotBlank()) onUrlSubmit(url, label) },
                    enabled = url.isNotBlank(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("添加设备")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
