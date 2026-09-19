package app.zemote.protocol

/**
 * IPC value codec mirroring the web client's Sm()/Cm().
 *
 * Type tags: Undefined=0, String=1, Buffer=2, VSBuffer=3, Array=4,
 *   Object=5 (JSON-string), Int=6.
 * Lengths/counts are encoded as 7-bit little-endian varints.
 *
 * ── 性能约定（改这个文件前请先读） ──
 * 这里是**每一帧双向都必经**的热路径：历史窗口、附件分片的帧动辄几百 KB 到几 MB，
 * 任何逐字节的额外开销都会被放大成「通过 bridge 获取信息慢」。两条硬性要求：
 *
 * 1. **写入端不要用 `java.io.ByteArrayOutputStream`**。它的 `write(int)` 与
 *    `write(byte[],int,int)` 都带 `synchronized`，而本类只在单线程内使用 ——
 *    一次 512KB 的分片等于五十多万次取监视器锁，扩容时还要反复整块复制。
 *    改用无锁可增长数组（见 [ValueWriter]）。
 * 2. **读取端不要为了读一个字节而分配数组**，也不要在能直接构造 String 的地方
 *    先复制一份 ByteArray。`String(data, pos, len, UTF_8)` 与
 *    `String(read(len), UTF_8)` 结果相同，但后者白复制一次（tag 5 的 JSON 对象
 *    字符串可能有好几 MB）。
 */
/** Cached Gson instance — creating a new Gson() on every encode/decode is expensive. */
private val _gson = com.google.gson.Gson()

/** 零长度字节结果，避免 `read(0)` 每次分配新数组。 */
private val EMPTY_BYTES = ByteArray(0)

/**
 * 无锁的可增长字节缓冲。
 *
 * 旧实现用 `java.io.ByteArrayOutputStream`：逐字节写入要取监视器锁，扩容靠
 * `Arrays.copyOf` 整块复制。本类实际只在单线程内使用，锁是纯开销。
 * 这里改为容量翻倍的无锁实现，逐字节写入只剩一次数组边界检查。
 */
class ValueWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(if (initialCapacity > 0) initialCapacity else 16)
    private var len = 0

    private fun ensure(extra: Int) {
        val need = len + extra
        if (need <= buf.size) return
        var cap = buf.size
        while (cap < need) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    fun writeByte(v: Int) {
        ensure(1)
        buf[len++] = v.toByte()
    }

    fun writeBytes(b: ByteArray) {
        if (b.isEmpty()) return
        ensure(b.size)
        System.arraycopy(b, 0, buf, len, b.size)
        len += b.size
    }

    fun writeVarint(value: Int) {
        var v = value
        do {
            var byte = v and 0x7F
            v = v ushr 7
            if (v > 0) byte = byte or 0x80
            writeByte(byte)
        } while (v > 0)
    }

    /** 已写入的字节数（不含预留的未使用容量）。 */
    val size: Int get() = len

    fun toByteArray(): ByteArray = if (len == 0) EMPTY_BYTES else buf.copyOf(len)
}

class ValueReader(private val data: ByteArray) {
    var pos = 0
    val remaining: Int get() = data.size - pos

    /**
     * 读一个字节（0..255）。
     *
     * 旧实现是 `read(1)[0]` —— `decodeValue` 每解出一个值都要为 tag 读一次，
     * 一条历史帧里成千上万个嵌套值就是成千上万个 `ByteArray(1)` 分配。
     */
    fun readByte(): Int {
        if (pos >= data.size) throw IllegalArgumentException("Not enough data")
        return data[pos++].toInt() and 0xFF
    }

    fun read(n: Int): ByteArray {
        if (n == 0) return EMPTY_BYTES
        if (n < 0 || pos + n > data.size) throw IllegalArgumentException("Not enough data")
        val out = ByteArray(n)
        System.arraycopy(data, pos, out, 0, n)
        pos += n
        return out
    }

    /**
     * 读 n 个字节并直接按 UTF-8 解码为 String，**不复制中间字节数组**。
     * 旧实现 `String(read(len), UTF_8)` 对大字段会白复制一份同样大的 ByteArray。
     */
    fun readUtf8(n: Int): String {
        if (n < 0 || pos + n > data.size) throw IllegalArgumentException("Not enough data")
        val s = String(data, pos, n, Charsets.UTF_8)
        pos += n
        return s
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

/** 列表初始容量上限：避免被伪造的超大 count 直接预分配一大块内存。 */
private const val LIST_PRESIZE_CAP = 64

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
            // 与 `JsonPrimitive(value.toString()).toString()` 等价：
            // JsonPrimitive(String) 序列化出来就是带引号的 JSON 字符串，
            // 而纯数字不需要任何转义。这里省掉一次 JsonPrimitive + JsonWriter 分配。
            val json = "\"" + value.toString() + "\""
            val bytes = json.toByteArray(Charsets.UTF_8)
            w.writeByte(5)
            w.writeVarint(bytes.size)
            w.writeBytes(bytes)
        }
        else -> {
            val json = _gson.toJson(value)
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
    val tag = r.readByte()
    return when (tag) {
        0 -> null
        1 -> {
            val len = r.readVarint()
            if (len > MAX_VALUE_BYTES) throw IllegalArgumentException("string too large")
            r.readUtf8(len)
        }
        2, 3 -> {
            val len = r.readVarint()
            if (len > MAX_VALUE_BYTES) throw IllegalArgumentException("bytes too large")
            r.read(len)
        }
        4 -> {
            val count = r.readVarint()
            if (count > MAX_CONTAINER_ITEMS) throw IllegalArgumentException("list too large")
            // 预分配上限，兼顾常见小列表的分配次数与伪造 count 的内存风险
            val list = ArrayList<Any?>(count.coerceAtMost(LIST_PRESIZE_CAP))
            repeat(count) { list.add(decodeValue(r)) }
            list
        }
        5 -> {
            val len = r.readVarint()
            if (len > MAX_VALUE_BYTES) throw IllegalArgumentException("object too large")
            _gson.fromJson(r.readUtf8(len), Any::class.java)
        }
        6 -> r.readVarint()
        else -> throw IllegalArgumentException("unknown value tag $tag")
    }
}
