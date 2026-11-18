package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        // Ending the wait for input closes a real connection: only after the final write.
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
    fun processingOutlivesTheTimeout() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        // An approval prompt owns its deadline, so a request may wait
        // for the user much longer than a read or a write may take.
        val agent = Agent(
            respond = {
                delay(TIMEOUT_MS * 10)
                "response"
            },
        )
        serveCliAgentConnection(connection.stream, MAX, agent::process, TIMEOUT_MS)
        assertEquals(TIMEOUT_MS * 10, currentTime)
        assertFalse(agent.cancelled)
        assertEquals(listOf("ok", "response"), connection.writtenPackets())
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

    @Test
    fun aReplyMayTurnTheConnectionIntoAStream() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream()
        val agent = Agent(respond = { "opened" }, opens = mapOf("open" to stream))
        val serving = async {
            serveCliAgentConnection(connection.stream, MAX, agent::process, TIMEOUT_MS)
        }
        runCurrent()
        assertEquals(listOf("ok", "opened"), connection.writtenPackets())
        assertTrue(agent.replies.all(ByteArray::isZeroed))

        // Reads of the stream wait for the peer without a time limit.
        delay(TIMEOUT_MS * 10)
        assertFalse(serving.isCompleted)
        // The watch stopped with the reply, and left the connection open.
        assertFalse("observe cancelled" in connection.events)
        connection.sendPacket("ping")
        runCurrent()
        assertEquals(listOf("ok", "opened", "echo ping"), connection.writtenPackets())
        // The stream reads once the wait for input has returned.
        val afterWatch = connection.events.drop(connection.events.indexOf("observe"))
        assertEquals("observe returned", afterWatch.first { it == "read" || it == "observe returned" })

        // Either side ends the stream.
        connection.close()
        serving.await()
        assertEquals(listOf("served", "closed"), stream.events)
        assertTrue(agent.packets.all(ByteArray::isZeroed))
    }

    @Test
    fun aStreamThatTheAppEndsClosesTheConnection() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream(packets = 1)
        val agent = Agent(respond = { "opened" }, opens = mapOf("open" to stream))
        val serving = async {
            serveCliAgentConnection(connection.stream, MAX, agent::process)
        }
        runCurrent()
        connection.sendPacket("ping")
        serving.await()
        assertEquals(listOf("ok", "opened", "echo ping"), connection.writtenPackets())
        assertEquals(listOf("served", "closed"), stream.events)
    }

    @Test
    fun aWriteOfTheStreamEndsAfterTheTimeout() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream()
        val agent = Agent(respond = { "opened" }, opens = mapOf("open" to stream))
        val serving = async {
            serveCliAgentConnection(connection.stream, MAX, agent::process, TIMEOUT_MS)
        }
        runCurrent()
        connection.writeGate = CompletableDeferred()
        connection.sendPacket("ping")
        serving.await()
        assertContains(connection.events, "write cancelled")
        assertEquals(listOf("served", "gave up", "closed"), stream.events)
    }

    @Test
    fun concurrentWritesOfTheStreamKeepPacketsWhole() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val gate = CompletableDeferred<Unit>()
        val stream = object : AgentDuplex {
            override val maxPacketSize = MAX

            override suspend fun serve(channel: AgentDuplexChannel) {
                connection.writeGate = gate
                coroutineScope {
                    launch { channel.writePacket("first".encodeToByteArray()) }
                    launch { channel.writePacket("second".encodeToByteArray()) }
                }
            }

            override fun close() = Unit
        }
        val serving = async {
            serveCliAgentConnection(connection.stream, MAX, Agent(opens = mapOf("open" to stream))::process)
        }
        runCurrent()
        // The second packet waits for the first one, header and body.
        assertEquals(listOf("write"), connection.events.takeLastWhile { it != "wrote" })
        // The wait for input outlives the watch, and the connection stays open.
        assertFalse("observe cancelled" in connection.events)

        gate.complete(Unit)
        serving.await()
        assertEquals(listOf("ok", "response", "first", "second"), connection.writtenPackets())
    }

    @Test
    fun aStreamWhoseReplyIsDroppedIsClosedUnserved() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream()
        val agent = Agent(
            respond = {
                // The client leaves, and its watch cancels the processing as it answers.
                connection.close()
                currentCoroutineContext().cancel()
                "opened"
            },
            opens = mapOf("open" to stream),
        )
        serveCliAgentConnection(connection.stream, MAX, agent::process)
        assertEquals(listOf("ok"), connection.writtenPackets())
        assertEquals(listOf("closed"), stream.events)
        assertTrue(agent.replies.all(ByteArray::isZeroed))
    }

    @Test
    fun aStreamWhoseReplyFailsIsClosedUnserved() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream()
        val failure = TransportFailure(3)
        val agent = Agent(
            respond = {
                connection.writeFailure = failure
                "opened"
            },
            opens = mapOf("open" to stream),
        )
        val error = assertFailsWith<TransportFailure> {
            serveCliAgentConnection(connection.stream, MAX, agent::process)
        }
        assertSame(failure, error)
        assertEquals(listOf("closed"), stream.events)
        assertTrue(agent.replies.all(ByteArray::isZeroed))
    }

    @Test
    fun aStreamWhoseReplyTimesOutIsClosedUnserved() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream()
        val agent = Agent(
            respond = {
                connection.writeGate = CompletableDeferred()
                "opened"
            },
            opens = mapOf("open" to stream),
        )
        serveCliAgentConnection(connection.stream, MAX, agent::process, TIMEOUT_MS)
        assertEquals(TIMEOUT_MS, currentTime)
        assertEquals(listOf("closed"), stream.events)
        assertTrue(agent.replies.all(ByteArray::isZeroed))
    }

    @Test
    fun aStreamOfAClosingReplyIsClosedUnserved() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream()
        val agent = Agent(respond = { "bye" }, opens = mapOf("open" to stream), closes = setOf("open"))
        serveCliAgentConnection(connection.stream, MAX, agent::process)
        assertEquals(listOf("ok", "bye"), connection.writtenPackets())
        assertEquals(listOf("closed"), stream.events)
    }

    @Test
    fun anAuthenticationNeverOpensAStream() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("request")
        }
        val stream = EchoStream()
        val agent = Agent(opens = mapOf("auth" to stream))
        serveCliAgentConnection(connection.stream, MAX, agent::process)
        assertEquals(listOf("ok", "response"), connection.writtenPackets())
        assertEquals(listOf("closed"), stream.events)
    }

    @Test
    fun aStreamOfACancelledConnectionIsClosed() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream()
        val agent = Agent(respond = { "opened" }, opens = mapOf("open" to stream))
        val serving = launch {
            serveCliAgentConnection(connection.stream, MAX, agent::process)
        }
        runCurrent()
        assertEquals(listOf("served"), stream.events)

        serving.cancelAndJoin()
        assertEquals(listOf("served", "closed"), stream.events)
    }

    @Test
    fun anOversizedPacketOfTheStreamFails() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("auth")
            sendPacket("open")
        }
        val stream = EchoStream(maxPacketSize = 4)
        val agent = Agent(respond = { "opened" }, opens = mapOf("open" to stream))
        val serving = async {
            runCatching {
                serveCliAgentConnection(connection.stream, MAX, agent::process)
            }
        }
        runCurrent()
        connection.sendPacket("too long")
        assertIs<InvalidAgentFrameException>(serving.await().exceptionOrNull())
        assertEquals(listOf("served", "closed"), stream.events)
    }

    /** A stream that answers each packet with `echo <packet>`, for [packets] packets. */
    private class EchoStream(
        override val maxPacketSize: Int = MAX,
        private val packets: Int = Int.MAX_VALUE,
    ) : AgentDuplex {
        val events = mutableListOf<String>()

        override suspend fun serve(channel: AgentDuplexChannel) {
            events += "served"
            repeat(packets) {
                val packet = channel.readPacket()
                    ?: return
                if (!channel.writePacket("echo ${packet.decodeToString()}".encodeToByteArray())) {
                    events += "gave up"
                    return
                }
            }
        }

        override fun close() {
            events += "closed"
        }
    }

    /**
     * The session behind a connection: "auth" authenticates, "deny" is rejected
     * and closes, and anything else is answered by [respond]. The reply to a
     * request of [opens] carries its stream, and the reply to a request of
     * [closes] closes the connection.
     */
    private class Agent(
        private val onAuthenticate: () -> Unit = {},
        private val respond: suspend (String) -> String = { "response" },
        private val opens: Map<String, AgentDuplex> = emptyMap(),
        private val closes: Set<String> = emptySet(),
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
                "auth" -> AgentPacketReply("ok".encodeToByteArray(), close = false, upgrade = opens[request])
                    .also { onAuthenticate() }

                "deny" -> AgentPacketReply("denied".encodeToByteArray(), close = true)
                else -> AgentPacketReply(
                    packet = answer(request).encodeToByteArray(),
                    close = request in closes,
                    upgrade = opens[request],
                )
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
