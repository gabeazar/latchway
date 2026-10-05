package app.latchway.core

/** Chunk framing (PROTOCOL.md §4.4): index u32, flags u8, AES-GCM body. */
object Frame {
    const val HEADER_LEN = 5
    const val FLAG_LAST: Int = 0x01
    const val TAG_LEN = 16
    const val MAX_LEN = HEADER_LEN + Protocol.CHUNK_SIZE + TAG_LEN
}

private fun chunkNonce(index: Long): ByteArray {
    val n = ByteArray(12)
    putU32(n, 8, index)
    return n
}

private fun chunkAad(index: Long, flags: Int): ByteArray {
    val aad = ByteArray(Labels.AAD_CHUNK.size + 5)
    System.arraycopy(Labels.AAD_CHUNK, 0, aad, 0, Labels.AAD_CHUNK.size)
    putU32(aad, Labels.AAD_CHUNK.size, index)
    aad[Labels.AAD_CHUNK.size + 4] = flags.toByte()
    return aad
}

internal fun putU32(b: ByteArray, off: Int, v: Long) {
    b[off] = (v ushr 24).toByte()
    b[off + 1] = (v ushr 16).toByte()
    b[off + 2] = (v ushr 8).toByte()
    b[off + 3] = v.toByte()
}

internal fun getU32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
        ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

/** Seals file chunks with S_file. */
class ChunkSealer(private val key: ByteArray) {
    fun seal(index: Long, last: Boolean, plaintext: ByteArray, length: Int = plaintext.size): ByteArray {
        require(length <= Protocol.CHUNK_SIZE) { "chunk too large" }
        val flags = if (last) Frame.FLAG_LAST else 0
        val header = ByteArray(Frame.HEADER_LEN)
        putU32(header, 0, index)
        header[4] = flags.toByte()
        val body = if (length == plaintext.size) plaintext else plaintext.copyOf(length)
        return Aes.seal(key, chunkNonce(index), chunkAad(index, flags), body, header)
    }
}

/** Opens file chunks and enforces strict ordering. */
class ChunkOpener(private val key: ByteArray) {
    var next: Long = 0
        private set
    var done: Boolean = false
        private set

    class Result(val plaintext: ByteArray, val last: Boolean)

    /** Throws on any violation: the transfer must be aborted. */
    fun open(frame: ByteArray): Result {
        if (done) throw ChunkException("frame after last chunk")
        if (frame.size < Frame.HEADER_LEN + Frame.TAG_LEN || frame.size > Frame.MAX_LEN) throw ChunkException("frame has impossible length")
        val index = getU32(frame, 0)
        val flags = frame[4].toInt() and 0xff
        if (index != next) throw ChunkException("chunk $index out of order (expected $next)")
        if (flags and Frame.FLAG_LAST.inv() != 0) throw ChunkException("unknown chunk flags")
        val pt = Aes.open(key, chunkNonce(index), chunkAad(index, flags), frame, Frame.HEADER_LEN)
            ?: throw ChunkException("chunk failed authentication")
        val last = flags and Frame.FLAG_LAST != 0
        if (!last && pt.size != Protocol.CHUNK_SIZE) throw ChunkException("short chunk before the last one")
        next++
        done = last
        return Result(pt, last)
    }
}

class ChunkException(message: String) : Exception(message)
