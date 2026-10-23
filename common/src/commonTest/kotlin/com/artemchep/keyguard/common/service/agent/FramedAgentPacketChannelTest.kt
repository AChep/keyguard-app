package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val MAX = 16

/** A header of [size], as a frame of the channel starts. */
private fun header(size: Int) = AgentIpcFraming.encodeHeader(size, Int.MAX_VALUE)

@OptIn(ExperimentalCoroutinesApi::class)
class FramedAgentPacketChannelTest {
    @Test
    fun emptyFramesAreInvalid() = runTest {
        val connection = FakeAgentConnection().apply { send(byteArrayOf(0, 0, 0, 0)) }
        val error = assertFailsWith<InvalidAgentFrameException> {
            FramedAgentPacketChannel(connection.stream, MAX).readPacket()
        }
        assertEquals("invalid_frame", error.message)
    }

    @Test
    fun oversizeFramesAreInvalidBeforeTheirBodyIsAllocated() = runTest {
        val headers = listOf(
            header(MAX + 1),
            // Neither body could be allocated, so the size must be checked first.
            byteArrayOf(0x7f, -1, -1, -1),
            byteArrayOf(-1, -1, -1, -1),
        )
        for (header in headers) {
            val connection = FakeAgentConnection().apply { send(header) }
            val error = assertFailsWith<InvalidAgentFrameException> {
                FramedAgentPacketChannel(connection.stream, MAX).readPacket()
            }
            // The size is never part of the text.
            assertEquals("invalid_frame", error.message)
            assertEquals(listOf("read"), connection.events)
        }
    }

    @Test
    fun aStreamThatEndsWithinTheHeaderEndsQuietly() = runTest {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0, 0))) {
            val connection = FakeAgentConnection(readChunk = 1).apply {
                send(bytes)
                close()
            }
            assertNull(FramedAgentPacketChannel(connection.stream, MAX).readPacket())
        }
    }

    @Test
    fun aTruncatedBodyEndsQuietlyAndIsZeroed() = runTest {
        val connection = FakeAgentConnection().apply {
            send(byteArrayOf(0, 0, 0, 4, 1, 2))
            close()
        }
        assertNull(FramedAgentPacketChannel(connection.stream, MAX).readPacket())
        val body = connection.readBuffers.last()
        assertContentEquals(ByteArray(4), body)
    }

    @Test
    fun cancellingTheReadOfABodyZeroesIt() = runTest {
        val connection = FakeAgentConnection().apply { send(byteArrayOf(0, 0, 0, 4, 1, 2)) }
        val read = launch { FramedAgentPacketChannel(connection.stream, MAX).readPacket() }
        runCurrent()
        val body = connection.readBuffers.last()
        assertContentEquals(byteArrayOf(1, 2, 0, 0), body)

        read.cancelAndJoin()
        assertContains(connection.events, "read cancelled")
        assertContentEquals(ByteArray(4), body)
    }

    @Test
    fun packetsRoundTrip() = runTest {
        val packets = listOf(byteArrayOf(1), ByteArray(MAX) { it.toByte() }, byteArrayOf(7, 8, 9))
        val writer = FakeAgentConnection()
        val output = FramedAgentPacketChannel(writer.stream, MAX)
        packets.forEach { output.writePacket(it) }
        // A four-byte big-endian length, then the payload.
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 1), writer.written.copyOfRange(0, 5))

        // Each read returns one byte, so every frame arrives in parts.
        val reader = FakeAgentConnection(readChunk = 1).apply {
            send(writer.written)
            close()
        }
        val input = FramedAgentPacketChannel(reader.stream, MAX)
        packets.forEach { assertContentEquals(it, input.readPacket()) }
        assertNull(input.readPacket())
    }

    @Test
    fun invalidPacketsAreRejectedBeforeAnyByteIsWritten() = runTest {
        val connection = FakeAgentConnection()
        val channel = FramedAgentPacketChannel(connection.stream, MAX)
        assertFailsWith<InvalidAgentFrameException> { channel.writePacket(ByteArray(MAX + 1)) }
        assertFailsWith<InvalidAgentFrameException> { channel.writePacket(ByteArray(0)) }
        assertEquals(emptyList(), connection.events)
    }

    @Test
    fun aFramedSessionAnswersEachPacketUntilTheStreamEnds() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("a")
            sendPacket("bc")
            close()
        }
        AgentPacketSession(EchoProtocol()).serveFramed(connection.stream)
        assertEquals(listOf("a", "bc"), connection.writtenPackets())
    }

    @Test
    fun aFramedSessionEndsQuietlyWithinATruncatedFrame() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("a")
            send(byteArrayOf(0, 0, 0, 2, 1))
            close()
        }
        AgentPacketSession(EchoProtocol()).serveFramed(connection.stream)
        assertEquals(listOf("a"), connection.writtenPackets())
    }

    @Test
    fun aFramedSessionFailsOnAnInvalidFrame() = runTest {
        val connection = FakeAgentConnection().apply { send(header(MAX + 1)) }
        assertFailsWith<InvalidAgentFrameException> {
            AgentPacketSession(EchoProtocol()).serveFramed(connection.stream)
        }
    }

    @Test
    fun aFramedSessionEndsAnIdleConnectionAfterTheTimeout() = runTest {
        val connection = FakeAgentConnection().apply { sendPacket("a") }
        AgentPacketSession(EchoProtocol()).serveFramed(connection.stream, ioTimeoutMs = 1_000)
        assertEquals(listOf("a"), connection.writtenPackets())
        assertEquals(1_000, currentTime)
        assertContains(connection.events, "read cancelled")
    }

    @Test
    fun aFramedSessionEndsWhenAWriteRunsOutOfTime() = runTest {
        val connection = FakeAgentConnection().apply {
            sendPacket("a")
            sendPacket("b")
            writeGate = CompletableDeferred()
        }
        AgentPacketSession(EchoProtocol()).serveFramed(connection.stream, ioTimeoutMs = 1_000)
        assertEquals(1_000, currentTime)
        assertContains(connection.events, "write cancelled")
        // The header and the body of the first packet; the second one is never read.
        assertEquals(2, connection.events.count { it == "read" })
    }

    /** Echoes every request. */
    private class EchoProtocol : AgentPacketProtocol<String, String> {
        override val maxPacketSize = MAX

        override fun decode(packet: ByteArray) = packet.decodeToString()

        override fun encode(response: String) = response.encodeToByteArray()

        override suspend fun handle(request: String, authenticated: Boolean) = request

        override fun authenticatedAfter(request: String, response: String, authenticated: Boolean): Boolean? = null
    }
}
