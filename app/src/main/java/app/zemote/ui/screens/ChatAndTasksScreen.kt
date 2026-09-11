package app.zemote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.protocol.ConvKinds
import app.zemote.protocol.ConvRow
import app.zemote.protocol.TaskEntry
import app.zemote.state.AppSessionViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// ────────────────────────── 任务会话列表 ──────────────────────────

/** 任务会话列表：运行中置顶 + 历史记录（真实数据，来自 bootstrap 的 tasks） */
@Composable
fun TasksScreen(
    workspaceKey: String,
    session: AppSessionViewModel,
    onBack: () -> Unit,
    onOpenSession: (String?) -> Unit,
) {
    val accountId = session.activeId
    var tasks by remember { mutableStateOf<List<app.zemote.protocol.TaskEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(accountId, workspaceKey) {
        val client = accountId?.let { session.clientOf(it) }
        if (client == null) {
            error = "设备未连接"
            loading = false
            return@LaunchedEffect
        }
        runCatching { app.zemote.protocol.fetchTasksFromBootstrap(client) }
            .onSuccess {
                tasks = it
                loading = false
            }
            .onFailure {
                error = it.message ?: "无法获取任务列表"
                loading = false
            }
    }

    val running = tasks.filter { it.running }
    val history = tasks.filterNot { it.running }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "任务会话", onBack = onBack)

        when {
            error != null -> CenterHint(
                icon = { Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp)) },
                title = "无法获取会话",
                body = error,
            )
            loading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ThinkingDot()
                Spacer(modifier = Modifier.height(14.dp))
                Text("正在获取会话…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (running.isNotEmpty()) {
                    item { SectionText("运行中") }
                    items(running, key = { it.taskId }) { entry ->
                        SessionRow(
                            title = entry.title,
                            subtitle = entry.workspaceLabel,
                            highlight = true,
                            onClick = { onOpenSession(entry.taskId) },
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    item { SectionText("历史会话") }
                    items(history, key = { "h-" + it.taskId }) { entry ->
                        SessionRow(
                            title = entry.title,
                            subtitle = entry.workspaceLabel,
                            highlight = false,
                            onClick = { onOpenSession(entry.taskId) },
                        )
                    }
                }
                if (tasks.isEmpty()) {
                    item {
                        CenterHint(
                            icon = { Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp)) },
                            title = "暂无会话记录",
                            body = "桌面端的历史会话会显示在这里；也可以直接发起新对话",
                            modifier = Modifier.padding(top = 80.dp),
                        )
                    }
                }
                item {
                    // 新对话入口：不依赖已有会话
                    Surface(
                        onClick = { onOpenSession(null) },
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "发起新对话",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ────────────────────────── 对话页 ──────────────────────────

/** 对话页：官方 V4 协议的时间线（思考/工具/文本）+ 全新发送栏 */
@Composable
fun ChatScreen(
    workspaceKey: String,
    sessionId: String?,
    session: AppSessionViewModel,
    onBack: () -> Unit,
) {
    val accountId = session.activeId
    var repo by remember { mutableStateOf<app.zemote.protocol.ConversationV4Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var historyUnavailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(accountId, workspaceKey, sessionId) {
        if (accountId == null) {
            error = "设备未连接"
            return@LaunchedEffect
        }
        runCatching { session.conversationFor(accountId, workspaceKey, sessionId) }
            .onSuccess { repo = it }
            .onFailure { error = it.message ?: "无法打开会话通道" }
    }

    LaunchedEffect(repo, sessionId) {
        repo?.openConversation(sessionId)
        // 历史行仍未到达 → 桌面端版本对该通道无响应（兼容性提示）
        kotlinx.coroutines.delay(8000)
        if (repo?.rows?.value.isNullOrEmpty()) historyUnavailable = true
        else historyUnavailable = false
    }

    val rows by (repo?.rows?.collectAsState() ?: remember { mutableStateOf(emptyList<ConvRow>()) })
    val working by (repo?.agentWorking?.collectAsState() ?: remember { mutableStateOf(false) })
    val activeId by (repo?.activeSessionId?.collectAsState() ?: remember { mutableStateOf(sessionId) })

    // 新消息自动滚到底部
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) {
            historyUnavailable = false
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        ScreenHeader(
            title = if (activeId != null) "任务会话" else "新对话",
            subtitle = activeId?.take(12),
            onBack = onBack,
        )

        val errorMessage = error
        if (errorMessage != null) {
            CenterHint(
                icon = { Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp)) },
                title = "无法打开对话",
                body = errorMessage,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (historyUnavailable) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "暂未收到该会话的历史消息：当前桌面版本对移动端会话通道的兼容性有限。\n输入栏发送功能仍可用，历史消息的展示将在后续版本适配。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
                items(rows, key = { it.rowId }) { row ->
                    TimelineRow(row = row)
                }
                if (working) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThinkingDot()
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "正在处理…",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            ComposerBar(
                text = input,
                onTextChange = { input = it },
                working = working,
                enabled = repo != null && error == null,
                onSend = { queued ->
                    val text = input
                    input = ""
                    scope.launch {
                        runCatching {
                            // AI 回复中 → 官方 queue 语义（排队）；空闲 → startNow
                            repo?.sendText(
                                text,
                                activeId,
                                requestedDelivery = if (queued) "queue" else "startNow",
                            )
                        }.onFailure { input = text }
                    }
                },
                onStop = {
                    scope.launch { runCatching { repo?.stop(activeId) } }
                },
            )
        }
    }
}

// ────────────────────────── 时间线渲染 ──────────────────────────

@Composable
private fun TimelineRow(row: ConvRow) {
    when (row.kind) {
        ConvKinds.USER_INPUT -> UserBubble(row)
        ConvKinds.ASSISTANT_TEXT -> if (row.text.isNotBlank()) {
            Text(
                row.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ConvKinds.REASONING -> if (row.text.isNotBlank()) ThinkingBlock(row)
        ConvKinds.TOOL_CALL -> ToolCallBlock(row)
        ConvKinds.SUBAGENT -> if (row.summaryText.isNotBlank() || row.text.isNotBlank()) {
            ToolCallBlock(row.copy(toolName = "subagent", inputText = row.summaryText.ifBlank { row.text }))
        }
        else -> Unit
    }
}

@Composable
private fun UserBubble(row: ConvRow) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                row.text.ifBlank { row.inputText },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** 思考块：默认折叠的"🧠 思考"，点击展开全文 */
@Composable
private fun ThinkingBlock(row: ConvRow) {
    var expanded by remember(row.rowId) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "思考",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    row.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 工具调用块：终端 / 查阅 / 更改 / 子任务，输出默认折叠 */
@Composable
private fun ToolCallBlock(row: ConvRow) {
    var expanded by remember(row.rowId) { mutableStateOf(false) }
    val (icon, label) = when (row.toolName) {
        "terminal", "bash" -> Icons.Rounded.Terminal to "终端"
        "search", "read", "grep", "glob" -> Icons.Rounded.Search to "查阅"
        "edit", "write", "multiedit" -> Icons.Rounded.Edit to "更改"
        "subagent" -> Icons.Rounded.SmartToy to "子任务"
        else -> Icons.Rounded.Memory to (row.toolName ?: "工具")
    }
    val hasOutput = row.outputText.isNotBlank()

    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasOutput) { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.inputText.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        row.inputText.replace("\n", " "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (row.additions != null && row.additions > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "+${row.additions}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF3FB950),
                    )
                }
                if (hasOutput) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            AnimatedVisibility(visible = expanded && hasOutput) {
                Text(
                    row.outputText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

// ────────────────────────── 发送栏（新版布局） ──────────────────────────

/**
 * 全新发送栏：一个圆角胶囊容器，上输入、下控制条。
 * AI 回复中时输入自动进入队列（官方 queue 语义），停止按钮独立显示。
 */
@Composable
private fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    working: Boolean,
    enabled: Boolean,
    onSend: (queued: Boolean) -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .navigationBarsPadding(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        placeholder = {
                            Text(
                                if (working) "AI 正在回复，输入内容将排队发送"
                                else "继续输入，@ 可引用上下文",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        minLines = 1,
                        maxLines = 5,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 附件入口
                        CircleAction(
                            icon = Icons.Rounded.Add,
                            contentDescription = "附件",
                            onClick = { },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // 权限模式（占位）
                        CircleAction(
                            icon = Icons.Rounded.Check,
                            contentDescription = "权限模式",
                            selected = true,
                            onClick = { },
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // 模型选择（占位）
                        CircleAction(
                            icon = Icons.Rounded.Memory,
                            contentDescription = "模型",
                            onClick = { },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // 深度思考开关
                        var thinkOn by remember { mutableStateOf(true) }
                        CircleAction(
                            icon = Icons.Rounded.Psychology,
                            contentDescription = "深度思考",
                            selected = thinkOn,
                            onClick = { thinkOn = !thinkOn },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // 停止（仅 AI 工作中显示）
                        if (working) {
                            FilledIconButton(
                                onClick = onStop,
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = "停止", modifier = Modifier.size(20.dp))
                            }
                            if (text.isNotBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                QueueSendButton(onClick = { onSend(true) })
                            }
                        } else {
                            // 发送
                            FilledIconButton(
                                onClick = { onSend(false) },
                                enabled = enabled && text.isNotBlank(),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "发送",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 排队发送按钮：AI 回复中时把输入加入队列 */
@Composable
private fun QueueSendButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
        ),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(Icons.Rounded.PlaylistAdd, contentDescription = "排队发送", modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ────────────────────────── 通用小组件 ──────────────────────────

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun SessionRow(title: String, subtitle: String?, highlight: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (highlight) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (highlight) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (highlight) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (highlight) {
                ThinkingDot()
            }
        }
    }
}

/** 处理中的呼吸圆点 */
@Composable
fun ThinkingDot() {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                CircleShape,
            )
    )
}

@Composable
private fun CenterHint(
    icon: @Composable () -> Unit,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) { icon() }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
