// Latchway core: the key schedule and proof of PROTOCOL.md §2 and §4.1.
// Pure JVM, no Android dependencies, so it is unit-tested against
// testdata/vectors.json exactly like the Go implementation.
package app.latchway.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Protocol {
    const val VERSION = 1
    const val CHUNK_SIZE = 64 * 1024
    const val SHARE_ID_LEN = 16
    const val SECRET_LEN = 32
    const val NONCE_LEN = 16
    const val KEY_LEN = 32
    const val REG_KEY_LEN = 32
    const val PBKDF2_ITERATIONS = 600_000
    const val MAX_SIGNAL_PAYLOAD = 16 * 1024
    const val META_SIZE = 1024
    const val MAX_NAME_BYTES = 255
}

internal object Labels {
    val PW = "latchway/v1/pw".toByteArray()
    val EXTRACT = "latchway/v1".toByteArray()
    val AUTH = "latchway/v1/auth".toByteArray()
    val ROOT = "latchway/v1/root".toByteArray()
    val PROOF = "latchway/v1/proof".toByteArray()
    val SESSION = "latchway/v1/session".toByteArray()
    val S_SIG = "latchway/v1/s/sig".toByteArray()
    val S_FILE = "latchway/v1/s/file".toByteArray()
    val AAD_SIG = "latchway/v1/sig".toByteArray()
    val AAD_CHUNK = "latchway/v1/chunk".toByteArray()
}

val secureRandom: SecureRandom by lazy { SecureRandom() }

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

/** Long-term keys derived from a link and optional password. */
class Keys(val auth: ByteArray, val root: ByteArray)

/** Per-session keys. */
class SessionKeys(val sig: ByteArray, val file: ByteArray)

object Hkdf {
    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - filled)
            System.arraycopy(previous, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }
}

object KeySchedule {
    /** PROTOCOL.md §2: derive K_auth and K_root. The password may be empty. */
    fun derive(shareId: ByteArray, secret: ByteArray, password: String): Keys {
        require(shareId.size == Protocol.SHARE_ID_LEN && secret.size == Protocol.SECRET_LEN)
        val pwk = if (password.isEmpty()) {
            ByteArray(Protocol.KEY_LEN)
        } else {
            val normalized = Normalizer.normalize(password, Normalizer.Form.NFC)
            val salt = Labels.PW + shareId
            pbkdf2(normalized, salt)
        }
        val ikm = secret + pwk
        val prk = Hkdf.extract(Labels.EXTRACT + shareId, ikm)
        return Keys(
            auth = Hkdf.expand(prk, Labels.AUTH, Protocol.KEY_LEN),
            root = Hkdf.expand(prk, Labels.ROOT, Protocol.KEY_LEN),
        )
    }

    /** The per-session step of §2. */
    fun session(root: ByteArray, hostNonce: ByteArray, joinerNonce: ByteArray): SessionKeys {
        val sprk = Hkdf.extract(Labels.SESSION + hostNonce + joinerNonce, root)
        return SessionKeys(
            sig = Hkdf.expand(sprk, Labels.S_SIG, Protocol.KEY_LEN),
            file = Hkdf.expand(sprk, Labels.S_FILE, Protocol.KEY_LEN),
        )
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        // PBEKeySpec takes a char array; Java's PBKDF2WithHmacSHA256 encodes
        // it as UTF-8, which is what the spec requires.
        val spec = PBEKeySpec(password.toCharArray(), salt, Protocol.PBKDF2_ITERATIONS, Protocol.KEY_LEN * 8)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(spec).encoded
    }
}

object Proof {
    /** HMAC-SHA256(K_auth, "latchway/v1/proof" || v || pw || hostNonce || joinerNonce). */
    fun compute(auth: ByteArray, version: Int, password: Boolean, hostNonce: ByteArray, joinerNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(auth, "HmacSHA256"))
        mac.update(Labels.PROOF)
        mac.update(byteArrayOf(version.toByte(), if (password) 1 else 0))
        mac.update(hostNonce)
        mac.update(joinerNonce)
        return mac.doFinal()
    }

    /** Constant-time comparison. */
    fun verify(auth: ByteArray, version: Int, password: Boolean, hostNonce: ByteArray, joinerNonce: ByteArray, proof: ByteArray): Boolean =
        MessageDigest.isEqual(compute(auth, version, password, hostNonce, joinerNonce), proof)
}

/** SHA-256 of a registration key, as the rendezvous stores it. */
fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
