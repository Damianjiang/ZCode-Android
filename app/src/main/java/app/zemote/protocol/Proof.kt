package app.zemote.protocol

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** IEEE CRC-32 (same polynomial/table semantics as the web client). */
object Crc32 {
    private val TABLE: IntArray = buildTable()

    private fun buildTable(): IntArray {
        val table = IntArray(256)
        for (i in 0 until 256) {
            var c = i.toLong()
            for (k in 0 until 8) {
                c = if ((c and 1L) != 0L) (0xEDB88320L xor (c ushr 1)) else (c ushr 1)
            }
            table[i] = (c and 0xFFFFFFFFL).toInt()
        }
        return table
    }

    fun compute(bytes: ByteArray): Int {
        var crc: Long = 0xFFFFFFFFL
        for (b in bytes) {
            val idx = ((crc xor b.toLong()) and 0xFFL).toInt()
            crc = ((TABLE[idx].toLong() xor crc) ushr 8) and 0xFFFFFFFFL
        }
        return crc.toInt()
    }

    fun hexOf(bytes: ByteArray): String = compute(bytes).toString(16).padStart(8, '0')
}

/** HMAC-SHA256 proof calculation.
 *  Mirrors `aen()` / `ien()` in the web client:
 *  proof = base64url_nopad(HMAC-SHA256(key: utf8(passHash),
 *                                     msg: utf8('$nonce|$role|$deviceSid')))
 */
object Proof {
    fun calculate(passHash: String, nonce: String, role: String, deviceSid: String): String {
        val msg = "$nonce|$role|$deviceSid"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(msg.toByteArray(Charsets.UTF_8))
        // base64url nopad (strip trailing =)
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).trimEnd('=')
    }
}

fun generateUuid(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    fun hex(i: Int) = String.format("%02x", bytes[i])
    return "${hex(0)}${hex(1)}${hex(2)}${hex(3)}-${hex(4)}${hex(5)}-${hex(6)}${hex(7)}-${hex(8)}${hex(9)}-${hex(10)}${hex(11)}${hex(12)}${hex(13)}${hex(14)}${hex(15)}"
}

fun generateRequestId(prefix: String) = "$prefix-${generateUuid()}"
