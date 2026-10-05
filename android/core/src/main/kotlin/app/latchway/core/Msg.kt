package app.latchway.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** RTCIceServer as the rendezvous sends it. */
@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/**
 * The single JSON shape used for every rendezvous, signaling and
 * data-channel control message (PROTOCOL.md §3–4). Only `t` is always
 * present. Field names match the wire exactly.
 */
@Serializable
data class Msg(
    val t: String,
    // Rendezvous layer.
    val sid: String? = null,
    val d: String? = null,
    val ice: List<IceServer>? = null,
    val code: String? = null,
    // Session, plaintext.
    val v: Int? = null,
    val pw: Boolean? = null,
    val n: String? = null,
    val p: String? = null,
    // Encrypted envelope.
    val c: String? = null,
    // Encrypted session messages.
    val name: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val chunk: Int? = null,
    val from: String? = null,
    val approval: Boolean? = null,
    val sdp: String? = null,
    val start: Long? = null,
    val cand: String? = null,
    val mid: String? = null,
    val mline: Int? = null,
    // Registration.
    val reg: String? = null,
    // Padding for meta.
    val pad: String? = null,
    // Data-channel control.
    val bytes: Long? = null,
    val reason: String? = null,
) {
    fun encode(): String = Wire.json.encodeToString(this)
    fun encodeBytes(): ByteArray = encode().toByteArray()

    companion object {
        /** Returns null for malformed input or a message without a type. */
        fun decode(text: String): Msg? = try {
            val m = Wire.json.decodeFromString<Msg>(text)
            if (m.t.isEmpty()) null else m
        } catch (_: Exception) {
            null
        }

        fun decode(bytes: ByteArray): Msg? = decode(String(bytes, Charsets.UTF_8))
    }
}

object Wire {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    // Message types.
    const val T_REGISTER = "register"
    const val T_READY = "ready"
    const val T_JOINED = "joined"
    const val T_JOIN = "join"
    const val T_SIG = "sig"
    const val T_LEAVE = "leave"
    const val T_ERROR = "error"
    const val T_PING = "ping"
    const val T_PONG = "pong"
    const val T_HELLO = "hello"
    const val T_AUTH = "auth"
    const val T_ENC = "enc"
    const val T_META = "meta"
    const val T_GO = "go"
    const val T_OFFER = "offer"
    const val T_ANSWER = "answer"
    const val T_ICE = "ice"
    const val T_ICE_DONE = "ice-done"
    const val T_PROGRESS = "progress"
    const val T_DONE = "done"
    const val T_ABORT = "abort"

    // Error codes.
    const val ERR_NOT_FOUND = "not_found"
    const val ERR_HOST_GONE = "host_gone"
    const val ERR_REPLACED = "replaced"
    const val ERR_FORBIDDEN = "forbidden"
    const val ERR_CLOSED = "closed"
    const val ERR_BAD_AUTH = "bad_auth"
    const val ERR_DENIED = "denied"
    const val ERR_EXPIRED = "expired"
    const val ERR_BUSY = "busy"
    const val ERR_RATE_LIMITED = "rate_limited"
    const val ERR_TIMEOUT = "timeout"
    const val ERR_PROTOCOL = "protocol"
    const val ERR_INTERNAL = "internal"
}

/** Padded meta (PROTOCOL.md §4.3): the serialised JSON is exactly 1024 bytes. */
object Meta {
    private const val PAD_OVERHEAD = 9 // ,"pad":""

    fun truncateUtf8(s: String, max: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= max) return s
        var cut = max
        // Back up to a UTF-8 sequence start.
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return String(bytes, 0, cut, Charsets.UTF_8)
    }

    /** Builds the padded meta message bytes, or null if it cannot fit. */
    fun padded(name: String, size: Long, mime: String, from: String?, approval: Boolean): ByteArray? {
        val base = Msg(
            t = Wire.T_META,
            name = truncateUtf8(name, Protocol.MAX_NAME_BYTES),
            size = size,
            mime = truncateUtf8(mime, 128),
            chunk = Protocol.CHUNK_SIZE,
            from = from?.takeIf { it.isNotEmpty() }?.let { truncateUtf8(it, 64) },
            approval = approval,
        )
        val baseLen = base.encodeBytes().size
        val need = Protocol.META_SIZE - baseLen - PAD_OVERHEAD
        if (need < 0) return null
        val out = base.copy(pad = " ".repeat(need)).encodeBytes()
        return if (out.size == Protocol.META_SIZE) out else null
    }
}
