package app.zemote.protocol

/**
 * RPC frame transport: logical messages split into frames with CRC32 checksum.
 *
 * Outbound: [sendMessage()] takes the full IPC frame body (with or without the
 *   13-byte framing header -- [ChannelClient] includes it), fragments it, and
 *   sends rpc-frames.
 *
 * Inbound:  [acceptPayload()] reassembles fragments, verifies CRC32, and emits
 *   the complete assembled bytes (13-byte header + encoded value-list) to
 *   [onMessage]. The caller (ChannelClient.handleMessage) decodes the value-list
 *   header itself. Do NOT pre-strip the IPC framing header here.
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
        /**
         * Timeout for in-progress frame assemblies. Mirrors the web client's
         * logicalFrameAssemblyTimeoutMs (default 5000ms). Old/incomplete assemblies
         * are discarded to prevent them from blocking new messages.
         */
        const val ASSEMBLY_TIMEOUT_MS = 5_000L
    }

    private var seq = 0
    private var messageSeq = 0

    /**
     * Incomplete (partially arrived) message reassembly table.
     * Uses ConcurrentHashMap: writes happen in bridge's inbound collect coroutine,
     * [dispose] clears from another thread, normal HashMap has concurrency risk.
     */
    private val assemblies = java.util.concurrent.ConcurrentHashMap<Int, Assembly>()

    /**
     * Callback fired when a complete, CRC32-verified message is reassembled.
     * The [frame] is the FULL assembled bytes including the 13-byte IPC framing
     * header -- the caller is responsible for decoding the value-list inside.
     */
    var onMessage: ((ByteArray) -> Unit)? = null

    private val identity: Map<String, Any?>
        get() = buildMap {
            put("bridgeSessionId", bridgeSessionId)
            bridgeGeneration?.let { put("bridgeGeneration", it) }
            recoveryId?.let { put("recoveryId", it) }
        }

    /**
     * Cleans up any assemblies that have exceeded the timeout, preventing
     * partial message data from blocking subsequent messages.
     * Mirrors the web client's LogicalFrameAssembler timeout cleanup.
     */
    private fun expireStaleAssemblies() {
        val now = System.currentTimeMillis()
        val expired = assemblies.entries.filter { (_, a) ->
            now - a.lastSeen >= ASSEMBLY_TIMEOUT_MS
        }.map { it.key }.toList()
        if (expired.isNotEmpty()) {
            onLog?.invoke("[rpc] expiring ${expired.size} stale assembly(s) (>${ASSEMBLY_TIMEOUT_MS}ms)")
            expired.forEach { assemblies.remove(it) }
        }
    }

    /**
     * Fragments [bytes] into rpc-frames and sends them via [sendPayload].
     * [bytes] should be the complete IPC message (with 13-byte framing header
     * if present -- [ChannelClient] includes it).
     */
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

    /**
     * Receives an incoming rpc-frame payload and assembles fragments.
     * Emits the complete assembled message (with 13-byte IPC header intact) to
     * [onMessage] once all fragments arrive and CRC32 checks out.
     */
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
            // Server ack carries ackMessageSeq field
            val ackSeq = (payload["ackMessageSeq"] as? Number)?.toInt()
                ?: (payload["messageSeq"] as? Number)?.toInt()
            if (ackSeq != null) assemblies.remove(ackSeq)
            return true
        }

        if (dataBase64 == null) return true
        // Out-of-bounds fragment index: drop directly to avoid ArrayIndexOutOfBoundsException
        if (fragIdx < 0 || fragIdx >= fragCount) return true

        // Dedup before decode: duplicate fragments (server retransmit / replay) dropped directly.
        // Old implementation unconditionally decoded first then wrote, wasting a max 512KB
        // decode on retransmit, and this code runs in the bridge's single inbound collect
        // coroutine which would directly slow down all subsequent frames.
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
        // Use counter instead of `fragments.all { it != null }`: old写法每收到一个分片都要
        // 把整个数组扫一遍（64 分片 = 每分片 64 次检查），这里是入站热路径。
        assembly.received += 1
        assembly.lastSeen = System.currentTimeMillis()

        if (assembly.received == fragCount) {
            if (msgBytes <= 0 || msgBytes > MAX_MESSAGE_BYTES) {
                onLog?.invoke("[rpc] bad messageBytes=$msgBytes for msg $msgSeqVal")
                assemblies.remove(msgSeqVal)
                return true
            }
            // Reassemble into complete message (includes 13-byte IPC header)
            val assembled = ByteArray(msgBytes)
            var offset = 0
            for (i in 0 until fragCount) {
                val frag = assembly.fragments[i]!!
                System.arraycopy(frag, 0, assembled, offset, frag.size)
                offset += frag.size
            }
            // Verify CRC32
            val actualChecksum = Crc32.hexOf(assembled)
            if (actualChecksum != assembly.crc32) {
                onLog?.invoke("[rpc] CRC32 mismatch for msg $msgSeqVal (expected=${assembly.crc32} actual=$actualChecksum)")
                assemblies.remove(msgSeqVal)
                return true
            }
            // Must ack after receiving complete message, otherwise server retransmits and
            // eventually判定 rpc-transport-fault
            sendAck(msgSeqVal)
            // Emit complete assembled message (with framing header) -- caller decodes
            onMessage?.invoke(assembled)
            assemblies.remove(msgSeqVal)
        } else {
            // Periodically clean up stale assemblies to prevent memory leaks
            // and blockage of new messages. Mirrors web client's frame assembly timeout.
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
