package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Twelve bytes, more than a packet of the test protocol. */
private val LONG = "x".repeat(12)

class AgentPacketSessionTest {
    @Test
    fun oversizeResponseIsReplacedAndTheSessionStaysOpen() = runTest {
        val session = AgentPacketSession(Protocol(oversize = { size -> "large:$size" }))

        val reply = session.process("long".encodeToByteArray())
        assertEquals("large:12", reply.packet.decodeToString())
        assertFalse(reply.close)
        assertEquals("ok", session.process("ok".encodeToByteArray()).packet.decodeToString())
    }

    @Test
    fun oversizeResponsesThatCannotBeReplacedCloseTheSession() = runTest {
        val cases = listOf(
            // The protocol has no oversize reply.
            Protocol(oversize = { null }) to "long",
            // An authentication reply decides the session, so it is never replaced.
            Protocol(oversize = { "small" }) to "auth-long",
            // The replacement does not fit either.
            Protocol(oversize = { "replacement" }) to "long",
        )
        for ((protocol, request) in cases) {
            val session = AgentPacketSession(protocol)
            assertFailsWith<IllegalArgumentException> { session.process(request.encodeToByteArray()) }
            assertFailsWith<IllegalStateException> { session.process("ok".encodeToByteArray()) }
        }
    }

    @Test
    fun aReplyCarriesTheStreamOfItsResponse() = runTest {
        val stream = Stream()
        val session = AgentPacketSession(Protocol(oversize = { null }, opens = mapOf("open" to stream)))

        val reply = session.process("open".encodeToByteArray())
        assertSame(stream, reply.upgrade)
        assertEquals(0, stream.closed)
    }

    @Test
    fun aResponseThatFailsClosesItsStream() = runTest {
        val stream = Stream()
        val session = AgentPacketSession(Protocol(oversize = { null }, opens = mapOf(LONG to stream)))

        assertFailsWith<IllegalArgumentException> { session.process("open-long".encodeToByteArray()) }
        assertEquals(1, stream.closed)
    }

    @Test
    fun aResponseThatOpensAStreamIsNeverReplaced() = runTest {
        val stream = Stream()
        val session = AgentPacketSession(Protocol(oversize = { "large" }, opens = mapOf(LONG to stream)))

        assertFailsWith<IllegalArgumentException> { session.process("open-long".encodeToByteArray()) }
        assertEquals(1, stream.closed)
        assertFailsWith<IllegalStateException> { session.process("ok".encodeToByteArray()) }
    }

    @Test
    fun aStreamThatFailsToCloseIsClosedOnceAndTheSessionEnds() = runTest {
        val stream = Stream(closeFails = true)
        val session = AgentPacketSession(Protocol(oversize = { null }, opens = mapOf(LONG to stream)))

        // The failure that ended the request, with the one of the close.
        val error = assertFailsWith<IllegalArgumentException> { session.process("open-long".encodeToByteArray()) }
        assertEquals(listOf("close failed"), error.suppressedExceptions.map { it.message })
        assertEquals(1, stream.closed)
        assertFailsWith<IllegalStateException> { session.process("ok".encodeToByteArray()) }
    }

    @Test
    fun aStreamIsClosedWhenItsRequestFailsToWipe() = runTest {
        val stream = Stream()
        val protocol = Protocol(oversize = { null }, opens = mapOf("open" to stream), wipeFails = true)
        val session = AgentPacketSession(protocol)

        assertFailsWith<IllegalStateException> { session.process("open".encodeToByteArray()) }
        assertEquals(1, stream.closed)
    }

    @Test
    fun aServedSessionClosesTheStreamsItCannotServe() = runTest {
        val stream = Stream()
        val session = AgentPacketSession(Protocol(oversize = { null }, opens = mapOf("open" to stream)))
        val packets = ArrayDeque(listOf("open".encodeToByteArray()))
        val written = mutableListOf<String>()

        session.serve(
            readPacket = { packets.removeFirstOrNull() },
            writePacket = { packet ->
                written += packet.decodeToString()
                true
            },
        )
        assertEquals(listOf("open"), written)
        assertEquals(1, stream.closed)
    }

    /**
     * Echoes each request, except that a "long" suffix answers with [LONG],
     * and an "auth" prefix authenticates the session. A response of [opens]
     * opens its stream. Wiping a request throws if it [wipeFails].
     */
    private class Protocol(
        private val oversize: (Int) -> String?,
        private val opens: Map<String, AgentDuplex> = emptyMap(),
        private val wipeFails: Boolean = false,
    ) : AgentPacketProtocol<String, String> {
        override val maxPacketSize = 10

        override fun decode(packet: ByteArray) = packet.decodeToString()

        override fun encode(response: String) = response.encodeToByteArray()

        override suspend fun handle(request: String, authenticated: Boolean) =
            if (request.endsWith("long")) LONG else request

        override fun authenticatedAfter(request: String, response: String, authenticated: Boolean) =
            if (request.startsWith("auth")) true else null

        override fun oversizeResponse(request: String, size: Int) = oversize(size)

        override fun upgrade(response: String) = opens[response]

        override fun wipeRequest(request: String) = check(!wipeFails) { "wipe failed" }
    }

    /** A stream that counts how often it is closed; its [close] throws if it [closeFails]. */
    private class Stream(private val closeFails: Boolean = false) : AgentDuplex {
        var closed = 0

        override val maxPacketSize = 10

        override suspend fun serve(channel: AgentDuplexChannel) = error("Not served")

        override fun close() {
            closed += 1
            check(!closeFails) { "close failed" }
        }
    }
}
