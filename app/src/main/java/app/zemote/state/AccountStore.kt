package app.zemote.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.zemote.protocol.ZemoteConnectionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID

data class Account(
    val id: String,
    val label: String,
    val url: String,
    val addedAt: Long,
    val lastUsedAt: Long? = null,
) {
    val params: ZemoteConnectionParams? get() = ZemoteConnectionParams.parse(url)
    val deviceName: String get() = params?.deviceName ?: params?.sourceHost ?: label
}

/**
 * 设备列表仓库。[accounts] StateFlow 是唯一数据源：读取自 DataStore，
 * 增删改先更新内存流再异步持久化（URL 经 Keystore AES/GCM 加密后落盘）。
 */
class AccountStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val ACCOUNTS_KEY = stringPreferencesKey("accounts")
        private val gson = com.google.gson.Gson()
    }

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    /** 兼容旧调用的流别名 */
    val accountsFlow: Flow<List<Account>> get() = accounts

    /** 从 DataStore 载入设备列表（应用启动时调用一次） */
    suspend fun load() {
        _accounts.value = readFromDataStore()
    }

    suspend fun addAccount(url: String, label: String? = null): Account {
        val params = ZemoteConnectionParams.parse(url)
        val acc = Account(
            id = UUID.randomUUID().toString(),
            label = label?.trim()?.takeIf { it.isNotEmpty() }
                ?: params?.deviceName ?: params?.sourceHost ?: "未命名设备",
            url = url.trim(),
            addedAt = System.currentTimeMillis(),
        )
        _accounts.update { it + acc }
        persist()
        return acc
    }

    suspend fun remove(id: String) {
        _accounts.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    suspend fun rename(id: String, label: String) {
        val trimmed = label.trim().takeIf { it.isNotEmpty() } ?: return
        _accounts.update { list -> list.map { if (it.id == id) it.copy(label = trimmed) else it } }
        persist()
    }

    suspend fun touch(id: String) {
        _accounts.update { list ->
            list.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
        }
        persist()
    }

    /** 序列化全部设备（含连接 URL）为 JSON，用于备份/迁移。注意：包含明文凭据，勿外传。 */
    suspend fun exportJson(): String = gson.toJson(mapOf(
        "app" to "zemote", "format" to "devices", "version" to 1,
        "exportedAt" to java.time.Instant.now().toString(),
        "accounts" to _accounts.value.map { a ->
            mapOf(
                "id" to a.id, "label" to a.label, "url" to a.url,
                "addedAt" to a.addedAt, "lastUsedAt" to a.lastUsedAt
            )
        }
    ))

    /**
     * 从导出 JSON 恢复设备。跳过无效 URL 与重复项（按连接 URL 去重），
     * 返回导入数量。
     */
    suspend fun importJson(raw: String): Int {
        @Suppress("UNCHECKED_CAST")
        val decoded = gson.fromJson(raw, Map::class.java) as? Map<String, Any> ?: return 0
        val list = decoded["accounts"] as? List<*> ?: return 0
        var added = 0
        val imported = mutableListOf<Account>()
        for (item in list) {
            if (item !is Map<*, *>) continue
            @Suppress("UNCHECKED_CAST")
            val m = item as Map<String, Any>
            val url = m["url"] as? String ?: continue
            if (ZemoteConnectionParams.parse(url) == null) continue
            if (_accounts.value.any { it.url == url }) continue
            imported.add(Account(
                id = m["id"] as? String ?: UUID.randomUUID().toString(),
                label = m["label"] as? String ?: "未命名设备",
                url = url,
                addedAt = (m["addedAt"] as? Number)?.toLong() ?: 0,
                lastUsedAt = (m["lastUsedAt"] as? Number)?.toLong(),
            ))
            added++
        }
        if (imported.isNotEmpty()) {
            _accounts.update { it + imported }
            persist()
        }
        return added
    }

    private suspend fun readFromDataStore(): List<Account> {
        val raw = dataStore.data.first()[ACCOUNTS_KEY] ?: return emptyList()
        val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
        val list = try {
            gson.fromJson<List<Map<String, Any?>>>(raw, listType) ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return list.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            val encUrl = map["url"] as? String ?: return@mapNotNull null
            // Android Keystore 加密存储；兼容历史明文数据
            val plainUrl = if (CredentialCipher.isEncrypted(encUrl)) {
                CredentialCipher.decrypt(encUrl) ?: return@mapNotNull null // 密钥丢失 → 跳过
            } else encUrl
            Account(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                label = map["label"] as? String ?: "未命名设备",
                url = plainUrl,
                addedAt = (map["addedAt"] as? Number)?.toLong() ?: 0,
                lastUsedAt = (map["lastUsedAt"] as? Number)?.toLong(),
            )
        }
    }

    private suspend fun persist() {
        val entries = _accounts.value.map { a ->
            val encUrl = CredentialCipher.encrypt(a.url) ?: a.url
            mapOf(
                "id" to a.id, "label" to a.label, "url" to encUrl,
                "addedAt" to a.addedAt, "lastUsedAt" to a.lastUsedAt
            )
        }
        dataStore.edit { prefs -> prefs[ACCOUNTS_KEY] = gson.toJson(entries) }
    }
}
