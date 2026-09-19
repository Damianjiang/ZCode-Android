package app.zemote.protocol

/**
 * RPC frame transport: logical messages split into frames with CRC32 checksum.
 */
class RpcFrameTransport(
    val bridgeSessionId: String,
    val bridgeGeneration: Int? = null,
    val recoveryId: String? = null,
    private val sendPayload: (Map<String, Any>) -> Unit,
    private val onLog: ((String) -> Unit)? = null,
) {
    companion object {
        const val MAX_FRAGMENT_BYTES = 512 * 1024
        const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
        const val MAX_FRAGMENTS = 64
        const val ASSEMBLY_TIMEOUT_MS = 5_000L
    }

    private var seq = 0
    private var messageSeq = 0

    private val assemblies = java.util.concurrent.ConcurrentHashMap<Int, Assembly>()

    var onMessage: ((ByteArray) -> Unit)? = null

    private val identity: Map<String, Any?>
        get() = buildMap {
            put("bridgeSessionId", bridgeSessionId)
            bridgeGeneration?.let { put("bridgeGeneration", it) }
            recoveryId?.let { put("recoveryId", it) }
        }

    private fun expireStaleAssemblies() {
        val now = System.currentTimeMillis()
        val expired = assemblies.entries.filter { (_, a) ->
            now - a.lastSeen >= ASSEMBLY_TIMEOUT_MS
        }.map { it.key }.toList()
        if (expired.isNotEmpty()) {
            onLog?.invoke("[rpc] expiring ${expired.size} stale assembly(s)")
            expired.forEach { assemblies.remove(it) }
        }
    }

    fun sendMessage(bytes: ByteArray) {
        if (bytes.isEmpty()) throw IllegalArgumentException("empty message")
        if (bytes.size > MAX_MESSAGE_BYTES) throw IllegalArgumentException("message too large")
        val msgSeq = ++messageSeq
        val checksum = Crc32.hexOf(bytes)
        val fragmentCount = (bytes.size + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES
        if (fragmentCount > MAX_FRAGMENTS) throw IllegalArgumentException("fragment limit exceeded")
        for (i in 0 until fragmentCount) {
            val start = i * MAX_FRAGMENT_BYTES
            val end = minOf(start + MAX_FRAGMENT_BYTES, bytes.size)
            val chunk = ByteArray(end - start)
            System.arraycopy(bytes, start, chunk, 0, chunk.size)
            seq++
            sendPayload(buildMap<String, Any> {
                identity.filterValues { it != null }.forEach { (k, v) -> put(k, v as Any) }
                put("zcode_type", "rpc-frame")
                put("seq", seq)
                put("messageSeq", msgSeq)
                put("fragmentIndex", i)
                put("fragmentCount", fragmentCount)
                put("messageBytes", bytes.size)
                put("checksum", mapOf("algorithm" to "crc32", "value" to checksum))
                put("dataBase64", android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP))
            })
        }
    }

    fun acceptPayload(payload: Map<String, Any>): Boolean {
        val type = payload["zcode_type"] as? String ?: return false
        if (type != "rpc-frame" && type != "rpc-frame-ack") return false

        @Suppress("UNCHECKED_CAST")
        val dataBase64 = payload["dataBase64"] as? String
        val msgSeqVal = (payload["messageSeq"] as? Number)?.toInt() ?: return false
        val fragIdx = (payload["fragmentIndex"] as? Number)?.toInt() ?: return false
        val fragCount = (payload["fragmentCount"] as? Number)?.toInt() ?: return false
        val msgBytes = (payload["messageBytes"] as? Number)?.toInt() ?: return false
        val checksum = (payload["checksum"] as? Map<*, *>)?.get("value") as? String

        if (type == "rpc-frame-ack") {
            val ackSeq = (payload["ackMessageSeq"] as? Number)?.toInt()
                ?: (payload["messageSeq"] as? Number)?.toInt()
            if (ackSeq != null) assemblies.remove(ackSeq)
            return true
        }

        if (dataBase64 == null) return true
        if (fragIdx < 0 || fragIdx >= fragCount) return true

        val existing = assemblies[msgSeqVal]
        if (existing != null && existing.count == fragCount && existing.fragments[fragIdx] != null) {
            return true
        }
        val chunk = try { android.util.Base64.decode(dataBase64, android.util.Base64.NO_WRAP) } catch (_: Exception) { return true }

        val assembly = if (existing != null && existing.count == fragCount) {
            existing
        } else {
            if (existing != null) assemblies.remove(msgSeqVal)
            Assembly(crc32 = checksum ?: "", count = fragCount).also { assemblies[msgSeqVal] = it }
        }
        assembly.fragments[fragIdx] = chunk
        assembly.received += 1
        assembly.lastSeen = System.currentTimeMillis()

        if (assembly.received == fragCount) {
            if (msgBytes <= 0 || msgBytes > MAX_MESSAGE_BYTES) {
                onLog?.invoke("[rpc] bad messageBytes=$msgBytes for msg $msgSeqVal")
                assemblies.remove(msgSeqVal)
                return true
            }
            val assembled = ByteArray(msgBytes)
            var offset = 0
            for (i in 0 until fragCount) {
                val frag = assembly.fragments[i]!!
                System.arraycopy(frag, 0, assembled, offset, frag.size)
                offset += frag.size
            }
            val actualChecksum = Crc32.hexOf(assembled)
            if (actualChecksum != assembly.crc32) {
                onLog?.invoke("[rpc] CRC32 mismatch for msg $msgSeqVal (expected=${assembly.crc32} actual=$actualChecksum)")
                assemblies.remove(msgSeqVal)
                return true
            }
            sendAck(msgSeqVal)
            onMessage?.invoke(assembled)
            assemblies.remove(msgSeqVal)
        } else {
            expireStaleAssemblies()
        }
        return true
    }

    private fun sendAck(messageSeq: Int) {
        sendPayload(buildMap<String, Any> {
            identity.filterValues { it != null }.forEach { (k, v) -> put(k, v as Any) }
            put("zcode_type", "rpc-frame-ack")
            put("ackMessageSeq", messageSeq)
        })
    }

    data class Assembly(
        val crc32: String,
        val count: Int,
        val fragments: Array<ByteArray?> = arrayOfNulls(count),
        var received: Int = 0,
        var lastSeen: Long = System.currentTimeMillis(),
    )

    fun dispose() { assemblies.clear() }
}
