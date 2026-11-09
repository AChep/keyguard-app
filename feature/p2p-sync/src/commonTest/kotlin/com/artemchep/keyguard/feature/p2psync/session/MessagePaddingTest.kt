package com.artemchep.keyguard.feature.p2psync.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagePaddingTest {
    @Test
    fun `buckets match NIP-44`() {
        // `calc_padded_len` vectors of NIP-44; the length prefix counts toward the size.
        listOf(
            16 to 32,
            32 to 32,
            33 to 64,
            65 to 96,
            100 to 128,
            200 to 224,
            320 to 320,
            400 to 448,
            515 to 640,
            700 to 768,
            800 to 896,
            900 to 1024,
            65_536 to 65_536,
        ).forEach { (size, padded) ->
            assertEquals(padded, MessagePadding.paddedSize(size - MessagePadding.HEADER_SIZE), "for $size")
        }
        assertEquals(32, MessagePadding.paddedSize(0))
    }

    @Test
    fun `past 256 bytes a message grows by less than a quarter`() {
        (253..300_000 step 13).forEach { size ->
            val unpadded = size + MessagePadding.HEADER_SIZE
            val padded = MessagePadding.paddedSize(size)
            assertTrue(padded >= unpadded && (padded - unpadded) * 4 < unpadded, "for $size: $padded")
        }
    }

    @Test
    fun `messages round trip`() {
        val random = Random(7)
        listOf(0, 1, 28, 29, 1_000, 65_536, 300_000).forEach { size ->
            val message = random.nextBytes(size)
            val padded = MessagePadding.pad(message)
            assertEquals(MessagePadding.paddedSize(size), padded.size)
            assertTrue(padded.drop(MessagePadding.HEADER_SIZE + size).all { it == 0.toByte() })
            assertContentEquals(message, MessagePadding.unpad(padded))
        }
    }

    @Test
    fun `malformed padding is refused`() {
        val padded = MessagePadding.pad(ByteArray(10))
        // Shorter than the prefix.
        assertNull(MessagePadding.unpad(ByteArray(3)))
        // A length past the end.
        assertNull(MessagePadding.unpad(padded.copyOf().also { it[3] = 40 }))
        assertNull(MessagePadding.unpad(padded.copyOf().also { it[0] = -1 }))
        // Not padded to the bucket of its length.
        assertNull(MessagePadding.unpad(padded + ByteArray(32)))
        assertNull(MessagePadding.unpad(padded.copyOf(31)))
    }
}
