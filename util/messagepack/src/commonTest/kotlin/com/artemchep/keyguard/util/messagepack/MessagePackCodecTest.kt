package com.artemchep.keyguard.util.messagepack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagePackCodecTest {
    @Test
    fun `serializable values round trip`() {
        val value = SampleMessage(
            name = "cipher",
            count = 2,
            active = true,
        )

        val bytes = MessagePackCodec.encodeToByteArray(
            serializer = SampleMessage.serializer(),
            value = value,
        )
        val decoded = MessagePackCodec.decodeFromByteArray(
            deserializer = SampleMessage.serializer(),
            bytes = bytes,
        )

        assertEquals(value, decoded)
    }

    @Test
    fun `dynamic values round trip to json`() {
        val value = mapOf(
            "Type" to 5,
            "ContextId" to "device-1",
            "Payload" to listOf("cipher-1", true),
        )

        val bytes = MessagePackCodec.encodeDynamic(value)
        val decoded = MessagePackCodec.decodeDynamic(bytes)

        assertEquals(
            JsonObject(
                mapOf(
                    "Type" to JsonPrimitive(5),
                    "ContextId" to JsonPrimitive("device-1"),
                    "Payload" to listOf("cipher-1", true).toJsonElement(),
                ),
            ),
            decoded.toJsonElement(),
        )
    }

    @Test
    fun `frames round trip multiple payloads`() {
        val first = byteArrayOf(1, 2, 3)
        val second = ByteArray(200) {
            it.toByte()
        }
        val payload = MessagePackFrameCodec.frame(first) + MessagePackFrameCodec.frame(second)

        val frames = MessagePackFrameCodec.readFrames(payload)

        assertEquals(2, frames.size)
        assertContentEquals(first, frames[0])
        assertContentEquals(second, frames[1])
    }

    @Test
    fun `encoded values are well formed`() {
        val value = mapOf(
            "name" to "cipher",
            "bytes" to List(300) { it % 256 },
            "nested" to listOf(listOf(mapOf("t" to "int", "c" to -4L)), null, true, 1.5),
            "big" to Long.MAX_VALUE,
        )
        val bytes = MessagePackCodec.encodeDynamic(value)

        assertTrue(MessagePackCodec.isWellFormed(bytes, maxDepth = 4))
        // The fourth level of nesting is one too many.
        assertFalse(MessagePackCodec.isWellFormed(bytes, maxDepth = 3))
    }

    @Test
    fun `scanned input is refused when a length runs past the end`() {
        val cases = listOf(
            // array32 of 2^31 values in six bytes
            byteArrayOf(0xdd.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01),
            // map16 of 2 pairs with one value
            byteArrayOf(0xde.toByte(), 0x00, 0x02, 0x01),
            // str8 of 5 bytes with 2
            byteArrayOf(0xd9.toByte(), 0x05, 0x61, 0x62),
            // bin32 with a truncated length
            byteArrayOf(0xc6.toByte(), 0x00, 0x00),
            // uint32 with 3 bytes
            byteArrayOf(0xce.toByte(), 0x00, 0x00, 0x00),
            // the never-used type
            byteArrayOf(0xc1.toByte()),
            // trailing bytes after the value
            byteArrayOf(0x01, 0x02),
            // nothing at all
            byteArrayOf(),
        )
        cases.forEach { bytes ->
            assertFalse(MessagePackCodec.isWellFormed(bytes, maxDepth = 8), bytes.contentToString())
        }
    }

    @Test
    fun `scanned input is refused when it nests too deep`() {
        // 100,000 nested fixarrays of one value each.
        val bytes = ByteArray(100_000) { 0x91.toByte() } + byteArrayOf(0x00)

        assertFalse(MessagePackCodec.isWellFormed(bytes, maxDepth = 16))
    }

    @Serializable
    private data class SampleMessage(
        val name: String,
        val count: Int,
        val active: Boolean,
    )
}
