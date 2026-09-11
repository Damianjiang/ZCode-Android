package app.zemote.protocol

/**
 * IPC value codec mirroring the web client's Sm()/Cm().
 *
 * Type tags: Undefined=0, String=1, Buffer=2, VSBuffer=3, Array=4,
 *   Object=5 (JSON-string), Int=6.
 * Lengths/counts are encoded as 7-bit little-endian varints.
 */
class ValueWriter {
    private val builder = java.io.ByteArrayOutputStream()

    fun writeByte(v: Int) = builder.write(v and 0xFF)
    fun writeBytes(b: ByteArray) = builder.write(b)

    fun writeVarint(value: Int) {
        var v = value
        do {
            var byte = v and 0x7F
            v = v ushr 7
            if (v > 0) byte = byte or 0x80
            builder.write(byte)
        } while (v > 0)
    }

    fun toByteArray(): ByteArray = builder.toByteArray()
}

class ValueReader(private val data: ByteArray) {
    var pos = 0
    val remaining: Int get() = data.size - pos

    fun read(n: Int): ByteArray {
        if (pos + n > data.size) throw IllegalArgumentException("Not enough data")
        val out = ByteArray(n)
        System.arraycopy(data, pos, out, 0, n)
        pos += n
        return out
    }

    /**
     * Reads a 7-bit little-endian varint. Supports values up to 2^31-1.
     * Throws on overflow or truncated input.
     */
    fun readVarint(): Int {
        var value = 0
        var shift = 0
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            if (shift == 28 && (b and 0xF0) != 0) throw IllegalArgumentException("varint overflow")
            value = value or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return value
            shift += 7
            if (shift >= 35) break
        }
        throw IllegalArgumentException("invalid varint")
    }
}

private const val MAX_CONTAINER_ITEMS = 100_000
private const val MAX_VALUE_BYTES = 16 * 1024 * 1024

/**
 * Encodes a Kotlin value into the IPC wire format.
 * - null → tag 0
 * - String → tag 1 + UTF-8 bytes
 * - ByteArray → tag 3 (VSBuffer)
 * - List → tag 4 + recursive encode of each element
 * - Int (0..2^31-1) → tag 6
 * - Long / other → tag 5 (JSON-encoded string)
 */
fun encodeValue(w: ValueWriter, value: Any?) {
    if (value == null) {
        w.writeByte(0)
    } else when (value) {
        is String -> {
            val bytes = value.toByteArray(Charsets.UTF_8)
            w.writeByte(1)
            w.writeVarint(bytes.size)
            w.writeBytes(bytes)
        }
        is ByteArray -> {
            w.writeByte(3)
            w.writeVarint(value.size)
            w.writeBytes(value)
        }
        is List<*> -> {
            w.writeByte(4)
            w.writeVarint(value.size)
            value.forEach { encodeValue(w, it) }
        }
        is Int -> {
            if (value in 0..0x7FFFFFFF) {
                w.writeByte(6)
                w.writeVarint(value)
            } else encodeValue(w, value.toLong())
        }
        is Long -> {
            val json = com.google.gson.JsonPrimitive(value.toString()).toString()
            val bytes = json.toByteArray(Charsets.UTF_8)
            w.writeByte(5)
            w.writeVarint(bytes.size)
            w.writeBytes(bytes)
        }
        else -> {
            val json = com.google.gson.Gson().toJson(value)
            val bytes = json.toByteArray(Charsets.UTF_8)
            w.writeByte(5)
            w.writeVarint(bytes.size)
            w.writeBytes(bytes)
        }
    }
}

/**
 * Decodes a value from the IPC wire format. Returns Any? (JSON objects decode
 * to LinkedHashMap via Gson).
 */
fun decodeValue(r: ValueReader): Any? {
    val tag = r.read(1)[0].toInt() and 0xFF
    return when (tag) {
        0 -> null
        1 -> {
            val len = r.readVarint()
            if (len > MAX_VALUE_BYTES) throw IllegalArgumentException("string too large")
            String(r.read(len), Charsets.UTF_8)
        }
        2, 3 -> {
            val len = r.readVarint()
            if (len > MAX_VALUE_BYTES) throw IllegalArgumentException("bytes too large")
            r.read(len)
        }
        4 -> {
            val count = r.readVarint()
            if (count > MAX_CONTAINER_ITEMS) throw IllegalArgumentException("list too large")
            val list = mutableListOf<Any?>()
            repeat(count) { list.add(decodeValue(r)) }
            list
        }
        5 -> {
            val len = r.readVarint()
            if (len > MAX_VALUE_BYTES) throw IllegalArgumentException("object too large")
            val json = String(r.read(len), Charsets.UTF_8)
            com.google.gson.Gson().fromJson(json, Any::class.java)
        }
        6 -> r.readVarint()
        else -> throw IllegalArgumentException("unknown value tag $tag")
    }
}

/**
 * 13-byte IPC framing header: [type:u8][id:u32be][ack:u32be][bodyLen:u32be]
 * Mirrors `Mne()` / `Nne` in the web client.
 */
object IpcFraming {
    const val TYPE_REGULAR = 1

    fun encode(body: ByteArray): ByteArray {
        val out = ByteArray(13 + body.size)
        val view = java.nio.ByteBuffer.wrap(out)
        view.put(TYPE_REGULAR.toByte())
        view.putInt(0)  // id
        view.putInt(0)  // ack
        view.putInt(body.size)
        view.put(body)
        return out
    }
}
