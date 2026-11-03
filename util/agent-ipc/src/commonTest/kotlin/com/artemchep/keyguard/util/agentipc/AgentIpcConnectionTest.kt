package com.artemchep.keyguard.util.agentipc

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The listener and connection API over the real native library. */
class AgentIpcConnectionTest {
    @Test
    fun theEndpointIsConnectableOnReturnAndThePeerIsThisProcess() = agentIpcTest {
        AgentIpcListener.bind(agentIpcTestLocation()).use { listener ->
            // One attempt, before any accept runs.
            val client = assertNotNullClient(tryConnectAgentIpcTestClient(listener.endpoint))
            client.use {
                listener.accept().use { connection ->
                    assertEquals(currentProcessId(), connection.peerPid)
                }
            }
        }
    }

    @Test
    fun bytesFlowBothWaysAndTheEndOfTheStreamIsMinusOne() = agentIpcTest {
        withConnection { connection, client ->
            client.write("ping".encodeToByteArray())
            withTimeout(BOUND) { connection.awaitInputOrDisconnect() }
            assertEquals("ping", connection.readExactly(4).decodeToString())
            connection.write("pong".encodeToByteArray())
            assertEquals("pong", client.readExactly(4).decodeToString())

            client.close()
            assertEquals(-1, withTimeout(BOUND) { connection.read(ByteArray(8)) })
            // The observer returns at once at the end of the stream.
            withTimeout(BOUND) { connection.awaitInputOrDisconnect() }
        }
    }

    @Test
    fun theObserverDoesNotConsumeInput() = agentIpcTest {
        withConnection { connection, client ->
            client.write(byteArrayOf(42))
            withTimeout(BOUND) { connection.awaitInputOrDisconnect() }
            // A second wait sees the same unread byte.
            withTimeout(BOUND) { connection.awaitInputOrDisconnect() }
            val byte = ByteArray(1)
            assertEquals(1, withTimeout(BOUND) { connection.read(byte) })
            assertContentEquals(byteArrayOf(42), byte)
        }
    }

    @Test
    fun theObserverReturnsQuietlyOnLocalClose() = agentIpcTest {
        withConnection { connection, client ->
            val observer = async { connection.awaitInputOrDisconnect() }
            delay(100)
            assertFalse(observer.isCompleted)

            connection.close()
            withTimeout(BOUND) { observer.await() }
            withTimeout(BOUND) { connection.awaitInputOrDisconnect() }
            val error = assertFailsWith<AgentIpcException> { connection.read(ByteArray(1)) }
            assertEquals(AgentIpcFailureKind.CLOSED, error.kind)
            assertEquals(0, client.readToEnd().size)
        }
    }

    @Test
    fun zeroLengthRules() = agentIpcTest {
        withConnection { connection, client ->
            val empty = assertFailsWith<AgentIpcException> { connection.read(ByteArray(4), 4, 0) }
            assertEquals(AgentIpcFailureKind.INVALID_ARGUMENT, empty.kind)
            assertEquals("read: empty_buffer", empty.diagnostic)
            val range = assertFailsWith<AgentIpcException> { connection.read(ByteArray(4), 2, 3) }
            assertEquals(AgentIpcFailureKind.INVALID_ARGUMENT, range.kind)
            // An empty write is no write at all.
            connection.write(ByteArray(0))
            connection.write(ByteArray(4), 4, 0)
            // A failed call does not close the connection.
            client.write(byteArrayOf(7))
            assertContentEquals(byteArrayOf(7), connection.readExactly(1))

            connection.close()
            val closed = assertFailsWith<AgentIpcException> { connection.write(ByteArray(0)) }
            assertEquals(AgentIpcFailureKind.CLOSED, closed.kind)
        }
    }

    @Test
    fun aReadFillsAtMostOneMebibyte() = agentIpcTest {
        withConnection { connection, client ->
            val payload = pattern(MIB + 17)
            val writing = CoroutineScope(Dispatchers.IO).async { client.write(payload) }
            val buffer = ByteArray(2 * MIB)
            var received = 0
            while (received < payload.size) {
                val count = withTimeout(BOUND) { connection.read(buffer, received, buffer.size - received) }
                assertTrue(count in 1..MIB, "read $count bytes")
                received += count
            }
            withTimeout(BOUND) { writing.await() }
            assertContentEquals(payload, buffer.copyOf(received))
        }
    }

    @Test
    fun aWriteToAClosedPeerIsPeerClosed() = agentIpcTest {
        withConnection { connection, client ->
            client.close()
            val error = assertFailsWith<AgentIpcException> {
                withTimeout(BOUND) {
                    // The first writes may still fit the buffers of a closing socket.
                    repeat(64) { connection.write(pattern(64 * 1024)) }
                }
            }
            assertEquals(AgentIpcFailureKind.PEER_CLOSED, error.kind)
        }
    }

    @Test
    fun cancellationClosesConnection() = agentIpcTest {
        withConnection { connection, client ->
            val reading = launch { connection.read(ByteArray(8)) }
            delay(100)
            withTimeout(BOUND) { reading.cancelAndJoin() }
            assertEquals(0, client.readToEnd().size)
            val error = assertFailsWith<AgentIpcException> { connection.read(ByteArray(8)) }
            assertEquals(AgentIpcFailureKind.CLOSED, error.kind)
        }
    }

    @Test
    fun cancellingTheObserverClosesTheConnection() = agentIpcTest {
        withConnection { connection, client ->
            val observing = launch { connection.awaitInputOrDisconnect() }
            delay(100)
            withTimeout(BOUND) { observing.cancelAndJoin() }
            assertEquals(0, client.readToEnd().size)
        }
    }

    @Test
    fun cancellingAnAcceptClosesTheListener() = agentIpcTest {
        val listener = AgentIpcListener.bind(agentIpcTestLocation())
        listener.use {
            val accepting = launch { listener.accept() }
            delay(100)
            withTimeout(BOUND) { accepting.cancelAndJoin() }
            val error = assertFailsWith<AgentIpcException> { listener.accept() }
            assertEquals(AgentIpcFailureKind.CLOSED, error.kind)
            // The endpoint is gone.
            assertNull(tryConnectAgentIpcTestClient(listener.endpoint)?.also { it.close() })
        }
    }

    @Test
    fun acceptResultDiscardedIsClosed() = runTest {
        val listener = AgentIpcListener.bind(agentIpcTestLocation())
        val client = connectAgentIpcTestClient(listener.endpoint)
        try {
            val calls = QueuedDispatcher()
            var accepted: AgentIpcConnection? = null
            val caller = launch { accepted = listener.accept(calls) }
            // Starts the caller, which queues the native accept.
            testScheduler.runCurrent()
            // The client is already connected, so the accept returns at once and
            // queues the caller's resumption on the test scheduler.
            calls.runNext()
            caller.cancel()
            caller.join()

            assertTrue(caller.isCancelled)
            assertNull(accepted)
            // The connection was closed, and the cancelled accept closed the listener.
            assertEquals(0, withContext(Dispatchers.Default) { client.readToEnd() }.size)
            val error = assertFailsWith<AgentIpcException> { listener.accept() }
            assertEquals(AgentIpcFailureKind.CLOSED, error.kind)
        } finally {
            client.close()
            listener.close()
        }
    }

    @Test
    fun closeIsIdempotent() = agentIpcTest {
        val listener = AgentIpcListener.bind(agentIpcTestLocation())
        listener.close()
        listener.close()
        withConnectionOf(AgentIpcListener.bind(agentIpcTestLocation())) { connection, _ ->
            connection.close()
            connection.close()
        }
    }

    private suspend fun withConnection(
        block: suspend CoroutineScope.(AgentIpcConnection, AgentIpcTestClient) -> Unit,
    ) = withConnectionOf(AgentIpcListener.bind(agentIpcTestLocation()), block)

    private suspend fun withConnectionOf(
        listener: AgentIpcListener,
        block: suspend CoroutineScope.(AgentIpcConnection, AgentIpcTestClient) -> Unit,
    ) {
        listener.use {
            connectAgentIpcTestClient(listener.endpoint).use { client ->
                withTimeout(BOUND) { listener.accept() }.use { connection ->
                    withContext(Dispatchers.Default) { block(connection, client) }
                }
            }
        }
    }

    private fun assertNotNullClient(client: AgentIpcTestClient?): AgentIpcTestClient =
        client ?: throw AssertionError("The endpoint is not connectable")

    /** Runs dispatched blocks only when the test asks. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = Channel<Runnable>(Channel.UNLIMITED)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(queue.trySend(block).isSuccess)
        }

        fun runNext() {
            queue.tryReceive().getOrThrow().run()
        }
    }
}

private const val MIB = 1 shl 20
