package com.artemchep.keyguard.util.agentipc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(AgentIpcOwnProcessTestApi::class, ExperimentalCoroutinesApi::class)
class AgentIpcHostTest {
    private var nextFakeHandle = Long.MAX_VALUE

    // A handle the registry never issues: closing it is a harmless -5.
    private fun fakeConnection(peerPid: Long?) = AgentIpcConnection(nextFakeHandle--, peerPid)

    /** A peer whose process ID is [samples] in turn, then the last one forever. */
    private fun peerOf(vararg samples: Long?): AgentIpcExpectedPeer {
        var next = 0
        return AgentIpcExpectedPeer { samples[minOf(next++, samples.lastIndex)] }
    }

    private fun ioFailure(): suspend () -> AgentIpcConnection =
        { throw AgentIpcException(AgentIpcFailureKind.IO, diagnostic = "accept: accept4 (os 24)") }

    @Test
    fun gateHoldsUntilPeerIsKnown() = runTest {
        val source = FakeSource()
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 8, events::add)
        val gate = CompletableDeferred<AgentIpcExpectedPeer>()
        val serving = launch { host.serve({ gate.await() }) { fail("Nothing connects") } }
        advanceUntilIdle()
        assertEquals(0, source.accepts)
        assertEquals(emptyList(), events)

        gate.complete(peerOf(PID))
        advanceUntilIdle()
        assertEquals(listOf<AgentIpcEvent>(AgentIpcEvent.Listening), events)
        assertEquals(1, source.accepts)
        host.stop()
        serving.join()
        assertTrue(source.closed)
    }

    @Test
    fun stopWhileGatedReturns() = runTest {
        val source = FakeSource()
        val host = AgentIpcHost(source, 8) { fail("Nothing happens: $it") }
        var gateCancelled = false
        val serving = launch {
            host.serve({
                try {
                    awaitCancellation()
                } finally {
                    gateCancelled = true
                }
            }) { fail("Nothing connects") }
        }
        advanceUntilIdle()
        host.stop()
        serving.join()
        assertTrue(gateCancelled)
        assertEquals(0, source.accepts)
        assertTrue(source.closed)

        // A stopped host stays stopped.
        host.stop()
        host.serve({ peerOf(PID) }) { fail("Nothing connects") }
        assertEquals(0, source.accepts)
    }

    @Test
    fun aFailingGateEndsServeAndStopsTheHost() = runTest {
        val source = FakeSource()
        val host = AgentIpcHost(source, 8) { fail("Nothing happens: $it") }
        val error = assertFailsWith<IllegalStateException> {
            host.serve({ error("no helper") }) { fail("Nothing connects") }
        }
        assertEquals("no helper", error.message)
        assertTrue(source.closed)
    }

    @Test
    fun ioFailuresAreRetriedWithBackoffAndFatalAfterTwenty() = runTest {
        val source = FakeSource(List(AGENT_IPC_MAX_ACCEPT_FAILURES + 5) { ioFailure() })
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 8, events::add)
        val error = assertFailsWith<AgentIpcException> {
            host.serve({ peerOf(PID) }) { fail("Nothing connects") }
        }
        assertEquals(AgentIpcFailureKind.IO, error.kind)
        assertEquals(AGENT_IPC_MAX_ACCEPT_FAILURES, source.accepts)
        val failures = events.filterIsInstance<AgentIpcEvent.AcceptFailed>()
        assertEquals(AGENT_IPC_MAX_ACCEPT_FAILURES, failures.size)
        assertEquals(
            AgentIpcEvent.AcceptFailed(AgentIpcFailureKind.IO, "accept: accept4 (os 24)"),
            failures.first(),
        )
        // A delay after every failure but the last.
        assertEquals((AGENT_IPC_MAX_ACCEPT_FAILURES - 1) * AGENT_IPC_ACCEPT_BACKOFF_MILLIS, currentTime)
        assertTrue(source.closed)
    }

    @Test
    fun anAcceptedConnectionResetsTheFailureCount() = runTest {
        val almost = AGENT_IPC_MAX_ACCEPT_FAILURES - 1
        val connection = fakeConnection(PID)
        val source = FakeSource(List(almost) { ioFailure() } + listOf({ connection }) + List(almost) { ioFailure() })
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 8, events::add)
        val handled = mutableListOf<AgentIpcConnection>()
        val serving = launch { host.serve({ peerOf(PID) }) { handled += it } }
        advanceUntilIdle()
        assertEquals(listOf(connection), handled)
        assertEquals(2 * almost, events.count { it is AgentIpcEvent.AcceptFailed })
        assertTrue(serving.isActive)
        host.stop()
        serving.join()
    }

    @Test
    fun anInternalFailureIsFatalAtOnce() = runTest {
        val failure = AgentIpcException(AgentIpcFailureKind.INTERNAL, diagnostic = "accept: listener_failed")
        val source = FakeSource(listOf { throw failure })
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 8, events::add)
        val error = assertFailsWith<AgentIpcException> {
            host.serve({ peerOf(PID) }) { fail("Nothing connects") }
        }
        assertEquals(AgentIpcFailureKind.INTERNAL, error.kind)
        assertEquals(listOf<AgentIpcEvent>(AgentIpcEvent.Listening), events)
        assertEquals(0L, currentTime)
        assertTrue(source.closed)
    }

    @Test
    fun aClosedListenerEndsServe() = runTest {
        val source = FakeSource(listOf { throw AgentIpcException(AgentIpcFailureKind.CLOSED) })
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 8, events::add)
        host.serve({ peerOf(PID) }) { fail("Nothing connects") }
        assertEquals(listOf<AgentIpcEvent>(AgentIpcEvent.Listening), events)
        assertEquals(1, source.accepts)
    }

    @Test
    fun stopEndsTheBackoffAtOnce() = runTest {
        val source = FakeSource(List(AGENT_IPC_MAX_ACCEPT_FAILURES) { ioFailure() })
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 8, events::add)
        val serving = launch { host.serve({ peerOf(PID) }) { fail("Nothing connects") } }
        runCurrent()
        assertEquals(1, events.count { it is AgentIpcEvent.AcceptFailed })
        host.stop()
        serving.join()
        assertEquals(0L, currentTime)
        assertEquals(1, events.count { it is AgentIpcEvent.AcceptFailed })
    }

    @Test
    fun servesTheExpectedPeer() = runTest {
        val handled = mutableListOf<AgentIpcConnection>()
        val expected = fakeConnection(PID)
        val host = AgentIpcHost(FakeSource(listOf { expected }), 8) {}
        val serving = launch { host.serve({ peerOf(PID) }) { handled += it } }
        advanceUntilIdle()
        assertEquals(listOf(expected), handled)
        host.stop()
        serving.join()
    }

    @Test
    fun rejectsUnverifiedPeer() = runTest {
        // The kernel snapshot has no process ID.
        assertRejected(peerPid = null, peer = peerOf(PID), reason = AgentIpcEvent.Reason.PEER_UNVERIFIED)
    }

    @Test
    fun rejectsForeignPid() = runTest {
        assertRejected(peerPid = PID + 1, peer = peerOf(PID), reason = AgentIpcEvent.Reason.PEER_NOT_EXPECTED)
    }

    @Test
    fun rejectsPidChangeBetweenSamples() = runTest {
        assertRejected(peerPid = PID, peer = peerOf(PID, PID + 1), reason = AgentIpcEvent.Reason.PEER_NOT_RUNNING)
        // The child exited between the two samples.
        assertRejected(peerPid = PID, peer = peerOf(PID, null), reason = AgentIpcEvent.Reason.PEER_NOT_RUNNING)
    }

    @Test
    fun rejectsWhenChildNotRunning() = runTest {
        assertRejected(peerPid = PID, peer = peerOf(null), reason = AgentIpcEvent.Reason.PEER_NOT_RUNNING)
    }

    /** A connection from [peerPid] is closed unread, with [reason], when [peer] is expected. */
    private suspend fun TestScope.assertRejected(
        peerPid: Long?,
        peer: AgentIpcExpectedPeer,
        reason: AgentIpcEvent.Reason,
    ) {
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(FakeSource(listOf { fakeConnection(peerPid) }), 8, events::add)
        val serving = launch { host.serve({ peer }) { fail("A rejected peer is never handled") } }
        advanceUntilIdle()
        assertEquals(listOf(AgentIpcEvent.Listening, AgentIpcEvent.Rejected(reason)), events)
        host.stop()
        serving.join()
    }

    @Test
    fun overLimitIsRejectedNotQueued() = runTest {
        val release = CompletableDeferred<Unit>()
        val third = CompletableDeferred<Unit>()
        val first = fakeConnection(PID)
        val last = fakeConnection(PID)
        val source = FakeSource(
            listOf(
                { first },
                { fakeConnection(PID) },
                {
                    third.await()
                    last
                },
            ),
        )
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 1, events::add)
        val handled = mutableListOf<AgentIpcConnection>()
        val serving = launch {
            host.serve({ peerOf(PID) }) { connection ->
                handled += connection
                if (connection === first) release.await()
            }
        }
        advanceUntilIdle()
        val rejected = AgentIpcEvent.Rejected(AgentIpcEvent.Reason.OVER_LIMIT, 1)
        assertEquals(listOf(AgentIpcEvent.Listening, rejected), events)
        assertEquals(
            "Connection rejected: too many concurrent IPC connections (limit=1)",
            rejected.message,
        )

        // A slot frees when its handler returns.
        release.complete(Unit)
        advanceUntilIdle()
        third.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(first, last), handled)
        host.stop()
        serving.join()
    }

    @Test
    fun handlerFailuresAreMappedToEvents() = runTest {
        val outcomes = listOf<Pair<Throwable, AgentIpcEvent?>>(
            AgentIpcException(AgentIpcFailureKind.CLOSED) to null,
            AgentIpcException(AgentIpcFailureKind.PEER_CLOSED) to AgentIpcEvent.ClientDisconnected,
            AgentIpcException(AgentIpcFailureKind.IO, diagnostic = "read: recv (os 104)") to
                AgentIpcEvent.ConnectionFailed(AgentIpcFailureKind.IO, "read: recv (os 104)"),
            IllegalStateException("agent_ipc_busy") to null,
            CancellationException("the session itself") to null,
        )
        val connections = outcomes.map { fakeConnection(PID) }
        val source = FakeSource(connections.map { connection -> { connection } })
        val events = mutableListOf<AgentIpcEvent>()
        val host = AgentIpcHost(source, 8, events::add)
        val serving = launch {
            host.serve({ peerOf(PID) }) { connection ->
                throw outcomes[connections.indexOf(connection)].first
            }
        }
        advanceUntilIdle()
        assertTrue(serving.isActive)
        val session = events.filterIsInstance<AgentIpcEvent.SessionFailed>().single()
        assertTrue(session.error is IllegalStateException)
        assertEquals("IPC session failed: IllegalStateException", session.message)
        assertEquals(
            listOf(AgentIpcEvent.Listening) + outcomes.mapNotNull { it.second } + session,
            events,
        )
        host.stop()
        serving.join()
    }

    @Test
    fun eventMessagesAreFixedText() {
        val info = AgentIpcEvent.Severity.INFO
        val error = AgentIpcEvent.Severity.ERROR
        val expected = listOf(
            Triple(AgentIpcEvent.Listening, info, "IPC server listening"),
            Triple(AgentIpcEvent.ClientDisconnected, info, "Client disconnected"),
            Triple(
                AgentIpcEvent.Rejected(AgentIpcEvent.Reason.OVER_LIMIT, 8),
                error,
                "Connection rejected: too many concurrent IPC connections (limit=8)",
            ),
            Triple(
                AgentIpcEvent.Rejected(AgentIpcEvent.Reason.PEER_UNVERIFIED),
                error,
                "Connection rejected: the IPC peer could not be verified",
            ),
            Triple(
                AgentIpcEvent.Rejected(AgentIpcEvent.Reason.PEER_NOT_EXPECTED),
                error,
                "Connection rejected: the IPC peer is not the agent process",
            ),
            Triple(
                AgentIpcEvent.Rejected(AgentIpcEvent.Reason.PEER_NOT_RUNNING),
                error,
                "Connection rejected: the agent process is not running",
            ),
            // Diagnostics stay in their own field.
            Triple(
                AgentIpcEvent.AcceptFailed(AgentIpcFailureKind.IO, "accept: accept4 (os 24)"),
                error,
                "IPC accept failed: io",
            ),
            Triple(
                AgentIpcEvent.ConnectionFailed(AgentIpcFailureKind.INTERNAL, "read: panic"),
                error,
                "IPC connection failed: internal",
            ),
            // Never the exception's message, which may carry bytes.
            Triple(
                AgentIpcEvent.SessionFailed(IllegalArgumentException("secret 0x41")),
                error,
                "IPC session failed: IllegalArgumentException",
            ),
        )
        expected.forEach { (event, severity, message) ->
            assertEquals(severity, event.severity, message)
            assertEquals(message, event.message)
        }
    }

    @Test
    fun rejectedConnectionSeesEofWithoutHandler() = agentIpcTest {
        val events = Channel<AgentIpcEvent>(Channel.UNLIMITED)
        val host = AgentIpcHost(AgentIpcListener.bind(agentIpcTestLocation()), 8) { events.trySend(it) }
        // The real kernel snapshot is this process, which is not the expected child.
        val child = AgentIpcExpectedPeer { currentProcessId() + 1 }
        val serving = launch { host.serve({ child }) { fail("A rejected peer is never handled") } }
        try {
            connectAgentIpcTestClient(host.endpoint).use { client ->
                assertEquals(0, client.readToEnd().size)
            }
            assertEquals(AgentIpcEvent.Listening, withTimeout(BOUND) { events.receive() })
            assertEquals(
                AgentIpcEvent.Rejected(AgentIpcEvent.Reason.PEER_NOT_EXPECTED),
                withTimeout(BOUND) { events.receive() },
            )
        } finally {
            host.stop()
            withTimeout(BOUND) { serving.join() }
        }
    }

    @Test
    fun stopWithPendingAcceptReadWriteObserve() = agentIpcTest {
        val host = AgentIpcHost(AgentIpcListener.bind(agentIpcTestLocation()), 8)
        val blocked = Channel<Char>(Channel.UNLIMITED)
        val serving = launch {
            host.serve({ AgentIpcExpectedPeer.currentProcessForTests() }) { connection ->
                // The first byte says which call the handler blocks in.
                val mode = connection.readExactly(1).single().toInt().toChar()
                blocked.send(mode)
                when (mode) {
                    'r' -> connection.read(ByteArray(8))
                    'o' -> connection.awaitInputOrDisconnect()
                    // Larger than every socket and pipe buffer; the client never reads.
                    'w' -> connection.write(ByteArray(8 * 1024 * 1024))
                }
            }
        }
        val clients = "row".map { mode ->
            connectAgentIpcTestClient(host.endpoint).also { it.write(byteArrayOf(mode.code.toByte())) }
        }
        try {
            assertEquals(setOf('r', 'o', 'w'), List(3) { withTimeout(BOUND) { blocked.receive() } }.toSet())
            // Let each call block.
            delay(100)
            assertTrue(serving.isActive)
            host.stop()
            withTimeout(BOUND) { serving.join() }
            // Every connection ends, after what was written before the stop.
            clients.forEach { it.readToEnd() }
        } finally {
            clients.forEach { it.close() }
        }
    }

    @Test
    fun overLimitEof() = agentIpcTest {
        val events = Channel<AgentIpcEvent>(Channel.UNLIMITED)
        val host = AgentIpcHost(AgentIpcListener.bind(agentIpcTestLocation()), 1) { events.trySend(it) }
        val handled = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val serving = launch {
            host.serve({ AgentIpcExpectedPeer.currentProcessForTests() }) {
                handled.send(Unit)
                release.await()
            }
        }
        val held = connectAgentIpcTestClient(host.endpoint)
        try {
            withTimeout(BOUND) { handled.receive() }
            connectAgentIpcTestClient(host.endpoint).use { rejected ->
                assertEquals(0, rejected.readToEnd().size)
            }
            assertEquals(AgentIpcEvent.Listening, withTimeout(BOUND) { events.receive() })
            assertEquals(
                AgentIpcEvent.Rejected(AgentIpcEvent.Reason.OVER_LIMIT, 1),
                withTimeout(BOUND) { events.receive() },
            )

            // The slot frees once the handler returns and its connection is closed.
            release.complete(Unit)
            assertEquals(0, held.readToEnd().size)
            // The permit is released right after the close the client just saw.
            delay(100)
            connectAgentIpcTestClient(host.endpoint).use {
                withTimeout(BOUND) { handled.receive() }
            }
        } finally {
            held.close()
            host.stop()
            withTimeout(BOUND) { serving.join() }
        }
    }

    @Test
    fun acceptRacingStopLoop() = agentIpcTest {
        repeat(RACE_ROUNDS) { round ->
            val host = AgentIpcHost(AgentIpcListener.bind(agentIpcTestLocation()), 8)
            val serving = launch {
                host.serve({ AgentIpcExpectedPeer.currentProcessForTests() }) { connection ->
                    connection.awaitInputOrDisconnect()
                }
            }
            connectAgentIpcTestClient(host.endpoint).use { client ->
                // Vary where the stop lands: before, during or after the accept.
                repeat(round % 4) { yield() }
                if (round % 5 == 4) delay(1)
                host.stop()
                withTimeout(BOUND) { serving.join() }
                assertEquals(0, client.readToEnd().size)
            }
            assertFalse(serving.isActive)
        }
    }

    /** Hands out the connections, or failures, of [steps] in turn; then waits for close. */
    private class FakeSource(
        steps: List<suspend () -> AgentIpcConnection> = emptyList(),
    ) : AgentIpcAcceptSource {
        private val steps = ArrayDeque(steps)
        private val closedSignal = CompletableDeferred<Unit>()

        var accepts = 0
            private set

        val closed: Boolean get() = closedSignal.isCompleted

        override val endpoint: String = "fake"

        override suspend fun accept(): AgentIpcConnection {
            if (closed) throw AgentIpcException(AgentIpcFailureKind.CLOSED)
            accepts += 1
            val step = steps.removeFirstOrNull()
            if (step == null) {
                closedSignal.await()
                throw AgentIpcException(AgentIpcFailureKind.CLOSED)
            }
            return step()
        }

        override fun close() {
            closedSignal.complete(Unit)
        }
    }
}

private const val PID = 4242L

private const val RACE_ROUNDS = 25
