package com.artemchep.keyguard.util.ffi

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
import kotlin.test.assertTrue
import kotlin.test.fail

class NativeOperationTest {
    @Test
    fun successReturnsTheResultAndClosesTheHandle() = runTest {
        val handles = FakeHandles()
        val result = runOperation(handles) { byteArrayOf(1, 2, 3) }

        assertContentEquals(byteArrayOf(1, 2, 3), result)
        assertEquals(listOf(HANDLE), handles.closed)
        assertEquals(emptyList(), handles.canceled)
        assertEquals(0, handles.cleared)
    }

    @Test
    fun busyHandleFailsWithoutExecuting() = runTest {
        val handles = FakeHandles(handle = 0L)
        val error = assertFailsWith<Failure> {
            runOperation(handles) { fail("A busy handle must not execute") }
        }

        assertEquals("busy", error.message)
        assertEquals(emptyList(), handles.closed)
    }

    @Test
    fun createFailurePassesThroughWithoutClosing() = runTest {
        val handles = FakeHandles(createFailure = Failure("unavailable"))
        val error = assertFailsWith<Failure> {
            runOperation(handles) { fail("A failed create must not execute") }
        }

        assertEquals("unavailable", error.message)
        assertEquals(emptyList(), handles.closed)
    }

    @Test
    fun executeFailurePropagatesAndClosesOnce() = runTest {
        val handles = FakeHandles()
        val error = assertFailsWith<Failure> {
            runOperation(handles) { throw Failure("protocol") }
        }

        assertEquals("protocol", error.message)
        assertEquals(listOf(HANDLE), handles.closed)
        assertEquals(0, handles.cleared)
    }

    @Test
    fun cancellationDuringExecuteCancelsClosesAndClears() = runTest {
        val handles = FakeHandles()
        val result = byteArrayOf(1, 2, 3)
        lateinit var caller: Job
        caller = launch {
            runOperation(handles) {
                caller.cancel()
                result
            }
            fail("A cancelled caller must not receive the result")
        }
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals(listOf(HANDLE), handles.canceled)
        assertEquals(listOf(HANDLE), handles.closed)
        assertContentEquals(ByteArray(3), result)
    }

    @Test
    fun resultDiscardedWhileDispatchingBackIsCleared() = runTest {
        val handles = FakeHandles()
        val result = byteArrayOf(1, 2, 3)
        lateinit var caller: Job
        caller = launch {
            runOperation(handles) {
                // Queued ahead of the caller's resumption, so withContext drops the result.
                this@runTest.launch { caller.cancel() }
                result
            }
            fail("A cancelled caller must not receive the result")
        }
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals(emptyList(), handles.canceled)
        assertEquals(listOf(HANDLE), handles.closed)
        assertContentEquals(ByteArray(3), result)
    }

    @Test
    fun callerCancelledBeforeDispatchNeverCreatesAHandle() = runTest {
        val handles = FakeHandles()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            runOperation(handles) { fail("A cancelled caller must not execute") }
        }
        caller.cancel()
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals(emptyList(), handles.created)
    }

    private suspend fun TestScope.runOperation(
        handles: FakeHandles,
        execute: (Long) -> ByteArray,
    ): ByteArray = runNativeOperation(
        // A distinct dispatcher, so withContext really dispatches.
        context = StandardTestDispatcher(testScheduler),
        create = handles::create,
        cancel = handles.canceled::add,
        close = handles.closed::add,
        busy = { Failure("busy") },
        clear = { value ->
            handles.cleared += 1
            value.fill(0)
        },
        execute = execute,
    )

    private class FakeHandles(
        private val handle: Long = HANDLE,
        private val createFailure: Throwable? = null,
    ) {
        val created = mutableListOf<Long>()
        val canceled = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        var cleared = 0

        fun create(): Long {
            createFailure?.let { throw it }
            created += handle
            return handle
        }
    }

    private class Failure(message: String) : RuntimeException(message)
}

private const val HANDLE = 7L
