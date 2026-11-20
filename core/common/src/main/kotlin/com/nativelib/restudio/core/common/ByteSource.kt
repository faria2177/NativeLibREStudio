package com.nativelib.restudio.core.common

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Thrown when a read would go outside the bounds of the underlying data. */
class OutOfBoundsException(message: String) : RuntimeException(message)

/**
 * Random-access, read-only view of binary data.
 *
 * All reads are bounds-checked: malformed input can never cause reads
 * outside the source. Implementations must be safe to use from one thread at a time.
 */
interface ByteSource : Closeable {
    val size: Long

    /** Reads exactly [length] bytes at [offset] into [dst] starting at [dstOffset]. */
    fun readFully(offset: Long, dst: ByteArray, dstOffset: Int = 0, length: Int = dst.size)

    fun readBytes(offset: Long, length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        val out = ByteArray(length)
        readFully(offset, out, 0, length)
        return out
    }

    fun checkRange(offset: Long, length: Long) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw OutOfBoundsException("Range [$offset, +$length) is outside data of size $size")
        }
    }

    fun contains(offset: Long, length: Long): Boolean =
        offset >= 0 && length >= 0 && offset <= size && length <= size - offset
}

/** In-memory source, mainly for small files and tests. */
class ByteArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()

    override fun readFully(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        checkRange(offset, length.toLong())
        System.arraycopy(data, offset.toInt(), dst, dstOffset, length)
    }

    override fun close() = Unit
}

/**
 * File-backed source using memory-mapped windows so large binaries are never
 * loaded into the Java heap. Windows are mapped lazily and cached (LRU of a few windows).
 */
class MappedFileSource(file: File, private val windowSize: Long = DEFAULT_WINDOW) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    override val size: Long = channel.size()

    private val windows = object : LinkedHashMap<Long, MappedByteBuffer>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MappedByteBuffer>?) = this.size > MAX_WINDOWS
    }

    private fun window(index: Long): MappedByteBuffer = windows.getOrPut(index) {
        val start = index * windowSize
        val len = minOf(windowSize, size - start)
        channel.map(FileChannel.MapMode.READ_ONLY, start, len)
    }

    override fun readFully(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        checkRange(offset, length.toLong())
        var pos = offset
        var written = 0
        while (written < length) {
            val idx = pos / windowSize
            val inWin = (pos - idx * windowSize).toInt()
            val buf = window(idx).duplicate()
            buf.position(inWin)
            val n = minOf(length - written, buf.remaining())
            buf.get(dst, dstOffset + written, n)
            written += n
            pos += n
        }
    }

    override fun close() {
        windows.clear()
        channel.close()
        raf.close()
    }

    companion object {
        const val DEFAULT_WINDOW: Long = 16L * 1024 * 1024
        private const val MAX_WINDOWS = 4
    }
}

/** Endian-aware primitive reader on top of a [ByteSource]. */
class BinaryReader(val source: ByteSource, var order: ByteOrder = ByteOrder.LITTLE_ENDIAN) {
    private val scratch = ByteArray(8)

    private fun buf(offset: Long, n: Int): ByteBuffer {
        source.readFully(offset, scratch, 0, n)
        return ByteBuffer.wrap(scratch, 0, n).order(order)
    }

    fun u8(offset: Long): Int = buf(offset, 1).get().toInt() and 0xFF
    fun u16(offset: Long): Int = buf(offset, 2).short.toInt() and 0xFFFF
    fun u32(offset: Long): Long = buf(offset, 4).int.toLong() and 0xFFFFFFFFL
    fun s32(offset: Long): Int = buf(offset, 4).int
    fun u64(offset: Long): Long = buf(offset, 8).long // unsigned value stored in a Long (two's complement)

    /** Reads a word that is 4 bytes for ELF32 and 8 bytes for ELF64. */
    fun word(offset: Long, is64: Boolean): Long = if (is64) u64(offset) else u32(offset)

    /** Reads a NUL-terminated string, bounded by [maxLen] and the end of the data. */
    fun cString(offset: Long, maxLen: Int = 4096, limit: Long = source.size): String? {
        if (offset < 0 || offset >= limit) return null
        val end = minOf(limit, offset + maxLen)
        val chunk = source.readBytes(offset, (end - offset).toInt())
        val nul = chunk.indexOf(0)
        val len = if (nul >= 0) nul else chunk.size
        return String(chunk, 0, len, Charsets.UTF_8)
    }
}
