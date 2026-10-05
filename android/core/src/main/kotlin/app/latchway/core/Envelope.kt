package app.latchway.core

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direction of an encrypted signaling message (PROTOCOL.md §4.2). */
enum class Direction(val byte: Int) {
    HOST_TO_JOINER(0x00),
    JOINER_TO_HOST(0x01),
}

internal object Aes {
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray, prefix: ByteArray = ByteArray(0)): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plaintext)
        return if (prefix.isEmpty()) ct else prefix + ct
    }

    /** Returns null when authentication fails. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, offset: Int = 0): ByteArray? {
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad)
            c.doFinal(ciphertext, offset, ciphertext.size - offset)
        } catch (_: Exception) {
            null
        }
    }
}

private fun sigNonce(dir: Direction, counter: Long): ByteArray {
    val n = ByteArray(12)
    n[0] = dir.byte.toByte()
    for (i in 0 until 8) n[11 - i] = (counter ushr (8 * i)).toByte()
    return n
}

/** Encrypts outgoing signaling messages with a strictly increasing counter. */
class Sealer(private val key: ByteArray, private val dir: Direction) {
    var counter: Long = 0
        private set

    fun seal(plaintext: ByteArray): String {
        val ct = Aes.seal(key, sigNonce(dir, counter), Labels.AAD_SIG, plaintext)
        counter++
        return B64.encode(ct)
    }
}

/** Decrypts incoming signaling messages, rejecting replays and gaps. */
class Opener(private val key: ByteArray, private val dir: Direction) {
    private var expected: Long = 0

    /** Null means the session must be aborted: impostor or tampering. */
    fun open(c: String): ByteArray? {
        val ct = B64.decode(c) ?: return null
        val pt = Aes.open(key, sigNonce(dir, expected), Labels.AAD_SIG, ct) ?: return null
        expected++
        return pt
    }
}
