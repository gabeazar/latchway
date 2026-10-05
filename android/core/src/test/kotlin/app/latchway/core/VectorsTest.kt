package app.latchway.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Every value here must match testdata/vectors.json byte for byte. */
class VectorsTest {
    private val vectors = Json.parseToJsonElement(
        File(System.getProperty("latchway.vectors") ?: "../../testdata/vectors.json").readText(),
    ).jsonObject

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun str(key: String) = vectors[key]!!.jsonPrimitive.content

    private val shareId = B64.decode(str("share_id_b64"))!!
    private val secret = B64.decode(str("secret_b64"))!!
    private val hostNonce = hex(str("host_nonce_hex"))
    private val joinerNonce = hex(str("joiner_nonce_hex"))

    @Test
    fun linkParsesToTheFixture() {
        val parsed = assertNotNull(Share.parseLink(str("link")))
        assertEquals("latchway.app", parsed.host)
        assertContentEquals(shareId, parsed.share.id)
        assertContentEquals(secret, parsed.share.secret)
        assertEquals(str("link"), parsed.share.link("latchway.app"))
    }

    private fun checkKeys(section: String, password: String) {
        val v = vectors[section]!!.jsonObject
        val k = KeySchedule.derive(shareId, secret, password)
        assertEquals(v["k_auth_hex"]!!.jsonPrimitive.content, toHex(k.auth), "$section k_auth")
        assertEquals(v["k_root_hex"]!!.jsonPrimitive.content, toHex(k.root), "$section k_root")
        val proof = Proof.compute(k.auth, Protocol.VERSION, password.isNotEmpty(), hostNonce, joinerNonce)
        assertEquals(v["proof_b64"]!!.jsonPrimitive.content, B64.encode(proof), "$section proof")
        assertTrue(Proof.verify(k.auth, Protocol.VERSION, password.isNotEmpty(), hostNonce, joinerNonce, proof))
        val s = KeySchedule.session(k.root, hostNonce, joinerNonce)
        assertEquals(v["s_sig_hex"]!!.jsonPrimitive.content, toHex(s.sig), "$section s_sig")
        assertEquals(v["s_file_hex"]!!.jsonPrimitive.content, toHex(s.file), "$section s_file")
    }

    @Test
    fun keysWithoutPassword() = checkKeys("no_password", "")

    @Test
    fun keysWithPassword() = checkKeys("with_password", str("password"))

    @Test
    fun envelopeMatchesAndOpens() {
        val v = vectors["envelope"]!!.jsonObject
        val key = hex(v["key_hex"]!!.jsonPrimitive.content)
        val dir = if (v["direction"]!!.jsonPrimitive.content == "1") Direction.JOINER_TO_HOST else Direction.HOST_TO_JOINER
        val plaintexts = v["plaintexts"]!!.jsonArray.map { it.jsonPrimitive.content }
        val sealed = v["sealed_b64"]!!.jsonArray.map { it.jsonPrimitive.content }
        val sealer = Sealer(key, dir)
        plaintexts.forEachIndexed { i, pt -> assertEquals(sealed[i], sealer.seal(pt.toByteArray()), "sealed $i") }
        val opener = Opener(key, dir)
        sealed.forEachIndexed { i, c -> assertEquals(plaintexts[i], String(assertNotNull(opener.open(c)))) }
        // Replay must fail.
        assertNull(opener.open(sealed[0]))
    }

    @Test
    fun paddedMetaMatches() {
        val v = vectors["meta"]!!.jsonObject
        val padded = assertNotNull(
            Meta.padded(
                name = v["name"]!!.jsonPrimitive.content,
                size = v["size"]!!.jsonPrimitive.content.toLong(),
                mime = v["mime"]!!.jsonPrimitive.content,
                from = v["from"]!!.jsonPrimitive.content,
                approval = false,
            ),
        )
        assertEquals(v["padded_json"]!!.jsonPrimitive.content, String(padded))
        assertEquals(Protocol.META_SIZE, padded.size)
    }

    @Test
    fun chunkFramesMatchAndOpenInOrder() {
        val v = vectors["chunks"]!!.jsonObject
        val key = hex(v["key_hex"]!!.jsonPrimitive.content)
        val p0 = hex(v["plain0_hex"]!!.jsonPrimitive.content)
        val p1 = hex(v["plain1_hex"]!!.jsonPrimitive.content)
        val sealer = ChunkSealer(key)
        assertEquals(v["frame0_hex"]!!.jsonPrimitive.content, toHex(sealer.seal(0, false, p0)))
        assertEquals(v["frame1_hex"]!!.jsonPrimitive.content, toHex(sealer.seal(1, true, p1)))
        assertEquals(v["empty_final_frame_hex"]!!.jsonPrimitive.content, toHex(ChunkSealer(key).seal(0, true, ByteArray(0))))

        val opener = ChunkOpener(key)
        val f0 = hex(v["frame0_hex"]!!.jsonPrimitive.content)
        val f1 = hex(v["frame1_hex"]!!.jsonPrimitive.content)
        try {
            opener.open(f1)
            fail("out of order frame must be rejected")
        } catch (_: ChunkException) {
        }
        val r0 = opener.open(f0)
        assertContentEquals(p0, r0.plaintext)
        assertTrue(!r0.last)
        val r1 = opener.open(f1)
        assertContentEquals(p1, r1.plaintext)
        assertTrue(r1.last)
        try {
            opener.open(f1)
            fail("frames after last must be rejected")
        } catch (_: ChunkException) {
        }
    }
}
