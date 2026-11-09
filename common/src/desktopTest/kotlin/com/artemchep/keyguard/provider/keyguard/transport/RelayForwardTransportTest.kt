package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.provider.keyguard.session.realTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RelayForwardTransportTest {
    private class Side(
        val client: TestRelayClient,
        val transport: RelayForwardTransport,
    )

    @Test
    fun `a stream carries ordered messages both ways`() = realTime {
        withPair { a, b ->
            val initiator = async { a.transport.connect("device-b", ConnectRole.INITIATOR) }
            // B starts listening after A's first open; the open repeats.
            delay(RelayForwardTransport.OPEN_RESEND_MS + 500L)
            val sb = withTimeout(TIMEOUT) { b.transport.connect("device-a", ConnectRole.RESPONDER) }
            val sa = withTimeout(TIMEOUT) { initiator.await() }

            val forward = List(MESSAGES) { i -> message(i, 1 + i * 600) }
            val backward = List(MESSAGES) { i -> message(500 + i, RelayForwardTransport.MAX_MESSAGE_SIZE - i) }
            launch { forward.forEach { sa.write(it) } }
            launch { backward.forEach { sb.write(it) } }
            forward.forEach { assertContentEquals(it, withTimeout(TIMEOUT) { sb.read() }) }
            backward.forEach { assertContentEquals(it, withTimeout(TIMEOUT) { sa.read() }) }

            sa.close()
            assertNull(withTimeout(TIMEOUT) { sb.read() })
            assertFailsWith<TransportException> { sa.write(byteArrayOf(1)) }
        }
    }

    @Test
    fun `a writer a window ahead of the reader waits for it`() = realTime {
        withPair { a, b ->
            val (sa, sb) = connect(a, b)
            // The only stream of the connection gets the most credit there is.
            val window = RelayForwardTransport.MAX_WINDOW
            repeat(window) { i -> withTimeout(TIMEOUT) { sa.write(message(i, 10)) } }
            val blocked = async { sa.write(message(window, 10)) }
            delay(500L)
            assertFalse(blocked.isCompleted)

            // Reading half of the window returns credit for it.
            repeat(window / 2) { i -> assertContentEquals(message(i, 10), withTimeout(TIMEOUT) { sb.read() }) }
            withTimeout(TIMEOUT) { blocked.await() }
            for (i in window / 2..window) {
                assertContentEquals(message(i, 10), withTimeout(TIMEOUT) { sb.read() })
            }
        }
    }

    @Test
    fun `the streams of a connection never hold more credit than its queue allows`() = realTime {
        // 16 queued frames: 12 for the streams, 4 for the other frames.
        val relay = TestRelay(limits = RelayLimits(queuedFrames = 16))
        withHub(relay = relay) { a, b, _ ->
            assertEquals(12, a.client.forwardCredit?.total)
            val streams = List(4) { connect(a, b) }
            // B writes to A on every stream; A does not read yet.
            val written = streams.map { (sa, sb) ->
                var count = 0
                while (withTimeoutOrNull(QUIET_MS) { sb.write(message(count, 10)) } != null) count += 1
                sa to count
            }
            assertEquals(12, written.sumOf { it.second })

            // As A reads, the credit spreads to every stream: none is stuck.
            val (sa, sb) = streams.first()
            repeat(written.first().second) { assertContentEquals(message(it, 10), withTimeout(TIMEOUT) { sa.read() }) }
            for ((other, theirs) in streams.drop(1)) {
                launch { repeat(3) { i -> theirs.write(message(100 + i, 10)) } }
                repeat(3) { i -> assertContentEquals(message(100 + i, 10), withTimeout(TIMEOUT) { other.read() }) }
            }
            sa.close()
            sb.close()
        }
    }

    @Test
    fun `a responder never takes the open of an attempt the initiator gave up`() = realTime {
        withPair(connectTimeoutMs = 1_000L) { a, b ->
            assertFailsWith<TransportException> { a.transport.connect("device-b", ConnectRole.INITIATOR) }
            // The close follows the open. Then, well within the freshness of the open, the
            // responder finds none: the close withdrew it.
            delay(QUIET_MS)
            assertFailsWith<TransportException> { b.transport.connect("device-a", ConnectRole.RESPONDER) }
        }
    }

    @Test
    fun `streams close when the peer leaves or a frame is dropped`() = realTime {
        withHub { a, b, hub ->
            val (sa, sb) = connect(a, b)
            b.client.emitError(RelayError.RateLimited)
            assertNull(withTimeout(TIMEOUT) { sb.read() })

            hub.leave(b.client)
            assertNull(withTimeout(TIMEOUT) { sa.read() })
        }
    }

    @Test
    fun `connect fails when nobody answers or the device is unknown`() = realTime {
        withPair(connectTimeoutMs = 1_000L) { a, b ->
            // No open arrives, then no accept.
            assertFailsWith<TransportException> { b.transport.connect("device-a", ConnectRole.RESPONDER) }
            assertFailsWith<TransportException> { a.transport.connect("device-b", ConnectRole.INITIATOR) }
            assertFailsWith<TransportException> { a.transport.connect("device-z", ConnectRole.INITIATOR) }
        }
    }

    @Test
    fun `oversized messages are refused`() = realTime {
        withPair { a, b ->
            val (sa, _) = connect(a, b)
            assertFailsWith<IllegalArgumentException> {
                sa.write(ByteArray(RelayForwardTransport.MAX_MESSAGE_SIZE + 1))
            }
        }
    }

    private suspend fun CoroutineScope.connect(a: Side, b: Side): Pair<ByteStream, ByteStream> {
        val initiator = async { a.transport.connect("device-b", ConnectRole.INITIATOR) }
        val sb = withTimeout(TIMEOUT) { b.transport.connect("device-a", ConnectRole.RESPONDER) }
        return withTimeout(TIMEOUT) { initiator.await() } to sb
    }

    private suspend fun withPair(
        connectTimeoutMs: Long = RelayForwardTransport.CONNECT_TIMEOUT_MS,
        block: suspend CoroutineScope.(Side, Side) -> Unit,
    ) = withHub(connectTimeoutMs) { a, b, _ -> block(a, b) }

    private suspend fun withHub(
        connectTimeoutMs: Long = RelayForwardTransport.CONNECT_TIMEOUT_MS,
        relay: TestRelay = TestRelay(),
        block: suspend CoroutineScope.(Side, Side, TestRelayRoom) -> Unit,
    ) = coroutineScope {
        val hub = relay.room()
        val clientA = hub.join()
        val clientB = hub.join()
        val devices = mapOf("device-a" to clientA.id, "device-b" to clientB.id)
        val a = Side(clientA, RelayForwardTransport(clientA, devices::get, Random(1)::nextBytes, connectTimeoutMs))
        val b = Side(clientB, RelayForwardTransport(clientB, devices::get, Random(2)::nextBytes, connectTimeoutMs))
        val jobs = listOf(launch { a.transport.run() }, launch { b.transport.run() })
        try {
            block(a, b, hub)
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private fun message(sequence: Int, size: Int): ByteArray =
        ByteArray(size) { index -> (sequence + index).toByte() }

    private companion object {
        const val MESSAGES = 50
        const val TIMEOUT = 10_000L

        /** How long a write may wait before it counts as blocked. */
        const val QUIET_MS = 300L
    }
}
