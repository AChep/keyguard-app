package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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

    /**
     * Echoes each request, except that "long" answers with twelve bytes, and
     * an "auth" prefix authenticates the session.
     */
    private class Protocol(
        private val oversize: (Int) -> String?,
    ) : AgentPacketProtocol<String, String> {
        override val maxPacketSize = 10

        override fun decode(packet: ByteArray) = packet.decodeToString()

        override fun encode(response: String) = response.encodeToByteArray()

        override suspend fun handle(request: String, authenticated: Boolean) =
            if (request.endsWith("long")) "x".repeat(12) else request

        override fun authenticatedAfter(request: String, response: String, authenticated: Boolean) =
            if (request.startsWith("auth")) true else null

        override fun oversizeResponse(request: String, size: Int) = oversize(size)
    }
}
