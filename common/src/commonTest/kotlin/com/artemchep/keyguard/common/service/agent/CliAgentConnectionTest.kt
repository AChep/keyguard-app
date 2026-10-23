package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val MAX = 64

private const val TIMEOUT_MS = 1_000L

@OptIn(ExperimentalCoroutinesApi::class)
class CliAgentConnectionTest {
    @Test
    fun aConnectionAuthenticatesThenMakesExactlyOneRequest() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        val agent = Agent()
        serveCliAgentConnection(connection.stream, MAX, agent::process)

        assertEquals(listOf("auth", "request"), agent.requests)
        assertEquals(listOf("ok", "response"), connection.writtenPackets())
        // Nothing is read after the request, and every packet and reply is zeroed.
        assertEquals(listOf("observe", "observe cancelled"), connection.events.filter { it.startsWith("observe") })
        assertTrue(agent.packets.all(ByteArray::isZeroed))
        assertTrue(agent.replies.all(ByteArray::isZeroed))
    }

    @Test
    fun aRejectedAuthenticationIsAnsweredThenCloses() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("deny")
            sendPacket("request")
        }
        val agent = Agent()
        serveCliAgentConnection(connection.stream, MAX, agent::process)

        assertEquals(listOf("deny"), agent.requests)
        assertEquals(listOf("denied"), connection.writtenPackets())
        assertFalse("observe" in connection.events)
        assertTrue(agent.replies.all(ByteArray::isZeroed))
    }

    @Test
    fun aDisconnectWhileProcessingCancelsTheRequest() = runTest {
        cancelsWhileProcessing { it.close() }
    }

    @Test
    fun furtherInputWhileProcessingCancelsTheRequest() = runTest {
        cancelsWhileProcessing { it.send(byteArrayOf(0)) }
    }

    private suspend fun TestScope.cancelsWhileProcessing(client: (FakeAgentConnection) -> Unit) {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        val agent = Agent(respond = { awaitCancellation() })
        val serving = async { serveCliAgentConnection(connection.stream, MAX, agent::process) }
        runCurrent()
        assertFalse(agent.cancelled)

        client(connection)
        // Ends quietly: the client is gone or broke the protocol.
        serving.await()
        assertTrue(agent.cancelled)
        assertEquals(listOf("ok"), connection.writtenPackets())
        assertContains(connection.events, "observe returned")
        assertTrue(agent.packets.all(ByteArray::isZeroed))
    }

    @Test
    fun theResponseIsWrittenWhileTheWatchIsPending() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        val gate = CompletableDeferred<Unit>()
        val agent = Agent(
            respond = {
                // Holds back the response, but not the authentication reply.
                connection.writeGate = gate
                "response"
            },
        )
        val serving = async { serveCliAgentConnection(connection.stream, MAX, agent::process) }
        runCurrent()
        assertEquals("write", connection.events.last())
        assertContains(connection.events, "observe")
        assertFalse("observe cancelled" in connection.events)

        gate.complete(Unit)
        serving.await()
        // Cancelling the watch closes a real connection: only after the final write.
        val events = connection.events
        assertEquals(listOf("wrote", "observe cancelled"), events.takeLast(2))
        assertEquals(listOf("ok", "response"), connection.writtenPackets())
    }

    @Test
    fun transportFailuresAreNotWrapped() = runTest {
        val readFailure = TransportFailure(1)
        val reading = FakeAgentConnection().apply { sendPacket("auth") }
        val agentThatBreaksTheRead = Agent(onAuthenticate = { reading.readFailure = readFailure })
        val readError = assertFailsWith<TransportFailure> {
            serveCliAgentConnection(reading.stream, MAX, agentThatBreaksTheRead::process)
        }
        assertSame(readFailure, readError)

        val writeFailure = TransportFailure(2)
        val writing = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        val agentThatBreaksTheWrite = Agent(
            respond = {
                writing.writeFailure = writeFailure
                "response"
            },
        )
        val writeError = assertFailsWith<TransportFailure> {
            serveCliAgentConnection(writing.stream, MAX, agentThatBreaksTheWrite::process)
        }
        assertSame(writeFailure, writeError)
        assertTrue(agentThatBreaksTheWrite.replies.all(ByteArray::isZeroed))
    }

    @Test
    fun anInvalidRequestFrameFails() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            send(byteArrayOf(0, 0, 0, 0))
        }
        assertFailsWith<InvalidAgentFrameException> {
            serveCliAgentConnection(connection.stream, MAX, Agent()::process)
        }
    }

    @Test
    fun aTruncatedRequestEndsQuietly() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            send(byteArrayOf(0, 0, 0, 8, 1))
            close()
        }
        val agent = Agent()
        serveCliAgentConnection(connection.stream, MAX, agent::process)
        assertEquals(listOf("auth"), agent.requests)
        assertEquals(listOf("ok"), connection.writtenPackets())
    }

    @Test
    fun anIdleConnectionEndsAfterTheTimeout() = runTest {
        val connection = FakeAgentConnection()
        serveCliAgentConnection(connection.stream, MAX, Agent()::process, TIMEOUT_MS)
        assertEquals(TIMEOUT_MS, currentTime)
        assertContains(connection.events, "read cancelled")
        assertEquals(emptyList(), connection.writtenPackets())
    }

    @Test
    fun processingEndsAfterTheTimeout() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        val agent = Agent(respond = { awaitCancellation() })
        serveCliAgentConnection(connection.stream, MAX, agent::process, TIMEOUT_MS)
        assertEquals(TIMEOUT_MS, currentTime)
        assertTrue(agent.cancelled)
        assertEquals(listOf("ok"), connection.writtenPackets())
        assertEquals("observe cancelled", connection.events.last())
    }

    @Test
    fun aWriteEndsAfterTheTimeout() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        val agent = Agent(
            respond = {
                connection.writeGate = CompletableDeferred()
                "response"
            },
        )
        serveCliAgentConnection(connection.stream, MAX, agent::process, TIMEOUT_MS)
        assertEquals(TIMEOUT_MS, currentTime)
        assertEquals(listOf("write cancelled", "observe cancelled"), connection.events.takeLast(2))
        assertTrue(agent.replies.all(ByteArray::isZeroed))
    }

    /**
     * The session behind a connection: "auth" authenticates, "deny" is rejected
     * and closes, and anything else is answered by [respond].
     */
    private class Agent(
        private val onAuthenticate: () -> Unit = {},
        private val respond: suspend (String) -> String = { "response" },
    ) {
        val requests = mutableListOf<String>()

        /** The packets and replies as they were passed, to check that they are zeroed. */
        val packets = mutableListOf<ByteArray>()
        val replies = mutableListOf<ByteArray>()

        var cancelled = false

        suspend fun process(packet: ByteArray): AgentPacketReply {
            packets += packet
            val request = packet.decodeToString()
            requests += request
            val reply = when (request) {
                "auth" -> AgentPacketReply("ok".encodeToByteArray(), close = false)
                    .also { onAuthenticate() }

                "deny" -> AgentPacketReply("denied".encodeToByteArray(), close = true)
                else -> AgentPacketReply(answer(request).encodeToByteArray(), close = false)
            }
            replies += reply.packet
            return reply
        }

        private suspend fun answer(request: String): String = try {
            respond(request)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
    }

    /** A transport failure that coroutines cannot copy, so it stays the same instance. */
    private class TransportFailure(val code: Int) : Exception()
}

private fun ByteArray.isZeroed() = all { it == 0.toByte() }
