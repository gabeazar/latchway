package app.latchway.core

import java.net.URI
import java.util.Base64

/** base64url without padding, the only encoding Latchway uses for bytes. */
object B64 {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val alphabet = Regex("^[A-Za-z0-9_-]*$")

    fun encode(b: ByteArray): String = enc.encodeToString(b)

    /** Decodes, or returns null for anything that is not clean base64url. */
    fun decode(s: String): ByteArray? {
        if (!alphabet.matches(s) || s.length % 4 == 1) return null
        return try {
            dec.decode(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** A share identity: public id, and the secret only the link carries. */
class Share(val id: ByteArray, val secret: ByteArray) {
    init {
        require(id.size == Protocol.SHARE_ID_LEN) { "share id must be 16 bytes" }
        require(secret.size == Protocol.SECRET_LEN) { "secret must be 32 bytes" }
    }

    val idString: String get() = B64.encode(id)
    val secretString: String get() = B64.encode(secret)

    fun link(host: String) = "https://$host/s/$idString#$secretString"
    fun deepLink(host: String) = "latchway://$host/s/$idString#$secretString"

    companion object {
        fun create() = Share(randomBytes(Protocol.SHARE_ID_LEN), randomBytes(Protocol.SECRET_LEN))

        private val shareIdRe = Regex("^[A-Za-z0-9_-]{22}$")
        private val secretRe = Regex("^[A-Za-z0-9_-]{43}$")

        /** A 22-character id is canonical only if it re-encodes to itself. */
        fun parseId(s: String): ByteArray? {
            if (!shareIdRe.matches(s)) return null
            val b = B64.decode(s) ?: return null
            if (b.size != Protocol.SHARE_ID_LEN || B64.encode(b) != s) return null
            return b
        }

        fun isCanonicalId(s: String) = parseId(s) != null

        /** Result of parsing a link: where the rendezvous is, and the share. */
        class Parsed(val host: String, val share: Share)

        /**
         * Accepts both link forms. Never logs or echoes the secret. Returns
         * null for anything that is not a complete Latchway link.
         */
        fun parseLink(raw: String): Parsed? {
            val text = raw.trim()
            val uri = try {
                URI(text)
            } catch (_: Exception) {
                return null
            }
            if (uri.scheme != "https" && uri.scheme != "latchway") return null
            val host = uri.authority ?: return null
            if (host.isEmpty()) return null
            val parts = (uri.rawPath ?: "").trim('/').split('/')
            if (parts.size != 2 || parts[0] != "s") return null
            val id = parseId(parts[1]) ?: return null
            val frag = uri.rawFragment ?: return null
            if (!secretRe.matches(frag)) return null
            val secret = B64.decode(frag) ?: return null
            if (secret.size != Protocol.SECRET_LEN) return null
            return Parsed(host, Share(id, secret))
        }
    }
}
