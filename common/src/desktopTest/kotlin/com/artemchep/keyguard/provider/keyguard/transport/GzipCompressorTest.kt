package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.Decompressed
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GzipCompressorTest {
    @Test
    fun `round trip`() {
        listOf(
            ByteArray(0),
            "{\"entries\":[]}".encodeToByteArray(),
            ByteArray(200_000) { (it % 7).toByte() },
            Random(3).nextBytes(100_000),
        ).forEach { input ->
            val compressed = GzipCompressor.compress(input)
            val decompressed = assertIs<Decompressed.Bytes>(GzipCompressor.decompress(compressed, maxSize = input.size))
            assertContentEquals(input, decompressed.bytes)
            // Plain gzip, readable by any implementation.
            assertContentEquals(input, GZIPInputStream(ByteArrayInputStream(compressed)).readBytes())
        }
    }

    @Test
    fun `a bomb or garbage is refused`() {
        val bomb = GzipCompressor.compress(ByteArray(4 * 1024 * 1024))
        assertTrue(bomb.size < 64 * 1024)
        assertEquals(Decompressed.TooLarge, GzipCompressor.decompress(bomb, maxSize = 1024 * 1024))
        assertEquals(Decompressed.Invalid, GzipCompressor.decompress("not gzip".encodeToByteArray(), maxSize = 1024))
        val truncated = GzipCompressor.compress(ByteArray(1000) { 1 }).let { it.copyOf(it.size - 4) }
        assertEquals(Decompressed.Invalid, GzipCompressor.decompress(truncated, maxSize = 1024))
    }
}
