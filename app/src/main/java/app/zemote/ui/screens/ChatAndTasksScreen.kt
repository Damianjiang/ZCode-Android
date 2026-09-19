package app.zemote.ui.screens

import app.zemote.R

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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import app.zemote.protocol.PendingInteraction
import app.zemote.protocol.BackgroundWork
import app.zemote.protocol.TaskEntry
import app.zemote.state.AppSessionViewModel
import app.zemote.state.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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

    val unnamedSessionText = stringResource(R.string.unnamed_session)
    val notConnectedText = stringResource(R.string.device_not_connected)
    val fetchFailedText = stringResource(R.string.fetch_tasks_failed)

    LaunchedEffect(accountId, workspaceKey) {
        val client = accountId?.let { session.clientOf(it) }
        if (client == null) {
            error = notConnectedText
            loading = false
            return@LaunchedEffect
        }
        var bootstrapTasks: List<app.zemote.protocol.TaskEntry> = emptyList()
        runCatching { app.zemote.protocol.fetchTasksFromBootstrap(client, workspaceKey) }
            .onSuccess {
                bootstrapTasks = it
                tasks = it
                loading = false
            }
            .onFailure {
                error = it.message ?: fetchFailedText
                loading = false
            }
        // 订阅工作区 sessions-index：会话列表实时更新（新增/标题/运行状态），
        // 与 bootstrap 任务按 sessionId 合并（对齐原版 Flutter 双数据源）
        runCatching {
            val repo = session.conversationFor(accountId, workspaceKey) ?: return@runCatching
            repo.openSessionsIndex()
            repo.sessionEntries.collect { entries ->
                if (entries.isEmpty()) return@collect
                val byId = bootstrapTasks.associateBy { it.taskId }.toMutableMap()
                for (e in entries) {
                    val old = byId[e.sessionId]
                    byId[e.sessionId] = app.zemote.protocol.TaskEntry(
                        taskId = e.sessionId,
                        title = e.title.ifBlank { old?.title ?: unnamedSessionText },
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
        ScreenHeader(title = stringResource(R.string.sessions_title), onBack = onBack)

        when {
            error != null -> CenterHint(
                icon = { Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp)) },
                title = stringResource(R.string.fetch_sessions_failed),
                body = error,
            )
            loading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(14.dp))
                Text(stringResource(R.string.fetching_sessions), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 新建对话按钮放在最顶部，方便点击
                item {
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
                                stringResource(R.string.start_new_chat),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                if (running.isNotEmpty()) {
                    item { SectionText(stringResource(R.string.running_section)) }
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
                    item { SectionText(stringResource(R.string.history_section)) }
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
                            title = stringResource(R.string.no_sessions),
                            body = stringResource(R.string.no_sessions_hint),
                            modifier = Modifier.padding(top = 80.dp),
                        )
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
    onOpenSubagent: (String, String, String) -> Unit = { _, _, _ -> },
    readOnly: Boolean = false,
) {
    val accountId = session.activeId
    var repo by remember { mutableStateOf<app.zemote.protocol.ConversationV4Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    // sessionId 变化时重置上传状态和文件列表，避免跨会话残留
    var pendingFiles by remember(sessionId) { mutableStateOf(listOf<PendingFile>()) }
    var uploadStatus by remember(sessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // 自动跟随开关：留在这一层（低频状态，发送时置位），时间线内部只读不写。
    // 这里刻意用显式 MutableState 而不是 `by remember { mutableStateOf(...) }`：
    // 只有拿到那个 State 实例，才能 remember 出一个**实例稳定**的 setter lambda
    // 传给 MessageTimeline（否则每次重组都是新 lambda，参数恒不相等 → 无法跳过重组）。
    val autoFollowState = remember { mutableStateOf(true) }
    var autoFollow by autoFollowState
    val onToggleAutoFollow: (Boolean) -> Unit = remember { { v: Boolean -> autoFollowState.value = v } }
    // 发送失败提示（服务端拒绝 / 上传失败），短暂展示后自动消失
    var sendError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sendError) {
        if (sendError != null) {
            delay(5_000)
            sendError = null
        }
    }

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
                            ?: context.getString(R.string.attach)
                        PendingFile(name, context.contentResolver.getType(uri) ?: guessMime(name), bytes)
                    }.getOrNull()
                }
            }
            pendingFiles = pendingFiles + loaded
        }
    }

    val deviceNotConnectedText = stringResource(R.string.device_not_connected)
    val deviceNotConnectedRetryText = stringResource(R.string.device_not_connected_retry)

    // 打开会话（含自愈）：设备未就绪重试 → 打开 → 若 4 秒后历史/模型仍为空，
    // 说明这条桥在服务端已失效（如被抢占后遗留），销毁重建整条通道再试一次
    LaunchedEffect(accountId, workspaceKey, sessionId) {
        if (accountId == null) {
            error = deviceNotConnectedText
            return@LaunchedEffect
        }
        // 快速进入时设备可能仍在重连（connections 里还没有 client），有限次重试而不是永久空白
        var opened: app.zemote.protocol.ConversationV4Session? = null
        for (attempt in 1..5) {
            opened = runCatching { session.conversationFor(accountId, workspaceKey) }
                .getOrNull()
            if (opened != null) break
            delay(1000)
        }
        if (opened == null) {
            error = deviceNotConnectedRetryText
            return@LaunchedEffect
        }
        repo = opened
        // 立即启动异步加载（握手+订阅+历史），不阻塞界面渲染
        // 用 scope.launch 而非裸 launch：composable 销毁时自动取消，防止泄漏
        scope.launch { opened.openConversation(sessionId) }
        scope.launch { runCatching { opened.openSessionsIndex() } }
    }

    // 注意：这里刻意不订阅 repo.rows——行数据的订阅放在 MessageTimeline 内部，
    // 否则每个流式 token 都会让整个聊天页（顶栏 / 输入栏 / 队列卡片）一起重组。
    //
    // 同理，只在这里订阅「页面骨架真正需要」的低频状态。config / usage / modelOptions /
    // stopWorkId / followupMode / queueItems / autoDrain 全部下沉到 ComposerSection，
    // 因为其中 `usage` 在流式输出期间会随每个 `state.updated` 补丁更新（约 16 次/秒），
    // 订阅在这一层等于每帧都把整个聊天页（含 MessageTimeline 与所有可见消息）重组一遍
    // ——这是「UI 卡顿、交互响应迟缓」的主要根因。
    val working by (repo?.agentWorking?.collectAsState() ?: remember { mutableStateOf(false) })
    val historyState by (repo?.historyState?.collectAsState()
        ?: remember { mutableStateOf(app.zemote.protocol.HistoryState.LOADING) })
    val activeId by (repo?.activeSessionId?.collectAsState() ?: remember { mutableStateOf(sessionId) })

    // 任务面板开关（面板内部自己订阅 pendingInteractions / backgroundWorks）
    var showTaskPanel by remember { mutableStateOf(false) }

    // 附件内容加载（收到的图片消息按 ref 拉取渲染）。
    // 这个 lambda 会一路传到每个可见的时间线条目，**必须实例稳定**：
    // 用 rememberUpdatedState 持有最新的 repo / activeId，再 remember 出唯一的 lambda 实例。
    // 旧实现每次重组都新建 lambda，MessageTimeline 参数恒不相等 → 永远无法跳过重组，
    // 所有可见消息都会跟着重组（Markdown 文本重新布局），卡顿被进一步放大。
    val repoState = rememberUpdatedState(repo)
    val activeIdState = rememberUpdatedState(activeId)
    val loadAttachment: suspend (String) -> app.zemote.protocol.AttachmentData? = remember {
        { ref ->
            val r = repoState.value
            val sid = activeIdState.value
            if (r == null || sid == null) null
            else runCatching { r.attachmentRead(sid, ref) }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // 顶栏三块各自独立订阅，互不牵连：
        //   标题   → sessionEntries（sessions-index 推送）
        //   右侧按钮 → pendingInteractions / backgroundWorks
        // 它们原先都订阅在 ChatScreen 顶层，任何一次会话列表刷新、后台任务状态变化
        // 都会把整页（含时间线与所有可见消息）拖进重组。
        ScreenHeader(
            titleContent = {
                ChatHeaderTitle(
                    repo = repo,
                    activeId = activeId,
                    newChatTitle = stringResource(R.string.new_chat),
                    fallbackTitle = stringResource(R.string.sessions_title),
                )
            },
            subtitle = activeId?.take(12),
            onBack = onBack,
            actions = {
                ChatHeaderActions(
                    repo = repo,
                    enabled = repo != null && error == null,
                    onToggle = { showTaskPanel = !showTaskPanel },
                )
            },
        )

        val errorMessage = error
        if (errorMessage != null) {
            CenterHint(
                icon = { Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp)) },
                title = stringResource(R.string.cannot_open_chat),
                body = errorMessage,
            )
        } else if (repo == null) {
            // 会话通道建立中：快速进入时等待设备就绪/bridge 打开，绝不留白屏
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.opening_session),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            MessageTimeline(
                repo = repo!!,
                workspaceKey = workspaceKey,
                sessionId = sessionId,
                activeId = activeId,
                listState = listState,
                autoFollow = autoFollow,
                onToggleAutoFollow = onToggleAutoFollow,
                working = working,
                historyState = historyState,
                loadAttachment = loadAttachment,
                onOpenSubagent = onOpenSubagent,
                scope = scope,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            if (!readOnly) {
                // 发送失败提示：服务端拒绝时输入框已恢复，这里给出原因
                sendError?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                // 发送区整体下沉到 ComposerSection：它内部才订阅 config / usage /
                // modelOptions / queueItems 等高频状态。这些 flow 原本订阅在 ChatScreen
                // 顶层（见上方注释），流式期间会把整页拖进高频重组。
                ComposerSection(
                    repo = repo,
                    input = input,
                    onInputChange = { input = it },
                    working = working,
                    enabled = repo != null && error == null,
                    activeId = activeId,
                    pendingFiles = pendingFiles,
                    uploadStatus = uploadStatus,
                    onRemoveFile = { f -> pendingFiles = pendingFiles - f },
                    onAttach = { pickFiles.launch(arrayOf("*/*")) },
                    onSend = { queued ->
                        val text = input
                        val files = pendingFiles
                        input = ""
                        autoFollow = true
                        scope.launch {
                            runCatching {
                                val repo0 = repo ?: return@launch
                                var target = activeId
                                // 官方语义：只有 accepted/duplicate/noop 才算发出去；
                                // 其余状态要恢复输入框并提示，否则消息会"凭空消失"
                                var outcome: app.zemote.protocol.SendOutcome? = null
                                if (files.isNotEmpty()) {
                                    // 官方路径：附件需先有 sessionId 才能上传 →
                                    // createSession → attachmentPut → sendText(attachments)
                                    if (target == null) {
                                        target = repo0.createSession()
                                            ?: throw IllegalStateException(context.getString(R.string.create_session_failed))
                                    }
                                    val descriptors = mutableListOf<Map<String, Any?>>()
                                    files.forEachIndexed { i, f ->
                                        uploadStatus = context.getString(R.string.uploading_files, i + 1, files.size)
                                        val up = repo0.attachmentPut(target, f.name, f.mime, f.bytes) { p ->
                                            uploadStatus = context.getString(R.string.uploading_progress, i + 1, files.size, (p * 100).toInt())
                                        }
                                        if (up.ref.isNullOrBlank()) throw IllegalStateException(context.getString(R.string.attach_failed, f.name))
                                        descriptors.add(mapOf(
                                            "ref" to up.ref,
                                            "fileName" to up.fileName,
                                            "mime" to up.mime,
                                            "bytes" to up.bytes,
                                        ))
                                    }
                                    uploadStatus = null
                                    pendingFiles = emptyList()
                                    outcome = repo0.sendText(text, target, attachments = descriptors)
                                } else {
                                    // AI 回复中 → 官方 queue 语义（排队）；空闲 → startNow。
                                    // 后续更新完全由订阅帧（row.appended / row.delta）推送，不做轮询
                                    // 发送时若 target 为 null，sendText 会先 createSession 再发送
                                    outcome = repo0.sendText(
                                        text,
                                        target,
                                        requestedDelivery = if (queued) "queue" else "startNow",
                                    )
                                }
                                (outcome as? app.zemote.protocol.SendOutcome.Accepted)?.sessionId?.let { target = it }
                                if (outcome is app.zemote.protocol.SendOutcome.Rejected) {
                                    throw IllegalStateException(
                                        context.getString(
                                            R.string.send_rejected,
                                            outcome.reasonCode ?: outcome.message ?: "",
                                        ),
                                    )
                                }
                            }.onFailure {
                                // 失败即恢复输入内容与待发附件，并提示原因
                                input = text
                                pendingFiles = files
                                uploadStatus = null
                                sendError = it.message ?: context.getString(R.string.send_rejected, "")
                            }
                        }
                    },
                    onStop = {
                        scope.launch { runCatching { repo?.stop(activeId) } }
                    },
                )
            }
        }

        // 权限审批 / 用户输入弹窗：出现新的待响应请求时自动弹出
        InteractionDialogHost(repo)

        // 任务面板（全屏覆盖）：不可见时完全不订阅后台状态
        TaskPanelHost(
            repo = repo,
            visible = showTaskPanel,
            onDismiss = { showTaskPanel = false },
        )
    }
}

// ────────────────────────── 顶栏（独立重组域） ──────────────────────────

/**
 * 会话标题：订阅 sessions-index 的实时标题（桌面端重命名会跟着更新）。
 * 抽成独立组件，让 sessions-index 的推送只重组这一个 `Text`，
 * 而不是整个聊天页（顶栏 + 时间线 + 发送区）。
 */
@Composable
private fun ChatHeaderTitle(
    repo: app.zemote.protocol.ConversationV4Session?,
    activeId: String?,
    newChatTitle: String,
    fallbackTitle: String,
) {
    val sessionEntries by (repo?.sessionEntries?.collectAsState()
        ?: remember { mutableStateOf(emptyList<app.zemote.protocol.SessionEntry>()) })
    val sessionTitle = sessionEntries
        .firstOrNull { it.sessionId == activeId }
        ?.title?.trim()?.ifBlank { null }
    Text(
        text = when {
            activeId == null -> newChatTitle
            sessionTitle != null -> sessionTitle
            else -> fallbackTitle
        },
        style = MaterialTheme.typography.titleLarge,
    )
}

/**
 * 顶栏右侧的任务面板入口。
 * 订阅 `pendingInteractions` / `backgroundWorks` —— Agent 工作时后台任务状态会频繁变化，
 * 订阅留在 ChatScreen 顶层会波及整页；放在这里只会重组这一个按钮。
 */
@Composable
private fun ChatHeaderActions(
    repo: app.zemote.protocol.ConversationV4Session?,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val pendingInteractions by (repo?.pendingInteractions?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })
    val backgroundWorks by (repo?.backgroundWorks?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })
    val hasPending = pendingInteractions.isNotEmpty()
    val hasBackground = backgroundWorks.any { it.status == "running" }

    FilledTonalIconButton(
        onClick = onToggle,
        modifier = Modifier.size(36.dp),
        enabled = enabled,
    ) {
        val tint = if (hasPending || hasBackground) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            Icons.Rounded.TaskAlt,
            contentDescription = stringResource(R.string.tasks_panel),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (hasPending) {
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
    }
}

// ────────────────────────── 交互响应 / 任务面板（独立重组域） ──────────────────────────

/**
 * 权限审批 / 用户输入弹窗宿主。
 *
 * 修复的缺陷：旧实现用一个 `selectedInteraction` 状态驱动弹窗，但**全仓库没有任何
 * 地方把它置为非 null** —— 这个弹窗永远不会出现。桌面端发起权限审批时，界面只是
 * 在任务面板按钮上多一个小红点；用户不主动点开任务面板就完全看不到，而 Agent 会
 * 一直阻塞等待答复。现在改为订阅 `pendingInteractions`，出现新的待响应请求就自动弹出，
 * 每个 `requestId` 只自动弹一次（用户手动关掉后不会再弹回来）。
 */
@Composable
private fun InteractionDialogHost(repo: app.zemote.protocol.ConversationV4Session?) {
    val interactions by (repo?.pendingInteractions?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })
    val scope = rememberCoroutineScope()
    // 已自动弹过的 requestId（纯记账，不参与组合观察）
    val shown = remember { mutableSetOf<String>() }
    var current by remember { mutableStateOf<PendingInteraction?>(null) }

    LaunchedEffect(interactions) {
        if (current != null) return@LaunchedEffect
        val next = interactions.firstOrNull { it.requestId !in shown } ?: return@LaunchedEffect
        if (shown.size > 256) shown.clear()
        shown.add(next.requestId)
        current = next
    }

    val inter = current ?: return
    InteractionDialog(
        interaction = inter,
        onRespond = { optionId, freeText, action ->
            current = null
            scope.launch { repo?.respondInteraction(inter.requestId, optionId, freeText, action) }
        },
        onDismiss = { current = null },
    )
}

/**
 * 任务面板宿主。
 * `visible` 为 false 时直接返回、**不建立任何订阅** —— 否则后台任务状态的频繁更新
 * 会持续重组聊天页。
 */
@Composable
private fun TaskPanelHost(
    repo: app.zemote.protocol.ConversationV4Session?,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val scope = rememberCoroutineScope()
    val interactions by (repo?.pendingInteractions?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })
    val works by (repo?.backgroundWorks?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })

    TaskPanel(
        interactions = interactions,
        works = works,
        onRespond = { inter, optId, freeText, action ->
            scope.launch {
                repo?.respondInteraction(inter.requestId, optId, freeText, action)
                onDismiss()
            }
        },
        onCancel = { workId ->
            scope.launch { repo?.cancelBackgroundWork(workId) }
        },
        onDismiss = onDismiss,
    )
}

// ────────────────────────── 发送区（独立重组域） ──────────────────────────

/**
 * 发送区：队列卡片 + 待发附件条 + 输入栏。
 *
 * 单独抽出来的唯一目的是**限制重组范围**：`convConfig` / `usage` / `modelOptions` /
 * `stopWorkId` / `followupMode` / `queueItems` / `autoDrain` 只在这里订阅。
 * 这些状态在流式输出期间更新极频繁（尤其 `usage` 随每个 `state.updated` 补丁，
 * 约 16 次/秒），订阅在 ChatScreen 顶层会让整个聊天页连同 MessageTimeline、
 * 所有可见消息一起重组 —— 这正是「UI 卡顿、交互响应迟缓」的主要根因。
 */
@Composable
private fun ComposerSection(
    repo: app.zemote.protocol.ConversationV4Session?,
    input: String,
    onInputChange: (String) -> Unit,
    working: Boolean,
    enabled: Boolean,
    activeId: String?,
    pendingFiles: List<PendingFile>,
    uploadStatus: String?,
    onRemoveFile: (PendingFile) -> Unit,
    onAttach: () -> Unit,
    onSend: (queued: Boolean) -> Unit,
    onStop: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val convConfig by (repo?.convConfig?.collectAsState() ?: remember { mutableStateOf(null) })
    val usage by (repo?.usage?.collectAsState() ?: remember { mutableStateOf(null) })
    val modelOptions by (repo?.modelOptions?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val stopWorkId by (repo?.stopWorkId?.collectAsState() ?: remember { mutableStateOf(null) })
    val followupMode by (repo?.followupMode?.collectAsState() ?: remember { mutableStateOf(null) })
    val queueItems by (repo?.queueItems?.collectAsState()
        ?: remember { mutableStateOf(emptyList<app.zemote.protocol.QueueItem>()) })
    val autoDrain by (repo?.autoDrain?.collectAsState() ?: remember { mutableStateOf(true) })

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
            onRemove = onRemoveFile,
        )
    }

    ComposerBar(
        text = input,
        onTextChange = onInputChange,
        working = working,
        enabled = enabled,
        config = convConfig,
        usage = usage,
        modelOptions = modelOptions,
        stopWorkId = stopWorkId,
        followupMode = followupMode,
        onAttach = onAttach,
        onThoughtSelect = { level ->
            scope.launch { runCatching { repo?.setThought(level) } }
        },
        onModelSelect = { provider, model ->
            scope.launch { runCatching { repo?.setModel(provider, model) } }
        },
        onSend = onSend,
        onStop = onStop,
    )
}

// ────────────────────────── 消息时间线（独立重组域） ──────────────────────────

/**
 * 消息时间线。
 *
 * 单独抽成一个 composable 是为了**把重组范围限制在时间线内部**：`repo.rows` 只在这里订阅，
 * 流式输出时不会带着顶栏、输入栏、队列卡片一起重组（旧实现整页订阅 rows，
 * 每个 token 都会让整页重组一遍，这是卡顿的主因之一）。
 */
@Composable
private fun MessageTimeline(
    repo: app.zemote.protocol.ConversationV4Session,
    workspaceKey: String,
    sessionId: String?,
    activeId: String?,
    listState: LazyListState,
    autoFollow: Boolean,
    onToggleAutoFollow: (Boolean) -> Unit,
    working: Boolean,
    historyState: app.zemote.protocol.HistoryState,
    loadAttachment: suspend (String) -> app.zemote.protocol.AttachmentData?,
    onOpenSubagent: (String, String, String) -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val rows by repo.rows.collectAsState()
    // hasMore 是可观察的 StateFlow（见 ConversationV4Session.hasMoreFlow）。
    // 旧实现里 `hasMore` 只是普通 var，`repo.hasOlderHistory` 在组合中读取它却
    // 不产生订阅 —— 服务端翻页结果变化时不会触发重组，于是出现「明明还有更早
    // 历史，顶部按钮却不出现」这种与预期不符的行为。
    val hasMoreFlag by repo.hasMoreFlow.collectAsState()
    val hasOlder = remember(rows, hasMoreFlag) { repo.hasOlderHistory }

    // 行内容变化时重建展示项；LazyColumn 用稳定 key，未变化的条目会被自动跳过。
    // 上一次的结果放在普通引用里而不是 Compose state —— 组合期间写 state 会引入
    // 额外的重组轮次，这里只需要它作为「实例复用池」，不参与订阅。
    val prevItemsRef = remember {
        java.util.concurrent.atomic.AtomicReference<List<DisplayItem>>(emptyList())
    }
    val displayItems = remember(rows) {
        buildDisplayItems(rows, prevItemsRef.get()).also { prevItemsRef.set(it) }
    }

    // 是否显示「回到最新消息」按钮：用户上翻时出现
    var showScrollToBottom by remember { mutableStateOf(false) }
    // 向上加载更多历史的状态
    var isLoadingOlder by remember { mutableStateOf(false) }
    var loadOlderError by remember { mutableStateOf<String?>(null) }

    val isFirstItemVisible by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && info.visibleItemsInfo.firstOrNull()?.index == 0
        }
    }
    val isLastItemVisible by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }

    // 当最后一项不可见时（用户上翻了），显示回到最新消息按钮
    LaunchedEffect(isLastItemVisible, displayItems.isNotEmpty()) {
        showScrollToBottom = !isLastItemVisible && displayItems.isNotEmpty()
    }

    // ── 列表条目结构：DSL 与锚点索引共用同一组条件，避免两处判断不一致导致滚动位置算错 ──
    val showLoading = rows.isEmpty() && historyState == app.zemote.protocol.HistoryState.LOADING
    val showFailed = rows.isEmpty() && historyState == app.zemote.protocol.HistoryState.FAILED
    val showEmpty = rows.isEmpty() && historyState == app.zemote.protocol.HistoryState.EMPTY
    // 注意：不把 isLoadingOlder 放进条件里 —— 加载中让按钮原地变转圈，
    // 否则条目会在加载开始/结束时被移除又插入，列表整体跳动一下。
    val showOlderButton = isFirstItemVisible && activeId != null &&
        loadOlderError == null && (hasOlder || isLoadingOlder)
    val showOlderError = loadOlderError != null

    /**
     * 末尾锚点项的索引，从数据推算而不是读 `layoutInfo`。
     * 布局在组合之后才更新，用 `layoutInfo.totalItemsCount - 1` 取会滞后一帧，
     * 新消息到达时滚动会差一条。顺序：可选状态条目 → displayItems → 可选 working → 锚点。
     */
    val anchorIndex = listOf(showLoading, showFailed, showEmpty, showOlderButton, showOlderError)
        .count { it } + displayItems.size + (if (working) 1 else 0)

    // 提到 items 外面：以前每个条目、每次重组都会新建一个 lambda，导致捕获它的
    // FadeInContainer / TimelineRow 参数恒不相等，永远无法跳过重组。
    val openSub: (ConvRow) -> Unit = remember(workspaceKey, sessionId, onOpenSubagent) {
        { row -> row.childSessionId?.let { cid -> onOpenSubagent(workspaceKey, cid, sessionId ?: "") } }
    }

    // 到达列表顶部时自动加载更早的历史（服务端确实还有更早的行才触发）。
    // 把 hasOlder 也作为 key：翻页结果变化时能重新触发（Boolean 相等比较，
    // 值没变就不会重启 effect，不会造成重复加载）。
    LaunchedEffect(isFirstItemVisible, displayItems.isNotEmpty(), hasOlder) {
        if (!isFirstItemVisible || isLoadingOlder) return@LaunchedEffect
        val sid = activeId ?: return@LaunchedEffect
        if (!hasOlder) return@LaunchedEffect
        isLoadingOlder = true
        loadOlderError = null
        // 直接在本 effect 里挂起，加载期间 isLoadingOlder 保持 true，
        // 避免按钮和滚动触发在两帧内重复发起同一次翻页。
        try {
            repo.loadOlderMessages(sid)
        } catch (t: Throwable) {
            loadOlderError = t.message ?: t.javaClass.simpleName
        } finally {
            isLoadingOlder = false
        }
    }

    // 新消息 / 条目数变化：贴底。
    // 列表末尾放了一个 0 高度的锚点项，"滚到最后一个 index"就等价于"贴到底部"，
    // 这样最后一条很长时也不会只把它的顶部露出来。
    var lastScrollTarget by remember { mutableStateOf(-1) }
    LaunchedEffect(anchorIndex, autoFollow) {
        if (!autoFollow || displayItems.isEmpty()) return@LaunchedEffect
        // 只有当前已经贴底时才继续跟随，防止用户上翻时被强制弹回
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible < info.totalItemsCount - 1) return@LaunchedEffect
        if (anchorIndex != lastScrollTarget) {
            lastScrollTarget = anchorIndex
            listState.scrollToItem(anchorIndex)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showLoading) {
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
                            stringResource(R.string.loading_chat),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (showFailed) {
                // 订阅/拉取都失败时给出明确反馈和重试入口，而不是一片空白
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 90.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.fetch_sessions_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = { scope.launch { repo.retryHistory(activeId) } }) {
                            Text(stringResource(R.string.retry_connect))
                        }
                    }
                }
            } else if (showEmpty) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.session_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            // 加载更多历史按钮：还有更早的行时显示在列表顶部
            if (showOlderButton) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isLoadingOlder) return@clickable
                                isLoadingOlder = true
                                loadOlderError = null
                                scope.launch {
                                    try {
                                        repo.loadOlderMessages(activeId)
                                    } catch (t: Throwable) {
                                        loadOlderError = t.message ?: t.javaClass.simpleName
                                    } finally {
                                        isLoadingOlder = false
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoadingOlder) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.History,
                                    contentDescription = stringResource(R.string.load_older_messages),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.load_older_messages),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (showOlderError) {
                item {
                    Text(
                        loadOlderError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            items(displayItems, key = { it.key }) { item ->
                when (item) {
                    is DisplayItem.Single -> {
                        if (item.row.kind == ConvKinds.USER_INPUT) {
                            TimelineRow(item.row, loadAttachment, onOpenSubagent = openSub)
                        } else {
                            // AI 产生的内容淡入，更灵动
                            FadeInContainer(item.key) {
                                TimelineRow(item.row, loadAttachment, onOpenSubagent = openSub)
                            }
                        }
                    }
                    is DisplayItem.ToolGroup -> FadeInContainer(item.key) {
                        ToolGroupCard(item.rows, onOpenSubagent = openSub)
                    }
                }
            }

            if (working) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinkingDot()
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.processing),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 末尾锚点：滚到它 = 贴到底部（见上面的自动跟随逻辑）
            item(key = "bottom-anchor") { Spacer(modifier = Modifier.height(0.dp)) }
        }

        // 右下角浮动按钮组
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 回到最新消息：用户上翻时出现
            AnimatedVisibility(visible = showScrollToBottom) {
                FilledTonalIconButton(
                    onClick = {
                        showScrollToBottom = false
                        scope.launch { listState.animateScrollToItem(anchorIndex) }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.scroll_to_bottom),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            // 自动跟随开关：亮 = 跟随最新内容，暗 = 手动浏览
            IconToggleButton(
                checked = autoFollow,
                onCheckedChange = onToggleAutoFollow,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = if (autoFollow) stringResource(R.string.auto_follow_on) else stringResource(R.string.auto_follow_off),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ────────────────────────── 时间线渲染 ──────────────────────────
// ────────────────────────── 时间线渲染 ──────────────────────────

@Composable
private fun TimelineRow(row: ConvRow, loadAttachment: suspend (String) -> app.zemote.protocol.AttachmentData?, onOpenSubagent: (ConvRow) -> Unit = {}) {
    when (row.kind) {
        ConvKinds.USER_INPUT -> UserBubble(row, loadAttachment)
        ConvKinds.ASSISTANT_TEXT -> if (row.text.isNotBlank()) {
            app.zemote.ui.components.MarkdownText(
                markdown = row.text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ConvKinds.REASONING -> ThinkingBlock(row)
        // 工具调用统一走「执行过程」汇总卡片（正常路径由 buildDisplayItems 聚合，
        // 此处兜底处理未聚合的单条）
        ConvKinds.TOOL_CALL -> ToolGroupCard(listOf(row), onOpenSubagent)
        ConvKinds.SUBAGENT -> if (row.summaryText.isNotBlank() || row.text.isNotBlank()) {
            ToolGroupCard(listOf(row.copy(toolName = "subagent", inputText = row.summaryText.ifBlank { row.text })), onOpenSubagent)
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
                row.text.ifBlank { stringResource(R.string.image_message) },
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
                    fileName = att["fileName"] ?: stringResource(R.string.image),
                    loadAttachment = loadAttachment,
                )
            } else {
                AttachmentChip(fileName = att["fileName"] ?: stringResource(R.string.attach))
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
                Icons.AutoMirrored.Rounded.InsertDriveFile,
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
    // 组件销毁或 ref 变化时回收旧 bitmap，防止内存泄漏
    DisposableEffect(ref) {
        onDispose { bitmap?.recycle(); bitmap = null }
    }
    DisposableEffect(Unit) {
        // 用户离开对话页时（ref 未变）也回收，防止整屏图片 bitmap 滞留在内存
        onDispose { bitmap?.recycle(); bitmap = null }
    }
    LaunchedEffect(ref) {
        val oldBmp = bitmap
        val data = runCatching { loadAttachment(ref) }.getOrNull()
        val bmp = data?.bytes?.let { bytes ->
            runCatching {
                // 大图降采样解码（最长边 ~2048px），防止整图 bitmap 爆内存
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
        if (oldBmp != null && oldBmp != bmp) oldBmp.recycle()
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
                    stringResource(R.string.image_loading),
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
                    stringResource(R.string.queued_count, order.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (autoDrain) stringResource(R.string.auto_send_on) else stringResource(R.string.auto_send_off),
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
                        contentDescription = stringResource(R.string.drag_reorder),
                        tint = if (dragId == item.queueItemId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.queue_item_index, index + 1, item.text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onSendNow(item.queueItemId) }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.send_now),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = { editTarget = item }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(onClick = { deleteTarget = item }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.delete),
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
            title = { Text(stringResource(R.string.queue_edit_title)) },
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
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.queue_delete_title)) },
            text = { Text(target.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(target.queueItemId)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
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
                            contentDescription = stringResource(R.string.remove),
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
                    if (streaming) stringResource(R.string.thinking_ellipsis) else stringResource(R.string.thinking_label),
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
private fun ToolGroupCard(rows: List<ConvRow>, onOpenSubagent: (ConvRow) -> Unit = {}) {
    // 用第一行的 rowId 作 key；rowId 是 Long（值类型），跨重组稳定
    val firstRowId = rows.firstOrNull()?.rowId ?: 0L
    var expanded by remember(firstRowId) { mutableStateOf(false) }
    val ctx = LocalContext.current
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
                    stringResource(R.string.exec_activity),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (rows.size > 1) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.exec_steps, rows.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (anyRunning) {
                    ThinkingDot()
                } else if (anyFailed) {
                    Text(stringResource(R.string.some_failed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                // 子智能体入口：组内任一行为子智能体且含 childSessionId 时显示
                val subagentRow = rows.firstOrNull { it.childSessionId != null }
                if (subagentRow != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.subagent_open),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onOpenSubagent(subagentRow) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            // 每步一句：执行了什么 / 修改了什么（不做动画，直切）
            rows.forEach { row ->
                val stepRunning = row.toolStatus == null || row.toolStatus == "running" || row.toolStatus == "pending"
                // toolSentence 内部要 JSONObject 解析 inputText。工具参数是流式追加的，
                // 卡片每帧重组时若重解析，一个 8 步的组每帧就是 8 次 JSON 解析。
                // key 里带上 rowId，位置变化也不会取到别的行的缓存。
                val sentence = remember(row.rowId, row.toolName, row.inputText, row.summaryText) {
                    toolSentence(ctx, row)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        sentence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (stepRunning) {
                        // 静态色点：组头已有一个 ThinkingDot 承担"运行中"的动画语义，
                        // 这里再挂 N 个 rememberInfiniteTransition 会让整卡每帧重组 N 次。
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), CircleShape),
                        )
                    } else if (row.toolStatus == "error") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
private fun toolSentence(ctx: android.content.Context, row: ConvRow): String {
    val name = row.toolName?.lowercase()
    val inputSrc = row.inputText.ifBlank { row.summaryText }
    val target = summarizeToolInput(name, inputSrc).ifBlank { row.text }
    return when (name) {
        "terminal", "bash", "run_command" ->
            if (target.isBlank()) ctx.getString(R.string.tool_run) else ctx.getString(R.string.tool_run_arg, target)
        "edit" ->
            if (target.isBlank()) ctx.getString(R.string.tool_edit) else ctx.getString(R.string.tool_edit_arg, target)
        "write" ->
            if (target.isBlank()) ctx.getString(R.string.tool_write) else ctx.getString(R.string.tool_write_arg, target)
        "multiedit", "notebookedit" ->
            if (target.isBlank()) ctx.getString(R.string.tool_multi_edit) else ctx.getString(R.string.tool_multi_edit_arg, target)
        "read" ->
            if (target.isBlank()) ctx.getString(R.string.tool_read) else ctx.getString(R.string.tool_read_arg, target)
        "search", "grep", "glob", "websearch", "web_fetch" ->
            if (target.isBlank()) ctx.getString(R.string.tool_search) else ctx.getString(R.string.tool_search_arg, target)
        "task", "subagent" ->
            if (target.isBlank()) ctx.getString(R.string.tool_subtask) else ctx.getString(R.string.tool_subtask_arg, target)
        null -> ctx.getString(R.string.tool_generic)
        else ->
            if (target.isBlank()) ctx.getString(R.string.tool_named, row.toolName ?: "")
            else ctx.getString(R.string.tool_named_arg, row.toolName ?: "", target)
    }
}

/** 时间线显示项：普通行单条展示，连续的工具行聚合为一组 */
private sealed interface DisplayItem {
    val key: String

    data class Single(val row: ConvRow) : DisplayItem {
        override val key get() = "r-${row.rowId}"
    }

    data class ToolGroup(val rows: List<ConvRow>) : DisplayItem {
        /**
         * key 只取**首行** rowId。
         *
         * 旧实现把末行 rowId 也编进 key（`g-1-5`）：流式期间组内每新增一个工具调用，
         * key 就变成 `g-1-6`，LazyColumn 会认为这是一个全新条目 —— 旧条目被销毁重建，
         * FadeInContainer 重新从透明淡入、ToolGroupCard 的 `expanded` 展开状态被重置。
         * 表现就是 AI 工作时「执行过程」卡片反复闪烁、用户展开后自己又合上。
         * 首行 rowId 在组增长时保持不变，key 因此稳定，条目与状态都能被正确复用。
         */
        override val key get() = "g-${rows.first().rowId}"
    }
}

/**
 * 把行列表折叠成展示项（连续的工具调用合并成一张卡片）。
 *
 * `prev` 是上一次的结果：流式输出期间每 60ms 就会重跑一次，而真正变化的通常只有最后一行。
 * 这里对每个槽位做「实例比对」，命中就复用旧对象，避免每次都重建整张列表 —— 否则
 * 一次长会话每帧要分配上千个 DisplayItem，GC 抖动会直接表现为滚动卡顿。
 *
 * 之所以能安全地按 `===` 比对：ConversationV4 在内容未变时始终复用同一个 ConvRow 实例
 * （见 appendToRow / flushPendingDeltas），只有真正被改写的行才会产生新实例。
 */
private fun buildDisplayItems(rows: List<ConvRow>, prev: List<DisplayItem>): List<DisplayItem> {
    val out = ArrayList<DisplayItem>(if (prev.isEmpty()) 16 else prev.size)
    val group = ArrayList<ConvRow>()
    fun flush() {
        if (group.isEmpty()) return
        val idx = out.size
        val cached = prev.getOrNull(idx) as? DisplayItem.ToolGroup
        val reusable = cached != null &&
            cached.rows.size == group.size &&
            cached.rows.indices.all { i -> cached.rows[i] === group[i] }
        out.add(if (reusable) cached!! else DisplayItem.ToolGroup(ArrayList(group)))
        group.clear()
    }
    for (row in rows) {
        val isTool = row.kind == ConvKinds.TOOL_CALL || row.kind == ConvKinds.SUBAGENT
        if (isTool) {
            group.add(row)
        } else {
            flush()
            val cached = prev.getOrNull(out.size)
            out.add(
                if (cached is DisplayItem.Single && cached.row === row) cached
                else DisplayItem.Single(row)
            )
        }
    }
    flush()
    return out
}

// ────────────────────────── 交互响应弹窗 ──────────────────────────

/** 权限审批 / 用户输入弹窗 */
@Composable
private fun InteractionDialog(
    interaction: PendingInteraction,
    onRespond: (optionId: String?, freeText: String?, action: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(interaction.requestId) { mutableStateOf("") }
    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (interaction.kind == "permission")
                Text(stringResource(R.string.permission_request_title))
            else
                Text(stringResource(R.string.elicitation_request_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (interaction.body.isNotBlank()) {
                    Text(interaction.body, style = MaterialTheme.typography.bodyMedium)
                }
                if (interaction.options.isNotEmpty()) {
                    interaction.options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRespond(opt.optionId, null, null) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (opt.kind == "deny") Icons.Rounded.Close
                                else Icons.Rounded.Check,
                                contentDescription = null,
                                tint = if (opt.kind == "deny") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (interaction.kind != "permission") {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                }
            }
        },
        confirmButton = {
            if (interaction.options.isEmpty() && interaction.kind != "permission") {
                TextButton(
                    onClick = { onRespond(null, text.trim(), null) },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.elicitation_submit)) }
            }
        },
        dismissButton = {
            if (interaction.options.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

// ────────────────────────── 任务面板 ──────────────────────────

/** 任务面板：显示待响应交互 + 后台运行中的任务 */
@Composable
private fun TaskPanel(
    interactions: List<PendingInteraction>,
    works: List<BackgroundWork>,
    onRespond: (PendingInteraction, String?, String?, String?) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.tasks_panel), style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 待响应交互
            if (interactions.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.permission_request_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(interactions, key = { it.requestId }) { inter ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (inter.body.isNotBlank()) {
                                Text(inter.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (inter.options.isNotEmpty()) {
                                inter.options.forEach { opt ->
                                    val isDeny = opt.kind == "deny"
                                    FilledTonalIconButton(
                                        onClick = { onRespond(inter, opt.optionId, null, null) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (isDeny) Icons.Rounded.Close else Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = if (isDeny) MaterialTheme.colorScheme.error
                                                    else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            } else if (inter.kind != "permission") {
                                // 用户输入型交互
                                var text by remember(inter.requestId) { mutableStateOf("") }
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 3,
                                    placeholder = { Text(stringResource(R.string.elicitation_request_title)) },
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                                    FilledTonalIconButton(
                                        onClick = { onRespond(inter, null, text.trim(), null) },
                                        enabled = text.isNotBlank(),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.elicitation_submit))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 后台任务
            val runningWorks = works.filter { it.status == "running" }
            if (runningWorks.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.task_running),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(runningWorks, key = { it.workId }) { work ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (work.kind == "subagent") Icons.Rounded.SmartToy
                                else Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                work.title.ifBlank { "${work.kind}" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (work.cancellable) {
                                IconButton(
                                    onClick = { onCancel(work.workId) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Cancel,
                                        contentDescription = stringResource(R.string.task_cancel),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (interactions.isEmpty() && runningWorks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.task_no_work), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
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
                            if (working) stringResource(R.string.composer_hint_queued)
                            else stringResource(R.string.composer_hint),
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
        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.attach), modifier = Modifier.size(18.dp))
    }
}

/** 思考等级按钮：弹出菜单显示所有可选等级 */
@Composable
private fun ThoughtLevelButton(
    config: app.zemote.protocol.ConvConfig?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
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
                contentDescription = stringResource(R.string.thought_level),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(17.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (level in levels) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(thoughtLabel(ctx, level), style = MaterialTheme.typography.bodyMedium)
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

    val label = if (currentModel != null) modelLabel(currentProvider ?: "", currentModel) else stringResource(R.string.model_label)

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
                contentDescription = stringResource(R.string.model_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (modelOptions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_empty), style = MaterialTheme.typography.bodyMedium) },
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
                contentDescription = stringResource(R.string.context_usage),
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        val ctx2 = LocalContext.current
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
                    text = { Text(stringResource(R.string.usage_cache_hit, (usage.hitRate * 100).toInt()), style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false },
                )
            }
            for ((source, chars) in usage.breakdown) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.usage_chars, sourceLabel(ctx2, source), "${(chars / 1000).toInt()}k"), style = MaterialTheme.typography.bodySmall)
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
        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.stop), modifier = Modifier.size(18.dp))
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
            Text(stringResource(R.string.usage_queue), style = MaterialTheme.typography.labelMedium)
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
            contentDescription = stringResource(R.string.send),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ────────────────────────── 辅助函数 ──────────────────────────

private fun thoughtLabel(ctx: android.content.Context, level: String): String = when (level.lowercase()) {
    "off", "none", "disabled" -> ctx.getString(R.string.thought_off)
    "nothink", "no-think", "no_think" -> ctx.getString(R.string.thought_no)
    "on", "enabled" -> ctx.getString(R.string.thought_on)
    "low", "light", "minimal", "shallow" -> ctx.getString(R.string.thought_low)
    "medium", "balanced", "default" -> ctx.getString(R.string.thought_medium)
    "high" -> ctx.getString(R.string.thought_high)
    "xhigh", "extra-high", "extra_high", "very-high", "very_high" -> ctx.getString(R.string.thought_vhigh)
    "max", "maximum" -> ctx.getString(R.string.thought_max)
    else -> level
}

/** 来源名称中文映射 */
private fun sourceLabel(ctx: android.content.Context, source: String): String = when (source) {
    "messages" -> ctx.getString(R.string.usage_messages)
    "system_tool_schemas" -> ctx.getString(R.string.usage_system_tools)
    "mcp_tool_schemas" -> ctx.getString(R.string.usage_mcp_tools)
    "system_prompt" -> ctx.getString(R.string.usage_system_prompt)
    "skills" -> ctx.getString(R.string.usage_skills)
    "meta_user_context" -> ctx.getString(R.string.usage_other)
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

/**
 * 通用顶栏。
 *
 * [titleContent] 用于需要**独立订阅**标题数据的场景（例如会话标题来自 sessions-index
 * 的实时推送）：传一个自己订阅状态的 composable，标题更新时只会重组它自己，
 * 而不会把整个页面拖进重组。传了 [titleContent] 就忽略 [title]。
 */
@Composable
fun ScreenHeader(
    title: String = "",
    subtitle: String? = null,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (titleContent != null) titleContent() else Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
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
