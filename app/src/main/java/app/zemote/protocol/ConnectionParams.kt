package app.zemote.protocol

/** Parse a ZCode remote URL into connection parameters. */
data class ZemoteConnectionParams(
    val deviceSid: String,
    val passHash: String,
    val timestamp: Int,
    val deviceMid: String? = null,
    val deviceName: String? = null,
    val appVersion: String? = null,
    private val isSecureScheme: Boolean = true,
    private val _sourceHost: String,
    private val sourcePort: Int? = null,
) {
    val sourceHost get() = _sourceHost

    companion object {
        fun parse(raw: String): ZemoteConnectionParams? {
            val trimmed = raw.trim()
            val uri = try { android.net.Uri.parse(trimmed) } catch (_: Exception) { return null }
            val scheme = uri.scheme ?: return null
            if (scheme != "https" && scheme != "wss") return null
            val sid = uri.getQueryParameter("sid")?.trim()
            val hash = uri.getQueryParameter("hash")?.trim()
            val tStr = uri.getQueryParameter("t")?.trim()
            if (sid.isNullOrEmpty() || hash.isNullOrEmpty()) return null
            val t = tStr?.toIntOrNull() ?: return null
            return ZemoteConnectionParams(
                deviceSid = sid,
                passHash = hash,
                timestamp = t,
                deviceMid = uri.getQueryParameter("mid"),
                deviceName = uri.getQueryParameter("name"),
                appVersion = uri.getQueryParameter("app_version"),
                isSecureScheme = true,
                _sourceHost = uri.host ?: "",
                sourcePort = if (uri.port > 0) uri.port else null,
            )
        }
    }

    val isSecure get() = isSecureScheme

    val relayWsUri: String
        get() {
            val wsScheme = if (isSecure) "wss" else "ws"
            val host = sourceHost
            val portSuffix = if (sourcePort != null) ":$sourcePort" else ""
            var url = "$wsScheme://$host$portSuffix/ws"
            if (deviceMid != null) url += "?mid=${android.net.Uri.encode(deviceMid)}"
            return url
        }
}
