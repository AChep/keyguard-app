package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// One function per scenario.
@Suppress("TooManyFunctions")
class TransportSelectorTest {
    private class FakeStream : ByteStream {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)

        var closed = false

        override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()

        override suspend fun write(bytes: ByteArray) = Unit

        override suspend fun awaitWritable() = Unit

        override suspend fun close() {
            closed = true
            incoming.close()
        }
    }

    private class FakeTransport(
        override val kind: TransportKind,
        val behavior: suspend (ConnectRole) -> ByteStream,
    ) : Transport {
        var startedAt: Long? = null

        lateinit var clock: () -> Long

        override suspend fun connect(peerId: String, role: ConnectRole): ByteStream {
            startedAt = clock()
            return behavior(role)
        }
    }

    private fun TestScope.selector(vararg transports: FakeTransport): TransportSelector {
        transports.forEach { it.clock = { currentTime } }
        return TransportSelector(transports.toList())
    }

    @Test
    fun `WebRTC that opens within its head start wins alone`() = runTest {
        val stream = FakeStream()
        val webRtc = FakeTransport(TransportKind.WEBRTC) {
            delay(1_000L)
            stream
        }
        val relay = FakeTransport(TransportKind.RELAY) { awaitCancellation() }
        val selected = selector(webRtc, relay).connect("peer", ConnectRole.INITIATOR)
        assertEquals(TransportKind.WEBRTC, selected.kind)
        assertSame(stream, selected.stream)
        assertEquals(null, relay.startedAt)
    }

    @Test
    fun `the relay joins the race after the head start, and the first stream that opens wins`() = runTest {
        val webRtc = FakeTransport(TransportKind.WEBRTC) { awaitCancellation() }
        val relayStream = FakeStream()
        val relay = FakeTransport(TransportKind.RELAY) {
            delay(500L)
            relayStream
        }
        val selected = selector(webRtc, relay).connect("peer", ConnectRole.INITIATOR)
        assertEquals(TransportKind.RELAY, selected.kind)
        assertEquals(TransportSelector.HEAD_START_MS, relay.startedAt)
        assertEquals(TransportSelector.HEAD_START_MS + 500L, currentTime)
    }

    @Test
    fun `a stream that opens after the winner closes`() = runTest {
        val late = FakeStream()
        // Opens a moment after the relay, and does not stop for the cancellation.
        val webRtc = FakeTransport(TransportKind.WEBRTC) {
            withContext(NonCancellable) { delay(TransportSelector.HEAD_START_MS + 1_000L) }
            late
        }
        val relay = FakeTransport(TransportKind.RELAY) {
            delay(500L)
            FakeStream()
        }
        val selected = async { selector(webRtc, relay).connect("peer", ConnectRole.INITIATOR) }
        testScheduler.advanceUntilIdle()
        assertEquals(TransportKind.RELAY, selected.await().kind)
        assertTrue(late.closed)
    }

    @Test
    fun `a transport that fails hands over at once`() = runTest {
        val webRtc = FakeTransport(TransportKind.WEBRTC) {
            delay(300L)
            throw TransportException("ICE failed")
        }
        val relay = FakeTransport(TransportKind.RELAY) { FakeStream() }
        val selected = selector(webRtc, relay).connect("peer", ConnectRole.INITIATOR)
        assertEquals(TransportKind.RELAY, selected.kind)
        assertEquals(300L, relay.startedAt)
    }

    @Test
    fun `only the transports both devices support take part`() = runTest {
        val webRtc = FakeTransport(TransportKind.WEBRTC) { awaitCancellation() }
        val relay = FakeTransport(TransportKind.RELAY) { FakeStream() }
        val selector = selector(webRtc, relay)
        assertEquals(setOf(TransportKind.WEBRTC, TransportKind.RELAY), selector.kinds)

        // A watch lists the relay only: no wait on WebRTC.
        val selected = selector.connect("watch", ConnectRole.INITIATOR, setOf(TransportKind.RELAY))
        assertEquals(TransportKind.RELAY, selected.kind)
        assertEquals(0L, relay.startedAt)
        assertEquals(null, webRtc.startedAt)
        // A peer that did not say gets every transport.
        assertEquals(listOf<Transport>(webRtc, relay), selector.candidates(null))

        val relayOnly = selector(FakeTransport(TransportKind.RELAY) { FakeStream() })
        assertFailsWith<TransportException> {
            relayOnly.connect("phone", ConnectRole.INITIATOR, setOf(TransportKind.WEBRTC))
        }
    }

    @Test
    fun `the responder takes the stream the first message arrives on`() = runTest {
        val silent = FakeStream()
        val chosen = FakeStream()
        val webRtc = FakeTransport(TransportKind.WEBRTC) { silent }
        val relay = FakeTransport(TransportKind.RELAY) {
            delay(1_000L)
            chosen
        }
        val selected = async { selector(webRtc, relay).connect("peer", ConnectRole.RESPONDER) }
        runCurrent()
        // Both wait at once.
        assertEquals(0L, webRtc.startedAt)
        assertEquals(0L, relay.startedAt)

        testScheduler.advanceTimeBy(2_000L)
        chosen.incoming.send(byteArrayOf(1))
        chosen.incoming.send(byteArrayOf(2))
        val stream = selected.await()
        assertEquals(TransportKind.RELAY, stream.kind)
        assertTrue(silent.closed)
        assertFalse(chosen.closed)
        // The first message is read again.
        assertContentEquals(byteArrayOf(1), stream.stream.read())
        assertContentEquals(byteArrayOf(2), stream.stream.read())
    }

    @Test
    fun `a responder stream that stays silent is given up`() = runTest {
        val silent = FakeStream()
        val selector = TransportSelector(
            transports = listOf(FakeTransport(TransportKind.RELAY) { silent }.also { it.clock = { currentTime } }),
            firstMessageTimeoutMs = 1_000L,
        )
        assertFailsWith<TransportException> { selector.connect("peer", ConnectRole.RESPONDER) }
        assertTrue(silent.closed)
    }

    @Test
    fun `when every transport fails the last failure is the cause`() = runTest {
        val webRtc = FakeTransport(TransportKind.WEBRTC) { throw TransportException("ICE failed") }
        val relay = FakeTransport(TransportKind.RELAY) {
            delay(100L)
            throw TransportException("unreachable")
        }
        val e = assertFailsWith<TransportException> {
            selector(webRtc, relay).connect("peer", ConnectRole.INITIATOR)
        }
        // The debug mode of coroutines may wrap it in a copy.
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message == "unreachable" })
    }

    @Test
    fun `a cancelled connect stops every attempt`() = runTest {
        var stopped = 0
        val pending: suspend (ConnectRole) -> ByteStream = {
            try {
                awaitCancellation()
            } finally {
                stopped += 1
            }
        }
        val selector = selector(
            FakeTransport(TransportKind.WEBRTC, pending),
            FakeTransport(TransportKind.RELAY, pending),
        )
        val attempt = async { selector.connect("peer", ConnectRole.RESPONDER) }
        runCurrent()
        attempt.cancel()
        assertFailsWith<CancellationException> { attempt.await() }
        assertEquals(2, stopped)
    }
}
