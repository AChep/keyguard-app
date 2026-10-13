package com.artemchep.keyguard.common.service.passkey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsPasskeyOperationTest {
    @Test
    fun `request disappearance after native acceptance preserves delayed history`() = runTest {
        val databaseAvailable = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val operation = WindowsPasskeyOperation(1, backgroundScope) { pending ->
            pending.complete {
                events += "accepted"
                // The database can remain busy beyond the next native request event.
                databaseAvailable.await()
                events += "history"
            }
        }
        operation.start()
        runCurrent()
        assertEquals(listOf("accepted"), events)

        operation.cancelIfPending()
        runCurrent()
        databaseAvailable.complete(Unit)
        runCurrent()
        assertEquals(listOf("accepted", "history"), events)
    }

    @Test
    fun `native cancellation before completion prevents response and history`() = runTest {
        val selection = CompletableDeferred<Unit>()
        var completed = false
        var cancelled = false
        val operation = WindowsPasskeyOperation(1, backgroundScope) { pending ->
            try {
                selection.await()
                pending.complete { completed = true }
            } finally {
                cancelled = true
            }
        }
        operation.start()
        runCurrent()

        operation.cancelIfPending()
        selection.complete(Unit)
        runCurrent()
        assertTrue(cancelled)
        assertFalse(completed)
    }

    @Test
    fun `native rejection during completion does not record history`() = runTest {
        val nativeResponse = CompletableDeferred<Unit>()
        var recorded = false
        var rejected = false
        val operation = WindowsPasskeyOperation(1, backgroundScope) { pending ->
            try {
                pending.complete {
                    nativeResponse.await()
                    recorded = true
                }
            } catch (_: IllegalStateException) {
                rejected = true
            }
        }
        operation.start()
        runCurrent()

        operation.cancelIfPending()
        nativeResponse.completeExceptionally(IllegalStateException("Native request expired"))
        runCurrent()
        assertTrue(rejected)
        assertFalse(recorded)
    }

    @Test
    fun `owner cancellation still stops history while completing`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val databaseAvailable = CompletableDeferred<Unit>()
        var recorded = false
        var cancelled = false
        val operation = WindowsPasskeyOperation(1, owner) { pending ->
            try {
                pending.complete {
                    databaseAvailable.await()
                    recorded = true
                }
            } finally {
                cancelled = true
            }
        }
        operation.start()
        runCurrent()

        owner.cancel()
        databaseAvailable.complete(Unit)
        runCurrent()
        assertTrue(cancelled)
        assertFalse(recorded)
    }
}
