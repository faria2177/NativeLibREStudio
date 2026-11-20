package com.nativelib.restudio.core.common

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val BUF = 64 * 1024

    fun sha256(file: File, checkCancelled: () -> Unit = {}): String =
        file.inputStream().use { sha256(it, checkCancelled) }

    fun sha256(input: InputStream, checkCancelled: () -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUF)
        while (true) {
            checkCancelled()
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()

fun Long.toHexAddr(width: Int = 0): String {
    val s = java.lang.Long.toHexString(this)
    return "0x" + if (s.length < width) "0".repeat(width - s.length) + s else s
}

fun Int.toHexAddr(width: Int = 0): String = (this.toLong() and 0xFFFFFFFFL).toHexAddr(width)

fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, "%.2f %s", v, units[i])
}

/**
 * Minimal dependency-free JSON writer used for reproducible reports.
 * Output key order is the insertion order, so identical input gives identical output.
 */
class JsonWriter(private val indent: String = "  ") {
    private val sb = StringBuilder()
    private var depth = 0
    private val firstStack = ArrayDeque<Boolean>()

    private fun newline() { sb.append('\n'); repeat(depth) { sb.append(indent) } }

    private fun beforeValue() {
        if (firstStack.isEmpty()) return
        if (!firstStack.last()) sb.append(',')
        firstStack[firstStack.lastIndex] = false
        newline()
    }

    fun beginObject(): JsonWriter { beforeValue(); sb.append('{'); depth++; firstStack.addLast(true); return this }
    fun endObject(): JsonWriter = close('}')
    fun beginArray(): JsonWriter { beforeValue(); sb.append('['); depth++; firstStack.addLast(true); return this }
    fun endArray(): JsonWriter = close(']')

    private fun close(c: Char): JsonWriter {
        val empty = firstStack.removeLast()
        depth--
        if (!empty) newline()
        sb.append(c)
        return this
    }

    /** Writes an object key; the next call must write its value. */
    fun name(key: String): JsonWriter {
        beforeValue()
        quote(key); sb.append(": ")
        // the following value must not emit a separator
        firstStack.addLast(true); pendingName = true
        return this
    }

    private var pendingName = false

    private fun valueRaw(raw: String): JsonWriter {
        if (pendingName) { firstStack.removeLast(); pendingName = false; sb.append(raw); return this }
        beforeValue(); sb.append(raw); return this
    }

    fun value(v: String?): JsonWriter = if (v == null) valueRaw("null") else valueRaw(buildString { appendQuoted(this, v) })
    fun value(v: Long): JsonWriter = valueRaw(v.toString())
    fun value(v: Int): JsonWriter = valueRaw(v.toString())
    fun value(v: Boolean): JsonWriter = valueRaw(v.toString())

    fun field(key: String, v: String?) = name(key).value(v)
    fun field(key: String, v: Long) = name(key).value(v)
    fun field(key: String, v: Int) = name(key).value(v)
    fun field(key: String, v: Boolean) = name(key).value(v)

    fun objectField(key: String): JsonWriter { name(key); consumeName(); sb.append('{'); depth++; firstStack.addLast(true); return this }
    fun arrayField(key: String): JsonWriter { name(key); consumeName(); sb.append('['); depth++; firstStack.addLast(true); return this }

    private fun consumeName() { firstStack.removeLast(); pendingName = false }

    private fun quote(s: String) = appendQuoted(sb, s)

    override fun toString(): String = sb.toString()

    companion object {
        fun appendQuoted(sb: StringBuilder, s: String) {
            sb.append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
        }
    }
}
