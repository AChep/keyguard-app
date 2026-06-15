package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.internal.protocols.MessagePackHubProtocol
import com.artemchep.keyguard.util.signalr.internal.util.handshake as performHandshake
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class HubHandshakerTest {
    private val protocol = MessagePackHubProtocol()
    private val json = Json

    @Test
    fun `handshake sends reference request and accepts empty response`() = runTest {
        val transport = FakeHandshakeTransport(
            response = "{}$RECORD_SEPARATOR".encodeToByteArray(),
        )

        val remainingPayload = handshake(transport)

        assertNull(remainingPayload)
        assertEquals(
            listOf("""{"protocol":"messagepack","version":1}$RECORD_SEPARATOR"""),
            transport.sentTextMessages,
        )
    }

    @Test
    fun `handshake preserves coalesced hub payload after response`() = runTest {
        val coalescedPayload = """{"type":6}$RECORD_SEPARATOR""".encodeToByteArray()
        val transport = FakeHandshakeTransport(
            response = "{}$RECORD_SEPARATOR".encodeToByteArray() + coalescedPayload,
        )

        val remainingPayload = handshake(transport)

        assertContentEquals(coalescedPayload, remainingPayload)
    }

    @Test
    fun `handshake fails response with error`() = runTest {
        val transport = FakeHandshakeTransport(
            response = """{"error":"Requested protocol 'messagepack' is not available."}$RECORD_SEPARATOR"""
                .encodeToByteArray(),
        )

        val exception = assertFailsWith<RuntimeException> {
            handshake(transport)
        }

        assertEquals(
            "Error in handshake Requested protocol 'messagepack' is not available.",
            exception.message,
        )
    }

    @Test
    fun `handshake rejects invalid json response`() = runTest {
        val transport = FakeHandshakeTransport(
            response = "{$RECORD_SEPARATOR".encodeToByteArray(),
        )

        val exception = assertFailsWith<RuntimeException> {
            handshake(transport)
        }

        assertEquals(
            "An invalid handshake response was received from the server.",
            exception.message,
        )
    }

    @Test
    fun `handshake rejects hub message where response was expected`() = runTest {
        val transport = FakeHandshakeTransport(
            response = """{"type":6}$RECORD_SEPARATOR""".encodeToByteArray(),
        )

        val exception = assertFailsWith<RuntimeException> {
            handshake(transport)
        }

        assertEquals(
            "Expected a handshake response from the server.",
            exception.message,
        )
    }

    private suspend fun handshake(
        transport: Transport,
    ) = performHandshake(
        protocol = protocol,
        handshakeResponseTimeout = 5.seconds,
        transport = transport,
        json = json,
    )
}

private class FakeHandshakeTransport(
    private val response: ByteArray,
) : Transport {
    val sentTextMessages = mutableListOf<String>()

    override suspend fun send(
        message: ByteArray,
    ) = Unit

    override suspend fun sendText(
        message: String,
    ) {
        sentTextMessages += message
    }

    override fun receive(): Flow<ByteArray> = flowOf(response)

    override suspend fun stop() = Unit
}
