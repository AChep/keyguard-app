package com.artemchep.keyguard.util.agentipc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** The cancellation contract of [agentIpcCall], with fake calls. */
class AgentIpcCallTest {
    @Test
    fun successReturnsTheResultWithoutClosing() = runTest {
        val calls = FakeCalls()
        val result = call(calls) { byteArrayOf(1, 2, 3) }

        assertContentEquals(byteArrayOf(1, 2, 3), result)
        assertEquals(0, calls.closed)
        assertEquals(0, calls.cleared)
    }

    @Test
    fun failurePropagatesWithoutClosing() = runTest {
        val calls = FakeCalls()
        val failure = AgentIpcException(AgentIpcFailureKind.PEER_CLOSED)
        val error = assertFailsWith<AgentIpcException> {
            call(calls) { throw failure }
        }

        assertSame(failure, error)
        assertEquals(0, calls.closed)
        assertEquals(0, calls.cleared)
    }

    @Test
    fun cancellationDuringTheCallClosesAndClears() = runTest {
        val calls = FakeCalls()
        val result = byteArrayOf(1, 2, 3)
        lateinit var caller: Job
        caller = launch {
            call(calls) {
                // Runs the handler registered before the call, as a cancelling thread would.
                caller.cancel()
                assertEquals(1, calls.closed)
                result
            }
            fail("A cancelled caller must not receive the result")
        }
        caller.join()

        assertTrue(caller.isCancelled)
        assertTrue(calls.closed >= 1)
        assertTrue(calls.cleared >= 1)
        assertContentEquals(ByteArray(3), result)
    }

    @Test
    fun resultDiscardedWhileDispatchingBackIsCleared() = runTest {
        val calls = FakeCalls()
        val result = byteArrayOf(1, 2, 3)
        lateinit var caller: Job
        caller = launch {
            call(calls) {
                // Queued ahead of the caller's resumption, so withContext drops the result.
                this@runTest.launch { caller.cancel() }
                result
            }
            fail("A cancelled caller must not receive the result")
        }
        caller.join()

        assertTrue(caller.isCancelled)
        assertTrue(calls.cleared >= 1)
        // Cancellation destroys, also when it arrives after the call returned.
        assertTrue(calls.closed >= 1)
        assertContentEquals(ByteArray(3), result)
    }

    @Test
    fun callerCancelledBeforeDispatchClosesWithoutCalling() = runTest {
        val calls = FakeCalls()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            call(calls) { fail("A cancelled caller must not call") }
        }
        caller.cancel()
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals(1, calls.closed)
        assertEquals(0, calls.cleared)
    }

    private suspend fun TestScope.call(
        calls: FakeCalls,
        execute: () -> ByteArray,
    ): ByteArray = agentIpcCall(
        close = { calls.closed += 1 },
        clear = { value ->
            calls.cleared += 1
            value.fill(0)
        },
        // A distinct dispatcher, so withContext really dispatches.
        context = StandardTestDispatcher(testScheduler),
        execute = execute,
    )

    private class FakeCalls {
        var closed = 0
        var cleared = 0
    }
}
