package app.latchway.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkTest {
    @Test
    fun roundTrip() {
        val s = Share.create()
        val parsed = assertNotNull(Share.parseLink(s.link("latchway.app")))
        assertEquals("latchway.app", parsed.host)
        assertContentEquals(s.id, parsed.share.id)
        assertContentEquals(s.secret, parsed.share.secret)
        val deep = assertNotNull(Share.parseLink(s.deepLink("files.example.org:8443")))
        assertEquals("files.example.org:8443", deep.host)
    }

    @Test
    fun rejectsIncompleteOrForeignLinks() {
        val s = Share.create()
        assertNull(Share.parseLink("https://latchway.app/s/${s.idString}"))
        assertNull(Share.parseLink("http://latchway.app/s/${s.idString}#${s.secretString}"))
        assertNull(Share.parseLink("https://latchway.app/x/${s.idString}#${s.secretString}"))
        assertNull(Share.parseLink("latchway.app/s/${s.idString}#${s.secretString}"))
        assertNull(Share.parseLink("https://latchway.app/s/short#${s.secretString}"))
    }

    @Test
    fun canonicalIds() {
        val canon = B64.encode(ByteArray(16) { 0xff.toByte() })
        assertTrue(Share.isCanonicalId(canon))
        assertFalse(Share.isCanonicalId(canon.dropLast(1) + "x"))
    }

    @Test
    fun keysChangeWithPasswordAndNormalise() {
        val s = Share.create()
        val a = KeySchedule.derive(s.id, s.secret, "")
        val b = KeySchedule.derive(s.id, s.secret, "hunter2")
        assertFalse(a.auth.contentEquals(b.auth))
        val composed = KeySchedule.derive(s.id, s.secret, "café")
        val decomposed = KeySchedule.derive(s.id, s.secret, "café")
        assertContentEquals(composed.root, decomposed.root)
    }

    @Test
    fun metaTruncatesLongNamesOnRuneBoundaries() {
        val padded = assertNotNull(Meta.padded("é".repeat(300), 1L shl 40, "video/mp4", "Gabe", true))
        assertEquals(Protocol.META_SIZE, padded.size)
        val m = assertNotNull(Msg.decode(padded))
        assertEquals(Wire.T_META, m.t)
        assertEquals(Protocol.CHUNK_SIZE, m.chunk)
        assertTrue(m.name!!.toByteArray().size <= Protocol.MAX_NAME_BYTES)
    }

    @Test
    fun msgEncodingKeepsExplicitFalse() {
        val b = Msg(t = Wire.T_META, name = "a.bin", size = 5, chunk = Protocol.CHUNK_SIZE, approval = false).encode()
        assertTrue(b.contains("\"approval\":false"), b)
        assertNull(Msg.decode("{\"x\":1}"))
    }
}
