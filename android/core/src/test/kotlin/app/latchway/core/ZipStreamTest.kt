package app.latchway.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipStreamTest {
    private fun entry(name: String, data: ByteArray) = ZipStream.Entry(name, data.size.toLong()) { ByteArrayInputStream(data) }

    private fun drain(z: ZipStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(7919) // odd size to cross every record boundary
        while (true) {
            val n = z.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun sizeIsExactAndEveryReaderAgrees() {
        val a = ByteArray(0)
        val b = "hello, latchway".toByteArray()
        val c = ByteArray(200_000) { (it * 7).toByte() } // spans several stored blocks
        val entries = listOf(entry("empty.txt", a), entry("hello.txt", b), entry("photos/big.bin", c))
        val z = ZipStream(entries)
        val bytes = drain(z)
        assertEquals(z.totalSize, bytes.size.toLong(), "announced size must match the bytes produced")

        // Streaming reader (local headers + descriptors).
        val seen = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                seen[e.name] = zin.readBytes()
            }
        }
        assertEquals(listOf("empty.txt", "hello.txt", "photos/big.bin"), seen.keys.toList())
        assertContentEquals(a, seen["empty.txt"])
        assertContentEquals(b, seen["hello.txt"])
        assertContentEquals(c, seen["photos/big.bin"])

        // Random-access reader (central directory).
        val f = File.createTempFile("latchway", ".zip")
        try {
            f.writeBytes(bytes)
            ZipFile(f).use { zf ->
                assertEquals(3, zf.size())
                assertContentEquals(c, zf.getInputStream(zf.getEntry("photos/big.bin")).readBytes())
                assertEquals(b.size.toLong(), zf.getEntry("hello.txt").size)
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun shortSourceIsAnError() {
        val e = ZipStream.Entry("x.bin", 10) { ByteArrayInputStream(ByteArray(3)) }
        val z = ZipStream(listOf(e))
        try {
            drain(z)
            throw AssertionError("expected an IOException")
        } catch (ex: java.io.IOException) {
            assertNull(null)
        }
    }

    @Test
    fun namesAreMadeUniqueAndSafe() {
        val names = ZipStream.uniqueNames(listOf("a.jpg", "dir/a.jpg", "..", "a.jpg", "noext", "noext"))
        assertEquals(listOf("a.jpg", "a (1).jpg", "file", "a (2).jpg", "noext", "noext (1)"), names)
    }
}
