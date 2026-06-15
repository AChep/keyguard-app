package com.artemchep.keyguard.util.messagepack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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

    @Serializable
    private data class SampleMessage(
        val name: String,
        val count: Int,
        val active: Boolean,
    )
}
