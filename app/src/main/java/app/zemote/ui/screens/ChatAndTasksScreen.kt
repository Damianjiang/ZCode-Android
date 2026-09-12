package app.zemote.ui.screens

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
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
        var bootstrapTasks: List<app.zemote.protocol.TaskEntry> = emptyList()
        runCatching { app.zemote.protocol.fetchTasksFromBootstrap(client) }
            .onSuccess {
                bootstrapTasks = it
                tasks = it
                loading = false
            }
            .onFailure {
                error = it.message ?: "无法获取任务列表"
                loading = false
            }
        // 订阅工作区 sessions-index：会话列表实时更新（新增/标题/运行状态），
        // 与 bootstrap 任务按 sessionId 合并（对齐原版 Flutter 双数据源）
        runCatching {
            val repo = session.conversationFor(accountId, workspaceKey, null) ?: return@runCatching
            repo.openSessionsIndex()
            repo.sessionEntries.collect { entries ->
                if (entries.isEmpty()) return@collect
                val byId = bootstrapTasks.associateBy { it.taskId }.toMutableMap()
                for (e in entries) {
                    val old = byId[e.sessionId]
                    byId[e.sessionId] = app.zemote.protocol.TaskEntry(
                        taskId = e.sessionId,
                        title = e.title.ifBlank { old?.title ?: "未命名会话" },
                        status = if (e.running) "running" else e.phase.ifBlank { old?.status },
                        workspacePath = old?.workspacePath,
                        workspaceLabel = old?.workspaceLabel
                            ?: workspaceKey.substringAfterLast('/').ifBlank { workspaceKey },
                        updatedAt = e.lastActivityAt,
                    )
                }
                tasks = byId.values.sortedByDescending { it.updatedAt ?: 0L }
                error = null
                loading = false
            }
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
    var pendingFiles by remember { mutableStateOf(listOf<PendingFile>()) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 系统文件选择器：图片和任意文件均可选，选中即加入待发列表
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@mapNotNull null
                        val name = queryDisplayName(context, uri)
                            ?: uri.lastPathSegment?.substringAfterLast('/')
                            ?: "附件"
                        PendingFile(name, context.contentResolver.getType(uri) ?: guessMime(name), bytes)
                    }.getOrNull()
                }
            }
            pendingFiles = pendingFiles + loaded
        }
    }

    LaunchedEffect(accountId, workspaceKey, sessionId) {
        if (accountId == null) {
            error = "设备未连接"
            return@LaunchedEffect
        }
        runCatching { session.conversationFor(accountId, workspaceKey, sessionId) }
            .onSuccess { repo = it }
            .onFailure { error = it.message ?: "无法打开会话通道" }
    }

    // 会话标题数据源：sessions-index（幂等，重复调用自动跳过）
    LaunchedEffect(repo) {
        runCatching { repo?.openSessionsIndex() }
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
    val modelOptions by (repo?.modelOptions?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val stopWorkId by (repo?.stopWorkId?.collectAsState() ?: remember { mutableStateOf(null) })
    val followupMode by (repo?.followupMode?.collectAsState() ?: remember { mutableStateOf(null) })
    val queueItems by (repo?.queueItems?.collectAsState() ?: remember { mutableStateOf(emptyList<app.zemote.protocol.QueueItem>()) })
    val autoDrain by (repo?.autoDrain?.collectAsState() ?: remember { mutableStateOf(true) })
    val sessionEntries by (repo?.sessionEntries?.collectAsState() ?: remember { mutableStateOf(emptyList<app.zemote.protocol.SessionEntry>()) })

    // 附件内容加载（收到的图片消息按 ref 拉取渲染）
    val loadAttachment: suspend (String) -> app.zemote.protocol.AttachmentData? = { ref ->
        val sid = activeId
        if (sid == null) null
        else runCatching { repo?.attachmentRead(sid, ref) }.getOrNull()
    }

    // 自动跟随开关：开启时新消息与流式增长都贴底，关闭后完全手动
    var autoFollow by remember { mutableStateOf(true) }

    // 工具调用行聚合：连续的 toolCall/subagent 合并为一张「执行过程」卡片，
    // 只显示执行了什么/修改了什么，不直接刷原始 toolcall
    val displayItems = remember(rows) { buildDisplayItems(rows) }

    // 新消息到达（条目数变化）：滚动定位到最新一条
    LaunchedEffect(displayItems.size, historyUnavailable) {
        if (autoFollow && displayItems.isNotEmpty()) {
            historyUnavailable = false
            listState.animateScrollToItem((displayItems.size - 1).coerceAtLeast(0))
        }
    }

    // 流式输出跟随：思考/回复内容增长时条目数不变，按最后一行内容长度触发贴底滚动；
    // 用户上翻阅读历史时（最后一项不可见）暂停跟随，不抢滚动位置
    val lastRowLen = rows.lastOrNull()?.let {
        it.text.length + it.outputText.length + it.inputText.length + it.summaryText.length
    } ?: 0
    LaunchedEffect(lastRowLen, working) {
        if (!autoFollow || rows.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1) {
            listState.animateScrollBy(4000f)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // 会话标题：sessions-index 实时数据（桌面端重命名会跟着更新）；拿不到时回退通用标题
        val sessionTitle = sessionEntries
            .firstOrNull { it.sessionId == activeId }
            ?.title?.trim()?.ifBlank { null }
        ScreenHeader(
            title = when {
                activeId == null -> "新对话"
                sessionTitle != null -> sessionTitle
                else -> "任务会话"
            },
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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
                    items(displayItems, key = { it.key }) { item ->
                        when (item) {
                            is DisplayItem.Single -> {
                                if (item.row.kind == ConvKinds.USER_INPUT) {
                                    TimelineRow(item.row, loadAttachment)
                                } else {
                                    // AI 产生的内容淡入，更灵动
                                    FadeInContainer(item.key) {
                                        TimelineRow(item.row, loadAttachment)
                                    }
                                }
                            }
                            is DisplayItem.ToolGroup -> FadeInContainer(item.key) {
                                ToolGroupCard(item.rows)
                            }
                        }
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

                // 自动跟随开关：亮 = 跟随最新内容，暗 = 手动浏览
                IconToggleButton(
                    checked = autoFollow,
                    onCheckedChange = { autoFollow = it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp)
                        .size(34.dp),
                ) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        contentDescription = if (autoFollow) "自动跟随已开启" else "自动跟随已关闭",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 排队消息卡片：AI 工作中发送的内容进入队列，可立即发送/编辑/删除（官方队列语义）
            QueueBar(
                items = queueItems,
                autoDrain = autoDrain,
                onSendNow = { id -> scope.launch { runCatching { repo?.sendQueuedNow(id) } } },
                onEdit = { id, text -> scope.launch { runCatching { repo?.editQueueItem(id, text) } } },
                onDelete = { id -> scope.launch { runCatching { repo?.deleteQueueItem(id) } } },
                onToggleAutoDrain = { on -> scope.launch { runCatching { repo?.setAutoDrain(on) } } },
                onReorder = { ids -> scope.launch { runCatching { repo?.reorderQueueItem(ids) } } },
            )

            if (pendingFiles.isNotEmpty() || uploadStatus != null) {
                PendingFilesBar(
                    files = pendingFiles,
                    status = uploadStatus,
                    onRemove = { f -> pendingFiles = pendingFiles - f },
                )
            }

            ComposerBar(
                text = input,
                onTextChange = { input = it },
                working = working,
                enabled = repo != null && error == null,
                config = convConfig,
                usage = usage,
                modelOptions = modelOptions,
                stopWorkId = stopWorkId,
                followupMode = followupMode,
                onAttach = { pickFiles.launch(arrayOf("*/*")) },
                onThoughtSelect = { level ->
                    scope.launch { runCatching { repo?.setThought(level) } }
                },
                onModelSelect = { provider, model ->
                    scope.launch { runCatching { repo?.setModel(provider, model) } }
                },
                onSend = { queued ->
                    val text = input
                    val files = pendingFiles
                    input = ""
                    autoFollow = true
                    scope.launch {
                        runCatching {
                            val repo0 = repo ?: return@launch
                            var target = activeId
                            if (files.isNotEmpty()) {
                                // 官方路径：附件需先有 sessionId 才能上传 →
                                // createSession → attachmentPut → sendText(attachments)
                                if (target == null) {
                                    target = repo0.createSession()
                                        ?: throw IllegalStateException("创建会话失败")
                                }
                                val descriptors = mutableListOf<Map<String, Any?>>()
                                files.forEachIndexed { i, f ->
                                    uploadStatus = "正在上传附件 ${i + 1}/${files.size}"
                                    val up = repo0.attachmentPut(target, f.name, f.mime, f.bytes)
                                    if (up.ref.isNullOrBlank()) throw IllegalStateException("附件上传失败：${f.name}")
                                    descriptors.add(mapOf(
                                        "ref" to up.ref,
                                        "fileName" to up.fileName,
                                        "mime" to up.mime,
                                        "bytes" to up.bytes,
                                    ))
                                }
                                uploadStatus = null
                                pendingFiles = emptyList()
                                repo0.sendText(text, target, attachments = descriptors)
                            } else {
                                // AI 回复中 → 官方 queue 语义（排队）；空闲 → startNow。
                                // 后续更新完全由订阅帧（row.appended / row.delta）推送，不做轮询
                                repo0.sendText(
                                    text,
                                    target,
                                    requestedDelivery = if (queued) "queue" else "startNow",
                                )
                            }
                        }.onFailure {
                            input = text
                            pendingFiles = files
                            uploadStatus = null
                        }
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
private fun TimelineRow(row: ConvRow, loadAttachment: suspend (String) -> app.zemote.protocol.AttachmentData?) {
    when (row.kind) {
        ConvKinds.USER_INPUT -> UserBubble(row, loadAttachment)
        ConvKinds.ASSISTANT_TEXT -> if (row.text.isNotBlank()) {
            app.zemote.ui.components.MarkdownText(
                markdown = row.text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ConvKinds.REASONING -> if (row.text.isNotBlank()) ThinkingBlock(row)
        // 工具调用统一走「执行过程」汇总卡片（正常路径由 buildDisplayItems 聚合，
        // 此处兜底处理未聚合的单条）
        ConvKinds.TOOL_CALL -> ToolGroupCard(listOf(row))
        ConvKinds.SUBAGENT -> if (row.summaryText.isNotBlank() || row.text.isNotBlank()) {
            ToolGroupCard(listOf(row.copy(toolName = "subagent", inputText = row.summaryText.ifBlank { row.text })))
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
private fun UserBubble(row: ConvRow, loadAttachment: suspend (String) -> app.zemote.protocol.AttachmentData?) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (row.text.isNotBlank() || row.inputText.isNotBlank() || row.attachments.isEmpty()) {
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
        row.attachments.forEach { att ->
            Spacer(modifier = Modifier.height(6.dp))
            if (att["mime"]?.startsWith("image/") == true) {
                ImageAttachmentView(
                    ref = att["ref"].orEmpty(),
                    fileName = att["fileName"] ?: "图片",
                    loadAttachment = loadAttachment,
                )
            } else {
                AttachmentChip(fileName = att["fileName"] ?: "附件")
            }
        }
    }
}

/** 消息内的文件附件 chip */
@Composable
private fun AttachmentChip(fileName: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

/** 消息内的图片附件：按 ref 经 attachmentReadV4 拉取后渲染，绝不破图 */
@Composable
private fun ImageAttachmentView(
    ref: String,
    fileName: String,
    loadAttachment: suspend (String) -> app.zemote.protocol.AttachmentData?,
) {
    if (ref.isEmpty()) {
        AttachmentChip(fileName)
        return
    }
    var bitmap by remember(ref) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(ref) {
        val data = runCatching { loadAttachment(ref) }.getOrNull()
        val bmp = data?.bytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
        if (bmp != null) bitmap = bmp else failed = true
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = fileName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .padding(4.dp),
            )
            failed -> Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    fileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
            else -> Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "图片加载中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ────────────────────────── 排队消息 ──────────────────────────

/** 排队消息卡片：对齐官方 web 队列展示（立即发送 / 编辑 / 删除 / 拖动排序 + 自动发送开关） */
@Composable
private fun QueueBar(
    items: List<app.zemote.protocol.QueueItem>,
    autoDrain: Boolean,
    onSendNow: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleAutoDrain: (Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    if (items.isEmpty()) return
    var editTarget by remember { mutableStateOf<app.zemote.protocol.QueueItem?>(null) }
    var deleteTarget by remember { mutableStateOf<app.zemote.protocol.QueueItem?>(null) }

    // 本地顺序：拖动过程即时重排（乐观更新），松手后发 reorderQueueItem 由服务端确认
    var order by remember(items) { mutableStateOf(items) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val rowHeight = 34.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "排队消息 ${order.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (autoDrain) "自动发送：开" else "自动发送：关",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onToggleAutoDrain(!autoDrain) },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            order.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .zIndex(if (dragId == item.queueItemId) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragId == item.queueItemId) dragOffsetY else 0f
                        }
                        .pointerInput(item.queueItemId, order.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragId = item.queueItemId
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    dragOffsetY += drag.y
                                    var i = order.indexOfFirst { it.queueItemId == dragId }
                                    if (i < 0) return@detectDragGesturesAfterLongPress
                                    // 拖过相邻行高度就交换位置
                                    while (dragOffsetY > rowHeightPx && i < order.lastIndex) {
                                        order = order.toMutableList().apply {
                                            add(i + 1, removeAt(i))
                                        }
                                        i++
                                        dragOffsetY -= rowHeightPx
                                    }
                                    while (dragOffsetY < -rowHeightPx && i > 0) {
                                        order = order.toMutableList().apply {
                                            add(i - 1, removeAt(i))
                                        }
                                        i--
                                        dragOffsetY += rowHeightPx
                                    }
                                },
                                onDragEnd = {
                                    onReorder(order.map { it.queueItemId })
                                    dragId = null
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    dragId = null
                                    dragOffsetY = 0f
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        contentDescription = "拖动排序",
                        tint = if (dragId == item.queueItemId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "${index + 1}. ${item.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onSendNow(item.queueItemId) }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "立即发送",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = { editTarget = item }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(onClick = { deleteTarget = item }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        var editText by remember(target.queueItemId) { mutableStateOf(target.text) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑排队消息") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = editText.trim()
                    editTarget = null
                    if (t.isNotEmpty()) onEdit(target.queueItemId, t)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除排队消息？") },
            text = { Text(target.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(target.queueItemId)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

// ────────────────────────── 附件选择 ──────────────────────────

/** 待发送的附件（已在本地读入内存） */
private data class PendingFile(val name: String, val mime: String, val bytes: ByteArray)

/** 待发附件条：发送栏上方展示已选文件/上传进度 */
@Composable
private fun PendingFilesBar(
    files: List<PendingFile>,
    status: String?,
    onRemove: (PendingFile) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        files.forEach { f ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        f.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    IconButton(onClick = { onRemove(f) }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "移除",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** SAF 查询文件显示名 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** 扩展名 → MIME 兜底（contentResolver.getType 为空时使用，对齐官方猜测表） */
private fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

/** 思考块：流式输出中自动展开，完成后自动折叠；用户手动切换后不再自动干预（无展开动画，直切） */
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
            // 展开/收起直切，无过渡动画
            if (expanded) {
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

/**
 * 「执行过程」卡片：把原始 toolcall 过滤成人话摘要——执行了什么命令、修改了哪个文件。
 * 默认只显示每步一句话；点击展开可看各步原始输出。
 */
@Composable
private fun ToolGroupCard(rows: List<ConvRow>) {
    var expanded by remember(rows.firstOrNull()?.rowId) { mutableStateOf(false) }
    val anyRunning = rows.any {
        it.toolStatus == null || it.toolStatus == "running" || it.toolStatus == "pending"
    }
    val anyFailed = rows.any { it.toolStatus == "error" }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "执行过程",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (rows.size > 1) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "${rows.size} 步",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (anyRunning) {
                    ThinkingDot()
                } else if (anyFailed) {
                    Text("有失败", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            // 每步一句：执行了什么 / 修改了什么（不做动画，直切）
            rows.forEach { row ->
                val stepRunning = row.toolStatus == null || row.toolStatus == "running" || row.toolStatus == "pending"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        toolSentence(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (stepRunning) {
                        Spacer(modifier = Modifier.width(6.dp))
                        ThinkingDot()
                    } else if (row.toolStatus == "error") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (row.additions != null && row.additions > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "+${row.additions}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF3FB950),
                        )
                    }
                }
                if (expanded && row.outputText.isNotBlank()) {
                    Text(
                        row.outputText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp, bottom = 2.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 把一步工具调用翻译成一句人话：执行了命令 xxx / 修改了 MainActivity.kt / 读取了 … */
private fun toolSentence(row: ConvRow): String {
    val name = row.toolName?.lowercase()
    val inputSrc = row.inputText.ifBlank { row.summaryText }
    val target = summarizeToolInput(name, inputSrc).ifBlank { row.text }
    val what = when (name) {
        "terminal", "bash", "run_command" -> "执行了命令"
        "edit" -> "修改了"
        "write" -> "写入了"
        "multiedit", "notebookedit" -> "批量修改了"
        "read" -> "读取了"
        "search", "grep", "glob", "websearch", "web_fetch" -> "搜索了"
        "task", "subagent" -> "子任务"
        null -> "调用了工具"
        else -> "调用了 ${row.toolName}"
    }
    return if (target.isBlank()) what else "$what $target"
}

/** 时间线显示项：普通行单条展示，连续的工具行聚合为一组 */
private sealed interface DisplayItem {
    val key: String

    data class Single(val row: ConvRow) : DisplayItem {
        override val key get() = "r-${row.rowId}"
    }

    data class ToolGroup(val rows: List<ConvRow>) : DisplayItem {
        override val key get() = "g-${rows.first().rowId}-${rows.last().rowId}"
    }
}

private fun buildDisplayItems(rows: List<ConvRow>): List<DisplayItem> {
    val out = mutableListOf<DisplayItem>()
    val group = mutableListOf<ConvRow>()
    fun flush() {
        if (group.isNotEmpty()) {
            out.add(DisplayItem.ToolGroup(group.toList()))
            group.clear()
        }
    }
    for (row in rows) {
        val isTool = row.kind == ConvKinds.TOOL_CALL || row.kind == ConvKinds.SUBAGENT
        if (isTool) {
            group.add(row)
        } else {
            flush()
            out.add(DisplayItem.Single(row))
        }
    }
    flush()
    return out
}

/** AI 消息淡入容器：首次进入组合时从透明渐变到完全不透明 */
@Composable
private fun FadeInContainer(key: Any?, content: @Composable () -> Unit) {
    var shown by remember(key) { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 260),
        label = "aiFadeIn",
    )
    LaunchedEffect(key) { shown = true }
    Box(
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    ) {
        content()
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
 * 全新发送栏：上输入框，下控制条。
 * 控制条从左到右：附件 · 思考等级 · 模型 · 上下文 · 停止/排队/发送。
 */
@Composable
private fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    working: Boolean,
    enabled: Boolean,
    config: app.zemote.protocol.ConvConfig?,
    usage: app.zemote.protocol.ConvUsage?,
    modelOptions: List<app.zemote.protocol.ModelOption>,
    stopWorkId: String?,
    followupMode: String?,
    onAttach: () -> Unit,
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
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            // ── 输入框 ──
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            if (working) "AI 正在回复，内容将排队发送"
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
                    // 回车换行，发送走右侧按钮（多行输入）
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // ── 控制条 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // ── 左侧：附件 + 思考等级 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AttachmentButton(onClick = onAttach)
                    Spacer(modifier = Modifier.width(6.dp))
                    ThoughtLevelButton(
                        config = config,
                        onSelect = onThoughtSelect,
                    )
                }

                // ── 中间：模型 + 上下文 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelButton(
                        config = config,
                        modelOptions = modelOptions,
                        onSelect = onModelSelect,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    UsageButton(usage = usage)
                }

                // ── 右侧：停止 / 排队 / 发送 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (working) {
                        StopButton(onClick = onStop, workId = stopWorkId)
                        if (text.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            QueueButton(onClick = { onSend(true) })
                        }
                    } else {
                        SendButton(
                            enabled = enabled && text.isNotBlank(),
                            onClick = { onSend(false) },
                        )
                    }
                }
            }
        }
    }
}

// ────────────────────────── 按钮组件 ──────────────────────────

/** 附件按钮：加号图标，唤起系统文件选择器（图片/任意文件） */
@Composable
private fun AttachmentButton(onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = "附件", modifier = Modifier.size(18.dp))
    }
}

/** 思考等级按钮：弹出菜单显示所有可选等级 */
@Composable
private fun ThoughtLevelButton(
    config: app.zemote.protocol.ConvConfig?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = config?.thought
    val levels = config?.thoughtLevels ?: emptyList()

    if (levels.isEmpty()) return // 当前模型不支持思考，不显示按钮

    Box {
        FilledTonalIconButton(
            onClick = { open = true },
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Icon(
                Icons.Rounded.Psychology,
                contentDescription = "思考等级",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(17.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (level in levels) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(thoughtLabel(level), style = MaterialTheme.typography.bodyMedium)
                            if (level == current) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        }
                    },
                    onClick = { open = false; onSelect(level) },
                )
            }
        }
    }
}

/** 模型按钮：弹出菜单显示所有可用模型 */
@Composable
private fun ModelButton(
    config: app.zemote.protocol.ConvConfig?,
    modelOptions: List<app.zemote.protocol.ModelOption>,
    onSelect: (provider: String, model: String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentProvider = config?.provider
    val currentModel = config?.model

    val label = if (currentModel != null) modelLabel(currentProvider ?: "", currentModel) else "模型"

    Box {
        FilledTonalIconButton(
            onClick = { open = true },
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Icon(
                Icons.Rounded.Memory,
                contentDescription = "模型",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (modelOptions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("暂无可用模型", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false },
                )
            } else {
                for (opt in modelOptions) {
                    val isSelected = opt.provider == currentProvider && opt.model == currentModel
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                            }
                        },
                        onClick = { open = false; onSelect(opt.provider, opt.model) },
                    )
                }
            }
        }
    }
}

/** 上下文用量按钮：显示百分比进度条，点击弹出明细 */
@Composable
private fun UsageButton(usage: app.zemote.protocol.ConvUsage?) {
    var open by remember { mutableStateOf(false) }
    if (usage == null || usage.maxTokens == 0L) return

    val ratio = usage.ratio.coerceIn(0f, 1f)
    val color = when {
        ratio < 0.5f -> MaterialTheme.colorScheme.primary
        ratio < 0.8f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Box {
        FilledTonalIconButton(
            onClick = { open = true },
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            // M3 饼图图标，颜色随用量分档
            Icon(
                Icons.Rounded.PieChart,
                contentDescription = "上下文用量",
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        "${formatToken(usage.usedTokens)} / ${formatToken(usage.maxTokens)} tokens",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = { open = false },
            )
            if (usage.hitRate != null) {
                DropdownMenuItem(
                    text = { Text("缓存命中率: ${(usage.hitRate * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false },
                )
            }
            for ((source, chars) in usage.breakdown) {
                DropdownMenuItem(
                    text = {
                        Text("${sourceLabel(source)}: ${(chars / 1000).toInt()}k 字符", style = MaterialTheme.typography.bodySmall)
                    },
                    onClick = { open = false },
                )
            }
        }
    }
}

/** 停止按钮：红色圆形，AI 工作中显示 */
@Composable
private fun StopButton(onClick: () -> Unit, workId: String? = null) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(Icons.Rounded.Stop, contentDescription = "停止", modifier = Modifier.size(18.dp))
    }
}

/** 排队发送按钮：AI 工作中时把当前输入加入队列 */
@Composable
private fun QueueButton(onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("排队", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 发送按钮：绿色主色，空闲时显示 */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
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

// ────────────────────────── 辅助函数 ──────────────────────────

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

/** 来源名称中文映射 */
private fun sourceLabel(source: String): String = when (source) {
    "messages" -> "消息"
    "system_tool_schemas" -> "系统工具"
    "mcp_tool_schemas" -> "MCP 工具"
    "system_prompt" -> "系统提示词"
    "skills" -> "技能"
    "meta_user_context" -> "其他"
    else -> source
}

/** token 数值格式化 */
private fun formatToken(n: Long): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

/** 模型显示名称（带 provider 前缀，便于区分不同提供商的同名模型） */
private fun modelLabel(provider: String, model: String): String {
    val p = when (provider.lowercase()) {
        "zai", "zcode" -> "ZAI"
        "glm" -> "GLM"
        "deepseek" -> "DeepSeek"
        "anthropic" -> "Anthropic"
        "openai" -> "OpenAI"
        "qwen" -> "Qwen"
        "moonshot" -> "Moonshot"
        else -> provider
    }
    return "$p · $model"
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
