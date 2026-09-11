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
    private val conversations = ConcurrentHashMap<String, ConversationV4Session>()
    private val conversationMutex = Mutex()
    private val workspaceMaps = ConcurrentHashMap<String, Map<String, Any>>()

    /** MainShell bootstrap 后缓存工作区原始 map（V4 握手的 scopeParams 需要） */
    fun cacheWorkspaceScope(accountId: String, workspaceKey: String, map: Map<String, Any>) {
        workspaceMaps["${accountId}|${workspaceKey}"] = map
    }

    /** 取（或创建）某账号某工作区某任务的 V4 会话仓库；仅在设备已连接后可用。 */
    suspend fun conversationFor(accountId: String, workspaceKey: String, taskId: String?): ConversationV4Session? {
        val client = connections[accountId] ?: return null
        val key = "$accountId|$workspaceKey|${taskId ?: "new"}"
        conversations[key]?.let { return it }
        return conversationMutex.withLock {
            conversations.getOrPut(key) {
                val scopeParams = workspaceMaps["${accountId}|${workspaceKey}"]
                ConversationV4Session.open(client, workspaceKey, taskId, scopeParams)
            }
        }
    }

    fun closeConversation(accountId: String, workspaceKey: String) {
        val prefix = "$accountId|$workspaceKey|"
        conversations.keys.filter { it.startsWith(prefix) }.forEach { key ->
            conversations.remove(key)?.dispose()
        }
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
            conn?.dispose()
        }
    }

    fun disconnectAll() {
        viewModelScope.launch {
            connections.values.forEach { it.dispose() }
            connections.clear()
            _uiState.value = SessionUiState()
        }
    }

    override fun onCleared() {
        disconnectAll()
        super.onCleared()
    }
}
