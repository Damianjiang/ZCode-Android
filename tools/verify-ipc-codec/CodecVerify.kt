import app.zemote.protocol.ValueWriter
import app.zemote.protocol.ValueReader
import app.zemote.protocol.encodeValue
import app.zemote.protocol.decodeValue
import java.io.ByteArrayOutputStream

/**
 * IpcCodec 的独立验证。
 *
 * 目标：证明「无锁可增长缓冲 + readByte/readUtf8」与旧的
 * 「ByteArrayOutputStream + read(1)[0] + String(read(n))」在**字节层面完全等价**，
 * 且往返（encode → decode）对各类值都成立。
 */

// ── 旧实现的逐字节参考，用于对比 varint / 长度前缀 ──
private class LegacyWriter {
    val out = ByteArrayOutputStream()
    fun writeVarint(value: Int) {
        var v = value
        do {
            var byte = v and 0x7F
            v = v ushr 7
            if (v > 0) byte = byte or 0x80
            out.write(byte)
        } while (v > 0)
    }
    fun bytes(): ByteArray = out.toByteArray()
}

private var failures = 0
private fun check(name: String, cond: Boolean, extra: String = "") {
    if (cond) println("  PASS  $name") else { failures++; println("  FAIL  $name   $extra") }
}

private fun enc(v: Any?): ByteArray {
    val w = ValueWriter()
    encodeValue(w, v)
    return w.toByteArray()
}
private fun dec(b: ByteArray): Any? = decodeValue(ValueReader(b))
private fun roundTrip(v: Any?): Any? = dec(enc(v))

fun main() {
    println("== 1. varint 编码与旧实现逐字节一致 ==")
    val samples = listOf(
        0, 1, 63, 64, 127, 128, 255, 256, 16383, 16384,
        2097151, 2097152, 268435455, 268435456, Int.MAX_VALUE,
    )
    for (v in samples) {
        val nw = ValueWriter().also { it.writeVarint(v) }.toByteArray().toList()
        val od = LegacyWriter().also { it.writeVarint(v) }.bytes().toList()
        check("varint($v) = $nw", nw == od, "new=$nw old=$od")
    }

    println("== 2. varint 解码往返 ==")
    for (v in samples) {
        val got = ValueReader(ValueWriter().also { it.writeVarint(v) }.toByteArray()).readVarint()
        check("readVarint($v) = $got", got == v)
    }

    println("== 3. 整帧字节布局：tag + varint(len) + payload ==")
    val payload = "hello 世界 🎉"
    val pb = payload.toByteArray(Charsets.UTF_8)
    val manual = ByteArrayOutputStream().apply {
        write(1) // tag: String
        write(LegacyWriter().also { it.writeVarint(pb.size) }.bytes())
        write(pb)
    }.toByteArray().toList()
    val actual = enc(payload).toList()
    check("String 帧 = tag1 + varint(len) + utf8", actual == manual, "got=$actual want=$manual")

    val buf = ByteArray(300) { (it % 251).toByte() }
    val manualBuf = ByteArrayOutputStream().apply {
        write(3) // tag: VSBuffer
        write(LegacyWriter().also { it.writeVarint(buf.size) }.bytes())
        write(buf)
    }.toByteArray().toList()
    check("ByteArray 帧 = tag3 + varint(len) + bytes",
        enc(buf).toList() == manualBuf, "got=${enc(buf).toList()} want=$manualBuf")

    println("== 4. 各类型往返 ==")
    check("null", roundTrip(null) == null)
    check("多字节 String", roundTrip(payload) == payload)
    check("空 String", roundTrip("") == "")
    check("Int 0", roundTrip(0) == 0)
    check("Int 127", roundTrip(127) == 127)
    check("Int MAX", roundTrip(Int.MAX_VALUE) == Int.MAX_VALUE)
    // Long 走 tag 5（JSON 字符串），解码回来是 String —— 这是既有协议行为，
    // ConversationV4 的 `(m["rowId"] as? Number)?.toLong() ?: toString().toLongOrNull()`
    // 正是为此准备的。本次改动只换了 JSON 字符串的构造方式，未改变语义。
    check("Long 1757000000000 -> tag5 JSON 字符串", roundTrip(1757000000000L) == "1757000000000",
        "got=${roundTrip(1757000000000L)}")
    check("Int -1 -> tag5 JSON 字符串", roundTrip(-1) == "-1", "got=${roundTrip(-1)}")
    check("ByteArray 往返逐字节一致",
        (roundTrip(buf) as? ByteArray)?.contentEquals(buf) == true)
    check("空 ByteArray", (roundTrip(ByteArray(0)) as? ByteArray)?.isEmpty() == true)
    check("空 List", (roundTrip(emptyList<Any?>()) as? List<*>)?.isEmpty() == true)

    println("== 5. 嵌套结构（真实帧的形状） ==")
    val nested: Any = listOf(
        "a", 1, null,
        listOf("x", listOf("y", 2)),
        mapOf("k" to "v", "n" to 3.5, "b" to true, "l" to listOf(1, 2)),
    )
    val back = roundTrip(nested) as? List<*>
    check("顶层 List 还原 5 项", back != null && back.size == 5, "got=$back")
    check("第 2 层嵌套", (back?.get(3) as? List<*>)?.get(0) == "x")
    check("第 3 层嵌套", ((back?.get(3) as? List<*>)?.get(1) as? List<*>)?.get(0) == "y")
    val m = back?.get(4) as? Map<*, *>
    check("Map 字符串字段", m?.get("k") == "v")
    check("Map 布尔字段", m?.get("b") == true)
    check("Map 内嵌 List", (m?.get("l") as? List<*>)?.size == 2)

    println("== 6. 大块数据（缓冲增长路径） ==")
    val big = ByteArray(1 shl 20) { (it * 31 % 251).toByte() } // 1 MiB
    val bigBack = roundTrip(big) as? ByteArray
    check("1MiB ByteArray 逐字节一致", bigBack != null && bigBack.contentEquals(big),
        "size=${bigBack?.size}")
    val bigStr = "汉".repeat(200_000) // ~600KB UTF-8，多字节
    check("600KB 多字节 String 往返", roundTrip(bigStr) == bigStr)
    val many = (0 until 50_000).map { "item-$it" }
    check("5 万元素 List 往返", (roundTrip(many) as? List<*>)?.size == 50_000)

    println("== 7. readUtf8 与 String(read(n)) 等价 ==")
    val s = "abc 汉字 🎉 emoji"
    val sb = s.toByteArray(Charsets.UTF_8)
    val viaNew = ValueReader(sb).let { it.readUtf8(sb.size) }
    val viaOld = ValueReader(sb).let { String(it.read(sb.size), Charsets.UTF_8) }
    check("readUtf8 结果一致", viaNew == s && viaOld == s && viaNew == viaOld,
        "new=$viaNew old=$viaOld")
    val rPos = ValueReader(sb).also { it.readUtf8(sb.size) }
    check("readUtf8 推进 pos", rPos.pos == sb.size, "pos=${rPos.pos}")

    println("== 8. 边界与异常：抛异常而不是崩溃 ==")
    check("read(0) 返回空数组", ValueReader(ByteArray(0)).read(0).isEmpty())
    runCatching { ValueReader(ByteArray(4)).read(-1) }.let {
        check("read(-1) -> IllegalArgumentException",
            it.exceptionOrNull() is IllegalArgumentException, "got=${it.exceptionOrNull()}")
    }
    runCatching { ValueReader(ByteArray(0)).readByte() }.let {
        check("空输入 readByte 抛异常", it.isFailure)
    }
    runCatching { ValueReader(ByteArray(2)).readUtf8(-5) }.let {
        check("readUtf8(-5) -> IllegalArgumentException",
            it.exceptionOrNull() is IllegalArgumentException, "got=${it.exceptionOrNull()}")
    }
    runCatching { dec(ByteArray(0)) }.let {
        check("空帧 decode 抛异常", it.isFailure)
    }
    runCatching { dec(byteArrayOf(99)) }.let {
        check("未知 tag 抛异常", it.isFailure)
    }
    runCatching { dec(enc("hello").copyOf(3)) }.let {
        check("截断帧抛异常", it.isFailure)
    }
    // 0xFFFFFFFF 作为长度：旧实现会走到 ByteArray(负数) 抛 NegativeArraySizeException
    val negLen = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)
    runCatching { dec(negLen) }.let {
        check("负长度 varint -> IllegalArgumentException（不是 NegativeArraySizeException）",
            it.exceptionOrNull() is IllegalArgumentException, "got=${it.exceptionOrNull()}")
    }
    // 伪造的超大 count 不应直接预分配大块内存
    val hugeCount = byteArrayOf(4, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07)
    runCatching { dec(hugeCount) }.let {
        check("超大 list count 抛异常且不 OOM", it.isFailure)
    }

    println("== 9. 实例隔离 ==")
    val e1 = enc("aaa")
    val e2 = enc("bbb")
    check("两次编码互不影响", dec(e1) == "aaa" && dec(e2) == "bbb")
    check("toByteArray 返回精确长度（tag+varint+3 = 5）", e1.size == 5, "size=${e1.size}")
    check("空 writer -> 空数组", ValueWriter().toByteArray().isEmpty())

    println()
    if (failures == 0) println("全部通过") else println("失败 $failures 项")
    if (failures > 0) kotlin.system.exitProcess(1)
}
