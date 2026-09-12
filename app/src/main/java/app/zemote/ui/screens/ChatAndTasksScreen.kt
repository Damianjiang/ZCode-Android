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
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
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
    onOpenSession: (entry: app.zemote.protocol.TaskEntry?) -> Unit,
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
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
                            onClick = { onOpenSession(entry) },
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
                            onClick = { onOpenSession(entry) },
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
        // 历史行 8s 仍未到达 → 该会话暂无可见消息
        kotlinx.coroutines.delay(5000)
        if (repo?.rows?.value.isNullOrEmpty()) historyUnavailable = true
        else historyUnavailable = false
    }

    val rows by (repo?.rows?.collectAsState() ?: remember { mutableStateOf(emptyList<ConvRow>()) })
    val working by (repo?.agentWorking?.collectAsState() ?: remember { mutableStateOf(false) })
    val loading by (repo?.loading?.collectAsState() ?: remember { mutableStateOf(false) })
    val convConfig by (repo?.convConfig?.collectAsState() ?: remember { mutableStateOf(null) })
    val usage by (repo?.usage?.collectAsState() ?: remember { mutableStateOf(null) })
    val activeId by (repo?.activeSessionId?.collectAsState() ?: remember { mutableStateOf(sessionId) })

    // 新消息自动滚到底部（+1 偏移：空态提示占一个 item 位）
    LaunchedEffect(rows.size, historyUnavailable) {
        if (rows.isNotEmpty()) {
            historyUnavailable = false
            val target = rows.size - 1 + if (historyUnavailable) 1 else 0
            listState.animateScrollToItem(target.coerceAtLeast(0))
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
                if (loading && rows.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 90.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                "正在加载对话…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (historyUnavailable) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "该会话暂无可见消息。\n可以直接在下方输入框发起新话题。",
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
                config = convConfig,
                usage = usage,
                onThoughtSelect = { level ->
                    scope.launch { runCatching { repo?.setThought(level) } }
                },
                onModelSelect = { provider, model ->
                    scope.launch { runCatching { repo?.setModel(provider, model) } }
                },
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
                            // 实时帧兜底：发送后短轮询刷新，确保新回合尽快可见
                            val repo0 = repo ?: return@launch
                            repeat(3) {
                                kotlinx.coroutines.delay(2500)
                                runCatching { repo0.loadRows(repo0.activeSessionId.value ?: return@repeat) }
                            }
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
            app.zemote.ui.components.MarkdownText(
                markdown = row.text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ConvKinds.REASONING -> if (row.text.isNotBlank()) ThinkingBlock(row)
        ConvKinds.TOOL_CALL -> ToolCallBlock(row)
        ConvKinds.SUBAGENT -> if (row.summaryText.isNotBlank() || row.text.isNotBlank()) {
            ToolCallBlock(row.copy(toolName = "subagent", inputText = row.summaryText.ifBlank { row.text }))
        }
        // 图片类消息：占位卡片展示，绝不出现加载失败的破图
        ConvKinds.IMAGE, "screenshot" -> ImagePlaceholder(row)
        else -> Unit
    }
}

/** 图片占位卡片（后续接入图片传输后在此渲染真实内容） */
@Composable
private fun ImagePlaceholder(row: ConvRow) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                row.text.ifBlank { "图片消息" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/** 思考块：流式输出中自动展开，完成后自动折叠；用户手动切换后不再自动干预 */
@Composable
private fun ThinkingBlock(row: ConvRow) {
    val streaming = row.state == null || row.state !in app.zemote.protocol.COMPLETE_STATES
    var expanded by remember(row.rowId) { mutableStateOf(true) }
    var userToggled by remember(row.rowId) { mutableStateOf(false) }
    LaunchedEffect(streaming) {
        if (!userToggled) expanded = streaming
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                userToggled = true
                expanded = !expanded
            },
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
                    if (streaming) "思考中…" else "思考",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (streaming) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ThinkingDot()
                }
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

/** 工具调用卡片：调用工具 · 工具名 + 目标（文件名/描述），输出默认折叠 */
@Composable
private fun ToolCallBlock(row: ConvRow) {
    var expanded by remember(row.rowId) { mutableStateOf(false) }
    val name = row.toolName?.lowercase()
    val (icon, label) = when (name) {
        "terminal", "bash", "run_command" -> Icons.Rounded.Terminal to "终端"
        "read" -> Icons.Rounded.Search to "读取"
        "search", "grep", "glob", "websearch", "web_fetch" -> Icons.Rounded.Search to "查阅"
        "edit" -> Icons.Rounded.Edit to "编辑"
        "write" -> Icons.Rounded.Edit to "写入"
        "multiedit", "notebookedit" -> Icons.Rounded.Edit to "批量编辑"
        "task", "subagent" -> Icons.Rounded.SmartToy to "子任务"
        null -> Icons.Rounded.Memory to "工具"
        else -> Icons.Rounded.Memory to (row.toolName ?: "工具")
    }
    // inputText 官方是 JSON（如 {"command":..., "description":...} / {"filePath":...}）
    val summary = remember(row.rowId, row.inputText) { summarizeToolInput(name, row.inputText) }
    val running = row.toolStatus == null || row.toolStatus == "running" || row.toolStatus == "pending"
    val hasOutput = row.outputText.isNotBlank()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasOutput) {
                expanded = !expanded
            },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (summary.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (running) {
                    Spacer(modifier = Modifier.width(6.dp))
                    ThinkingDot()
                } else if (row.toolStatus == "error") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("失败", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
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
                    Spacer(modifier = Modifier.width(4.dp))
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

/**
 * 从官方 toolCall 的 inputText（JSON）提取摘要：
 * 文件类工具 → 文件名；bash/终端 → 命令描述（不显示文件名）；其余 → 原始一行。
 */
private fun summarizeToolInput(tool: String?, raw: String): String {
    if (raw.isBlank()) return ""
    if (!raw.startsWith("{")) return raw.substringBefore('\n')
    return try {
        val obj = org.json.JSONObject(raw)
        when (tool) {
            "edit", "write", "read", "multiedit", "notebookedit" ->
                fileNameOf(obj.optString("filePath").ifBlank {
                    obj.optString("file_path").ifBlank { obj.optString("notebook_path") }
                }).ifBlank { obj.optString("description") }
            else ->
                obj.optString("description").ifBlank {
                    obj.optString("command").ifBlank {
                        obj.optString("pattern").ifBlank { obj.optString("query").ifBlank { raw } }
                    }
                }
        }
    } catch (_: Exception) {
        raw.substringBefore('\n')
    }
}

private fun fileNameOf(path: String): String =
    path.substringAfterLast('\\').substringAfterLast('/').trim()

// ────────────────────────── 发送栏（官方功能布局） ──────────────────────────

/**
 * 全新发送栏：一个圆角胶囊容器，上输入、下控制条。
 * 左侧：上下文容量 / 选择模型 / 思考等级（均来自官方 V4 状态帧）；
 * 右侧：排队发送 / 停止 / 发送。AI 回复中时输入自动进入队列（官方 queue 语义）。
 */
@Composable
private fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    working: Boolean,
    enabled: Boolean,
    config: app.zemote.protocol.ConvConfig?,
    usage: app.zemote.protocol.ConvUsage?,
    onThoughtSelect: (String) -> Unit,
    onModelSelect: (provider: String, model: String) -> Unit,
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
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 上下文容量（官方「上下文容量」弹窗）
                        ContextUsageChip(usage)
                        Spacer(modifier = Modifier.width(8.dp))
                        // 选择模型（官方模型菜单；未知其他模型时仅展示当前项）
                        ModelChip(config, onModelSelect)
                        Spacer(modifier = Modifier.width(8.dp))
                        // 思考等级（低/中/高/最高；模型不支持时自动隐藏）
                        ThoughtChip(config, onThoughtSelect)
                        Spacer(modifier = Modifier.weight(1f))
                        // 停止（仅 AI 工作中显示）
                        if (working) {
                            FilledIconButton(
                                onClick = onStop,
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = "停止", modifier = Modifier.size(18.dp))
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
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "发送",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 思考等级中文标签（官方 thought 取值） */
private fun thoughtLabel(level: String): String = when (level.lowercase()) {
    "off", "none", "disabled" -> "关闭"
    "nothink", "no-think", "no_think" -> "不思考"
    "on", "enabled" -> "开启"
    "low", "light", "minimal", "shallow" -> "低"
    "medium", "balanced", "default" -> "中"
    "high" -> "高"
    "xhigh", "extra-high", "extra_high", "very-high", "very_high" -> "超高"
    "max", "maximum" -> "最高"
    else -> level
}

/** 思考等级选择：选项来自模型的 thoughtLevels；不支持思考的模型自动隐藏 */
@Composable
private fun ThoughtChip(config: app.zemote.protocol.ConvConfig?, onThoughtSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (config == null || !config.thoughtSupported) return
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Psychology,
                    contentDescription = "思考等级",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    thoughtLabel(config.thought ?: config.thoughtLevels.last()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (level in config.thoughtLevels) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(thoughtLabel(level))
                            Spacer(modifier = Modifier.weight(1f))
                            if (level == config.thought) {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    onClick = {
                        open = false
                        onThoughtSelect(level)
                    },
                )
            }
        }
    }
}

/** 模型选择：展示当前模型；管理模型入口在移动端隐藏 */
@Composable
private fun ModelChip(
    config: app.zemote.protocol.ConvConfig?,
    onModelSelect: (provider: String, model: String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    if (config?.model.isNullOrBlank()) return
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = "选择模型",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    config?.model ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(config?.model ?: "")
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                onClick = { open = false },
            )
            DropdownMenuItem(
                text = { Text("切换思考等级请点右侧按钮", style = MaterialTheme.typography.labelSmall) },
                onClick = {},
                enabled = false,
            )
        }
    }
}

/** 上下文容量：显示百分比，点击弹出官方样式明细（消息/系统工具/MCP 工具/系统提示词/技能/其他 + 缓存命中率） */
@Composable
private fun ContextUsageChip(usage: app.zemote.protocol.ConvUsage?) {
    var open by remember { mutableStateOf(false) }
    if (usage == null || usage.maxTokens <= 0L) return
    val pct = (usage.ratio * 100).coerceIn(0f, 100f)
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.DataUsage,
                    contentDescription = "上下文容量",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "${pct.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).width(252.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "上下文容量",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${usage.usedTokens / 10000}万/${usage.maxTokens / 10000}万（${pct.toInt()}%）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val total = usage.breakdown.sumOf { it.second }.coerceAtLeast(1L)
                for ((source, chars) in usage.breakdown.sortedByDescending { it.second }) {
                    val label = sourceLabel(source)
                    val share = (chars.toDouble() / total * 100)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Circle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = (0.35f + 0.65f * share / 100.0f).toFloat()),
                            modifier = Modifier.size(9.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            if (share >= 1.0) "${share.toInt()}%" else "0%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (usage.hitRate != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "平均缓存命中率",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "${(usage.hitRate * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "messages" -> "消息"
    "system_tool_schemas" -> "系统工具"
    "mcp_tool_schemas" -> "MCP 工具"
    "system_prompt" -> "系统提示词"
    "skills" -> "技能"
    "meta_user_context" -> "其他"
    else -> source
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
