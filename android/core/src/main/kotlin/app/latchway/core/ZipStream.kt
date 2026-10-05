package app.latchway.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32

/**
 * Streams several files as one ZIP archive in a single pass, so "send
 * three photos" is one Latchway transfer with the size known up front.
 *
 * Entries use method 8 (DEFLATE) but the data is written as DEFLATE
 * *stored* blocks that this class emits itself: no compression (the bytes
 * are encrypted on the wire anyway), yet the exact length is computable
 * before any file is read, and CRCs can follow each entry in a data
 * descriptor, which every reader accepts for method 8. Zip64 records are
 * used when anything exceeds 4 GB, so there is no size limit.
 *
 * Pure JVM, unit-tested; the app passes content-resolver streams in.
 */
class ZipStream(private val entries: List<Entry>) : InputStream() {
    class Entry(val name: String, val size: Long, val open: () -> InputStream) {
        init {
            require(size >= 0) { "entry size must be known" }
        }

        internal val nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8)

        /** Raw bytes plus one 5-byte stored-block header per 65535 bytes. */
        internal val compressedSize: Long get() = size + 5 * maxOf(1L, (size + BLOCK - 1) / BLOCK)
    }

    private val zip64: Boolean =
        entries.any { it.size >= LIMIT32 || it.compressedSize >= LIMIT32 } || entries.size >= 0xFFFF || layoutSize(false) >= LIMIT32

    /** The exact number of bytes this stream produces. */
    val totalSize: Long = layoutSize(zip64)

    private fun layoutSize(z: Boolean): Long {
        var total = 0L
        for (e in entries) total += 30 + e.nameBytes.size + (if (z) 20 else 0) + e.compressedSize + (if (z) 24 else 16)
        for (e in entries) total += 46 + e.nameBytes.size + (if (z) 28 else 0)
        if (z) total += 56 + 20
        return total + 22
    }

    private enum class State { HEADER, DATA, DESCRIPTOR, CENTRAL, END, DONE }

    private var state = State.HEADER
    private var index = 0
    private var pending = ByteArray(0)
    private var pendingPos = 0
    private var position = 0L
    private var source: InputStream? = null
    private var remaining = 0L
    private val crc = CRC32()
    private val crcs = LongArray(entries.size)
    private val offsets = LongArray(entries.size)
    private var centralStart = 0L
    private var centralSize = 0L
    private val block = ByteArray(BLOCK + 5)
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("stream closed")
        if (len == 0) return 0
        while (true) {
            if (pendingPos < pending.size) {
                val n = minOf(len, pending.size - pendingPos)
                System.arraycopy(pending, pendingPos, b, off, n)
                pendingPos += n
                position += n
                return n
            }
            when (state) {
                State.HEADER -> {
                    if (index >= entries.size) {
                        state = State.CENTRAL
                        continue
                    }
                    val e = entries[index]
                    offsets[index] = position
                    pending = localHeader(e)
                    pendingPos = 0
                    source = e.open()
                    remaining = e.size
                    crc.reset()
                    state = State.DATA
                }
                State.DATA -> {
                    val e = entries[index]
                    // One stored block: header, then up to 65535 raw bytes.
                    val want = minOf(remaining, BLOCK.toLong()).toInt()
                    var got = 0
                    val src = source!!
                    while (got < want) {
                        val n = src.read(block, 5 + got, want - got)
                        if (n < 0) throw IOException("${e.name} is shorter than announced")
                        got += n
                    }
                    crc.update(block, 5, got)
                    remaining -= got
                    val last = remaining == 0L
                    block[0] = if (last) 1 else 0 // BFINAL, BTYPE=00
                    block[1] = (got and 0xff).toByte()
                    block[2] = ((got ushr 8) and 0xff).toByte()
                    block[3] = (got.inv() and 0xff).toByte()
                    block[4] = ((got.inv() ushr 8) and 0xff).toByte()
                    pending = block.copyOf(5 + got)
                    pendingPos = 0
                    if (last) {
                        src.close()
                        source = null
                        state = State.DESCRIPTOR
                    }
                }
                State.DESCRIPTOR -> {
                    crcs[index] = crc.value
                    pending = descriptor(entries[index], crcs[index])
                    pendingPos = 0
                    index++
                    state = State.HEADER
                }
                State.CENTRAL -> {
                    centralStart = position
                    pending = centralDirectory()
                    centralSize = pending.size.toLong()
                    pendingPos = 0
                    state = State.END
                }
                State.END -> {
                    pending = endRecords()
                    pendingPos = 0
                    state = State.DONE
                }
                State.DONE -> return -1
            }
        }
    }

    override fun close() {
        closed = true
        source?.close()
        source = null
    }

    // --- records ------------------------------------------------------------------

    private class Out {
        private val bytes = ByteArrayOutputStream()
        fun u16(v: Int) { bytes.write(v and 0xff); bytes.write((v ushr 8) and 0xff) }
        fun u32(v: Long) { for (i in 0 until 4) bytes.write(((v ushr (8 * i)) and 0xff).toInt()) }
        fun u64(v: Long) { for (i in 0 until 8) bytes.write(((v ushr (8 * i)) and 0xff).toInt()) }
        fun raw(b: ByteArray) = bytes.write(b)
        fun bytes(): ByteArray = bytes.toByteArray()
    }

    private fun localHeader(e: Entry): ByteArray = Out().run {
        u32(0x04034b50)
        u16(if (zip64) 45 else 20)
        u16(FLAGS)
        u16(METHOD_DEFLATE)
        u16(DOS_TIME)
        u16(DOS_DATE)
        u32(0) // crc, in the descriptor
        if (zip64) { u32(LIMIT32); u32(LIMIT32) } else { u32(e.compressedSize); u32(e.size) }
        u16(e.nameBytes.size)
        u16(if (zip64) 20 else 0)
        raw(e.nameBytes)
        if (zip64) { u16(1); u16(16); u64(e.size); u64(e.compressedSize) }
        bytes()
    }

    private fun descriptor(e: Entry, crc: Long): ByteArray = Out().run {
        u32(0x08074b50)
        u32(crc)
        if (zip64) { u64(e.compressedSize); u64(e.size) } else { u32(e.compressedSize); u32(e.size) }
        bytes()
    }

    private fun centralDirectory(): ByteArray = Out().run {
        entries.forEachIndexed { i, e ->
            u32(0x02014b50)
            u16(if (zip64) 45 else 20)
            u16(if (zip64) 45 else 20)
            u16(FLAGS)
            u16(METHOD_DEFLATE)
            u16(DOS_TIME)
            u16(DOS_DATE)
            u32(crcs[i])
            if (zip64) { u32(LIMIT32); u32(LIMIT32) } else { u32(e.compressedSize); u32(e.size) }
            u16(e.nameBytes.size)
            u16(if (zip64) 28 else 0)
            u16(0) // comment length
            u16(0) // disk number
            u16(0) // internal attributes
            u32(0) // external attributes
            u32(if (zip64) LIMIT32 else offsets[i])
            raw(e.nameBytes)
            if (zip64) { u16(1); u16(24); u64(e.size); u64(e.compressedSize); u64(offsets[i]) }
        }
        bytes()
    }

    private fun endRecords(): ByteArray = Out().run {
        if (zip64) {
            val zip64End = position
            u32(0x06064b50)
            u64(44)
            u16(45)
            u16(45)
            u32(0)
            u32(0)
            u64(entries.size.toLong())
            u64(entries.size.toLong())
            u64(centralSize)
            u64(centralStart)
            u32(0x07064b50)
            u32(0)
            u64(zip64End)
            u32(1)
        }
        u32(0x06054b50)
        u16(0)
        u16(0)
        u16(if (zip64) 0xFFFF else entries.size)
        u16(if (zip64) 0xFFFF else entries.size)
        u32(if (zip64) LIMIT32 else centralSize)
        u32(if (zip64) LIMIT32 else centralStart)
        u16(0)
        bytes()
    }

    companion object {
        private const val BLOCK = 65535
        private const val LIMIT32 = 0xFFFFFFFFL
        private const val FLAGS = 0x0808 // bit 3: data descriptor; bit 11: UTF-8 names
        private const val METHOD_DEFLATE = 8
        private const val DOS_TIME = 0
        private const val DOS_DATE = 0x21 // 1980-01-01

        /** Makes entry names safe and unique inside the archive. */
        fun uniqueNames(names: List<String>): List<String> {
            val seen = HashMap<String, Int>()
            return names.map { raw ->
                var n = raw.replace('\\', '/').substringAfterLast('/').trim()
                if (n.isEmpty() || n == "." || n == "..") n = "file"
                val count = seen[n] ?: 0
                seen[n] = count + 1
                if (count == 0) n else {
                    val dot = n.lastIndexOf('.')
                    if (dot > 0) "${n.substring(0, dot)} ($count)${n.substring(dot)}" else "$n ($count)"
                }
            }
        }
    }
}
