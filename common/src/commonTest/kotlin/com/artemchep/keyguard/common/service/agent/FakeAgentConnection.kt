package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * One connection in memory, as a server sees it through [stream]. The test plays
 * the client: it [send]s bytes and [close]s its side. Every call of the stream is
 * recorded in [events], in order: `read`, `write`, `wrote` and `observe`, and how
 * each pending one ended (`read cancelled`, `write cancelled`, `observe returned`
 * or `observe cancelled`).
 */
internal class FakeAgentConnection(
    /** The most bytes that one read returns. */
    private val readChunk: Int = Int.MAX_VALUE,
) {
    private val inbound = mutableListOf<Byte>()
    private var consumed = 0
    private var ended = false

    // Bumped on every change of the inbound side, to wake pending reads.
    private val changes = MutableStateFlow(0)

    private val output = mutableListOf<Byte>()

    val events = mutableListOf<String>()

    /** Every buffer the server read into, so that a test can check it was zeroed. */
    val readBuffers = mutableListOf<ByteArray>()

    /** Thrown by every later read, as a transport failure. */
    var readFailure: Throwable? = null

    /** Thrown by every later write, as a transport failure. */
    var writeFailure: Throwable? = null

    /** While set, every write waits for it. */
    var writeGate: CompletableDeferred<Unit>? = null

    val stream = AgentByteStream(
        read = ::read,
        write = ::write,
        awaitInputOrDisconnect = ::observe,
    )

    /** The bytes the server wrote. */
    val written: ByteArray get() = output.toByteArray()

    /** The packets the server wrote, as text. */
    fun writtenPackets(): List<String> {
        val bytes = written
        val packets = mutableListOf<String>()
        var position = 0
        while (position < bytes.size) {
            val header = bytes.copyOfRange(position, position + AgentIpcFraming.HEADER_SIZE)
            val size = AgentIpcFraming.decodeHeader(header)
            position += AgentIpcFraming.HEADER_SIZE
            packets += bytes.copyOfRange(position, position + size).decodeToString()
            position += size
        }
        return packets
    }

    fun send(bytes: ByteArray) {
        inbound += bytes.toList()
        changes.value += 1
    }

    fun sendPacket(text: String) {
        val packet = text.encodeToByteArray()
        send(AgentIpcFraming.encodeHeader(packet.size, Int.MAX_VALUE) + packet)
    }

    /** Ends the client's side of the stream. */
    fun close() {
        ended = true
        changes.value += 1
    }

    private suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        events += "read"
        readBuffers += buffer
        readFailure?.let { throw it }
        record("read cancelled") { awaitInput() }
        val count = minOf(length, readChunk, inbound.size - consumed)
        if (count == 0) {
            return -1
        }
        for (index in 0 until count) {
            buffer[offset + index] = inbound[consumed + index]
        }
        consumed += count
        return count
    }

    private suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        events += "write"
        writeFailure?.let { throw it }
        record("write cancelled") { writeGate?.await() }
        for (index in offset until offset + length) {
            output += buffer[index]
        }
        events += "wrote"
    }

    private suspend fun observe() {
        events += "observe"
        record("observe cancelled") { awaitInput() }
        events += "observe returned"
    }

    private suspend fun awaitInput() {
        changes.first { inbound.size > consumed || ended }
    }

    private suspend fun record(cancelled: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            events += cancelled
            throw e
        }
    }
}
