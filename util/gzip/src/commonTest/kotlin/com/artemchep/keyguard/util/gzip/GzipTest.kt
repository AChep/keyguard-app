package com.artemchep.keyguard.util.gzip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GzipTest {
    private class ExpectedException : Exception()

    @Test
    fun `round-trips data within the size cap`() {
        val original = ByteArray(4 * 1024 * 1024) { (it and 0xFF).toByte() }
        val restored = original.gzip().gunzip(maxSize = 8L * 1024 * 1024)

        assertContentEquals(original, restored)
    }

    @Test
    fun `round-trips empty data`() {
        val restored = ByteArray(0).gzip().gunzip(maxSize = 0L)

        assertContentEquals(ByteArray(0), restored)
    }

    @Test
    fun `skips the optional header fields`() {
        val member = "Hello, world!".encodeToByteArray().gzip()
        val fields = byteArrayOf(3, 0, 1, 2, 3) + // FEXTRA: length, then data
            "hello.txt".encodeToByteArray() + 0 + // FNAME
            "A comment".encodeToByteArray() + 0 // FCOMMENT
        // Replace the flags of the bare 10-byte header and insert the fields after it.
        val header = member.copyOf(10)
        header[3] = 0x1C
        val stream = header + fields + member.copyOfRange(10, member.size)

        assertContentEquals("Hello, world!".encodeToByteArray(), stream.gunzip(maxSize = 1024L))
    }

    @Test
    fun `rejects a stream with several members`() {
        val stream = "Hello, ".encodeToByteArray().gzip() + "world!".encodeToByteArray().gzip()

        assertInvalid(stream)
    }

    @Test
    fun `rejects many empty members without running out of stack`() {
        // A reader that recurses once per member overflows
        // the stack long before reaching this many.
        val member = ByteArray(0).gzip()
        val stream = ByteArray(member.size * 100_000)
        repeat(100_000) { member.copyInto(stream, it * member.size) }

        assertInvalid(stream)
    }

    @Test
    fun `rejects bytes after the member`() {
        val stream = "Hello, world!".encodeToByteArray().gzip() + ByteArray(512)

        assertInvalid(stream)
    }

    @Test
    fun `rejects a truncated header`() {
        // The FHCRC and FEXTRA flags, an empty extra field,
        // and then the input ends before the header CRC.
        val header = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x06, 0, 0, 0, 0, 0, 0, 0, 0)

        assertInvalid(header)
    }

    @Test
    fun `rejects empty data`() {
        assertInvalid(ByteArray(0))
    }

    @Test
    fun `rejects a decompression bomb that exceeds the cap`() {
        // 4 MiB of zeros compresses to a few KiB but inflates back to 4 MiB;
        // a 1 MiB cap must reject it rather than allocating the full output.
        val bomb = ByteArray(4 * 1024 * 1024).gzip()
        // The compressed input is well under the 1 MiB cap it will breach.
        assertTrue(bomb.size < 1 * 1024 * 1024)

        val e = assertFailsWith<GzipException> {
            bomb.gunzip(maxSize = 1L * 1024 * 1024)
        }
        assertEquals(GzipError.TooLarge, e.reason)
    }

    @Test
    fun `rejects a damaged stream`() {
        val compressed = "Hello, world!".encodeToByteArray().gzip()
        // Corrupt the CRC32 in the trailer.
        val index = compressed.size - 8
        compressed[index] = (compressed[index].toInt() xor 0xFF).toByte()

        assertInvalid(compressed)
    }

    @Test
    fun `rejects a truncated stream`() {
        val compressed = "Hello, world!".encodeToByteArray().gzip()

        assertInvalid(compressed.copyOf(compressed.size / 2))
    }

    @Test
    fun `rejects data that is not gzip`() {
        assertInvalid("Hello, world!".encodeToByteArray())
    }

    @Test
    fun `checks for cancellation while inflating`() {
        val compressed = ByteArray(1024).gzip()

        assertFailsWith<ExpectedException> {
            compressed.gunzip(
                maxSize = 1024L,
                checkCancellation = { throw ExpectedException() },
            )
        }
    }

    private fun assertInvalid(bytes: ByteArray) {
        val e = assertFailsWith<GzipException> {
            bytes.gunzip(maxSize = 1024L)
        }
        assertEquals(GzipError.Invalid, e.reason)
    }
}
