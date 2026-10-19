package com.artemchep.keyguard.presentation.operation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SingleOperationTest {
    private fun TestScope.operation() =
        SingleOperation(TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `an immediate operation finishes`() = runTest {
        val operation = operation()
        assertTrue(operation.launch {})
        assertEquals(SingleOperationState(), operation.state.value)
    }

    @Test
    fun `one operation runs at a time`() = runTest {
        val operation = operation()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        assertTrue(operation.launch { calls++; gate.await() })
        assertTrue(operation.state.value.busy)
        assertFalse(operation.launch { calls++ })
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, calls)
        assertFalse(operation.state.value.busy)
    }

    @Test
    fun `a failure is reported until the next operation`() = runTest {
        val operation = operation()
        operation.launch { throw IllegalStateException() }
        assertEquals(SingleOperationState(failed = true), operation.state.value)
        operation.launch {}
        assertEquals(SingleOperationState(), operation.state.value)
    }

    @Test
    fun `a timeout inside the operation is a failure`() = runTest {
        val operation = operation()
        operation.launch { withTimeout(1) { awaitCancellation() } }
        testScheduler.advanceUntilIdle()
        assertEquals(SingleOperationState(failed = true), operation.state.value)
    }

    @Test
    fun `an interrupted operation never changes the state`() = runTest {
        val operation = operation()
        val gate = CompletableDeferred<Unit>()
        operation.launch(interruptible = true) {
            withContext(NonCancellable) { gate.await() }
            throw IllegalStateException()
        }
        assertTrue(operation.interrupt())
        assertEquals(SingleOperationState(), operation.state.value)
        val next = CompletableDeferred<Unit>()
        var started = false
        assertTrue(operation.launch { started = true; next.await() })
        assertEquals(SingleOperationState(busy = true), operation.state.value)
        // The next operation waits for the interrupted one.
        assertFalse(started)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(started)
        assertEquals(SingleOperationState(busy = true), operation.state.value)
        next.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(SingleOperationState(), operation.state.value)
    }

    @Test
    fun `an operation interrupted while waiting still waits`() = runTest {
        val operation = operation()
        val gate = CompletableDeferred<Unit>()
        operation.launch(interruptible = true) {
            withContext(NonCancellable) { gate.await() }
        }
        operation.interrupt()
        operation.launch(interruptible = true) {}
        operation.interrupt()
        var started = false
        operation.launch { started = true }
        assertFalse(started)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(started)
    }

    @Test
    fun `an operation that is not interruptible keeps running`() = runTest {
        val operation = operation()
        val gate = CompletableDeferred<Unit>()
        operation.launch {
            gate.await()
            throw IllegalStateException()
        }
        assertFalse(operation.interrupt())
        assertEquals(SingleOperationState(busy = true), operation.state.value)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(SingleOperationState(failed = true), operation.state.value)
    }

    @Test
    fun `an interrupt forgets the last failure`() = runTest {
        val operation = operation()
        operation.launch { throw IllegalStateException() }
        assertTrue(operation.interrupt())
        assertEquals(SingleOperationState(), operation.state.value)
    }

    @Test
    fun `an operation does not start in a cancelled scope`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val operation = SingleOperation(scope)
        scope.cancel()
        assertFalse(operation.launch {})
        assertEquals(SingleOperationState(), operation.state.value)
    }
}
