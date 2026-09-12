package app.zemote.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.protocol.ConversationV4Session
import app.zemote.protocol.ZemoteClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 单台设备的连接状态（驱动 UI 渲染） */
data class DeviceStatus(
    val state: ConnectionState = ConnectionState.IDLE,
    val message: String? = null,
)

enum class ConnectionState { IDLE, CONNECTING, CONNECTED, ERROR }

/** 全部设备连接状态快照 */
data class SessionUiState(
    val statuses: Map<String, DeviceStatus> = emptyMap(),
    val activeId: String? = null,
)

/**
 * 多设备连接管理。所有状态收敛到 [uiState] 这个 StateFlow，
 * UI 只观察它即可随连接变化自动重组（旧实现是普通字段，UI 不刷新，已废弃）。
 */
class AppSessionViewModel : ViewModel() {

    private val connections = ConcurrentHashMap<String, ZemoteClient>()
    private val connectMutex = Mutex()
    private val currentAccounts = ConcurrentHashMap<String, Account>()
    private val workspaceMaps = ConcurrentHashMap<String, Map<String, Any>>()

    /**
     * 会话仓库缓存：访问序 LRU，单设备最多保留 [MAX_CONVERSATIONS_PER_ACCOUNT] 个。
     * 每个仓库对应一条 workspace bridge + 订阅，不设上限会随浏览工作区/会话无限增长。
     * 被淘汰仓库对应的页面重新进入时会自动重建（对话页/任务页均有重建路径）。
     */
    private val conversations = LinkedHashMap<String, ConversationV4Session>(8, 0.75f, true)
    private val conversationsLock = Any()
    private val conversationMutex = Mutex()

    private companion object {
        const val MAX_CONVERSATIONS_PER_ACCOUNT = 4
    }

    /** MainShell bootstrap 后缓存工作区原始 map（V4 握手的 scopeParams 需要） */
    fun cacheWorkspaceScope(accountId: String, workspaceKey: String, map: Map<String, Any>) {
        workspaceMaps["${accountId}|${workspaceKey}"] = map
    }

    /** 取（或创建）某账号某工作区某任务的 V4 会话仓库；仅在设备已连接后可用。 */
    suspend fun conversationFor(accountId: String, workspaceKey: String, taskId: String?): ConversationV4Session? {
        val client = connections[accountId] ?: return null
        val key = "$accountId|$workspaceKey|${taskId ?: "new"}"

        synchronized(conversationsLock) { conversations[key] }?.let { cached ->
            if (!cached.bridge.isDisposed) return cached
            // 命中了已被销毁的仓库（LRU 淘汰边界情况）——移除后走重建
            synchronized(conversationsLock) { conversations.remove(key) }
        }

        return conversationMutex.withLock {
            synchronized(conversationsLock) { conversations[key] }?.let { cached ->
                if (!cached.bridge.isDisposed) return cached
                synchronized(conversationsLock) { conversations.remove(key) }
            }
            val scopeParams = workspaceMaps["${accountId}|${workspaceKey}"]
            val repo = ConversationV4Session.open(client, workspaceKey, taskId, scopeParams)
            synchronized(conversationsLock) {
                conversations[key] = repo
                // 淘汰该设备最久未用、超出上限的仓库（dispose 会关掉 bridge 与订阅）
                val mine = conversations.keys.filter { it.startsWith("$accountId|") }
                if (mine.size > MAX_CONVERSATIONS_PER_ACCOUNT) {
                    mine.take(mine.size - MAX_CONVERSATIONS_PER_ACCOUNT).forEach { staleKey ->
                        conversations.remove(staleKey)?.dispose()
                    }
                }
            }
            repo
        }
    }

    fun closeConversation(accountId: String, workspaceKey: String) {
        val toClose = synchronized(conversationsLock) {
            val prefix = "$accountId|$workspaceKey|"
            conversations.keys.filter { it.startsWith(prefix) }
                .mapNotNull { conversations.remove(it) }
        }
        toClose.forEach { it.dispose() }
    }

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    val activeId: String? get() = _uiState.value.activeId

    fun isActive(accountId: String) = _uiState.value.activeId == accountId
    fun isConnected(accountId: String) =
        _uiState.value.statuses[accountId]?.state == ConnectionState.CONNECTED

    fun statusOf(accountId: String): DeviceStatus =
        _uiState.value.statuses[accountId] ?: DeviceStatus()

    fun clientOf(accountId: String): ZemoteClient? = connections[accountId]

    fun currentAccount(accountId: String): Account? = currentAccounts[accountId]

    private fun setStatus(accountId: String, status: DeviceStatus) {
        _uiState.update { it.copy(statuses = it.statuses + (accountId to status)) }
    }

    /**
     * 确保账号已连接并激活。已有存活连接则直接复用；
     * 同一账号的并发连接请求通过 [connectMutex] 去重。
     */
    fun connect(account: Account) {
        viewModelScope.launch {
            connectMutex.withLock {
                if (isConnected(account.id)) {
                    _uiState.update { it.copy(activeId = account.id) }
                    return@withLock
                }
                setStatus(account.id, DeviceStatus(ConnectionState.CONNECTING))
                val params = account.params
                if (params == null) {
                    setStatus(account.id, DeviceStatus(ConnectionState.ERROR, "无法解析连接 URL（需要 sid/hash/t 参数）"))
                    return@withLock
                }
                val client = ZemoteClient(params, onLog = { msg -> android.util.Log.d("Zemote", msg) })
                try {
                    client.connect()
                    client.waitPaired(timeoutMs = 90_000L)
                    connections[account.id] = client
                    currentAccounts[account.id] = account
                    setStatus(account.id, DeviceStatus(ConnectionState.CONNECTED))
                    _uiState.update { it.copy(activeId = account.id) }
                } catch (e: Exception) {
                    client.dispose()
                    setStatus(account.id, DeviceStatus(ConnectionState.ERROR, e.message ?: "连接失败"))
                }
            }
        }
    }

    fun switchTo(account: Account) {
        if (isConnected(account.id)) {
            _uiState.update { it.copy(activeId = account.id) }
            return
        }
        connect(account)
    }

    fun disconnect(accountId: String) {
        viewModelScope.launch {
            val conn = connections.remove(accountId)
            setStatus(accountId, DeviceStatus())
            if (_uiState.value.activeId == accountId) {
                _uiState.update { it.copy(activeId = null) }
            }
            // 断开设备时释放其所有 V4 会话（含 bridge 订阅）
            val staleConversations = synchronized(conversationsLock) {
                conversations.keys.filter { it.startsWith("$accountId|") }
                    .mapNotNull { conversations.remove(it) }
            }
            staleConversations.forEach { it.dispose() }
            conn?.dispose()
        }
    }

    fun disconnectAll() {
        viewModelScope.launch {
            connections.values.forEach { it.dispose() }
            connections.clear()
            val staleConversations = synchronized(conversationsLock) {
                val all = conversations.values.toList()
                conversations.clear()
                all
            }
            staleConversations.forEach { it.dispose() }
            _uiState.value = SessionUiState()
        }
    }

    override fun onCleared() {
        disconnectAll()
        super.onCleared()
    }
}
