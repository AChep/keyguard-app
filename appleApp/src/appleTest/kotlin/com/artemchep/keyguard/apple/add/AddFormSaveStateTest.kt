package com.artemchep.keyguard.apple.add

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddFormSaveStateTest {
    @Test
    fun savesOnceAndKeepsSuccessClosedUntilDismissal() = runTest {
        val state = AddFormSaveState()
        val result = CompletableDeferred<String>()
        var writes = 0
        val first = async { state.run { writes++; result.await() } }
        runCurrent()
        assertTrue(state.running.value)
        assertFailsWith<CancellationException> { state.run { writes++ } }
        result.complete("saved")
        assertEquals("saved", first.await())
        assertFailsWith<CancellationException> { state.run { writes++ } }
        assertEquals(1, writes)
    }

    @Test
    fun failedSaveAllowsRetry() = runTest {
        val state = AddFormSaveState()
        assertFailsWith<IllegalStateException> { state.run { error("storage failed") } }
        assertFalse(state.running.value)
        assertEquals("retried", state.run { "retried" })
    }

    @Test
    fun cancelledSaveAllowsRetry() = runTest {
        val state = AddFormSaveState()
        assertFailsWith<CancellationException> { state.run { throw CancellationException() } }
        assertFalse(state.running.value)
        assertEquals("retried", state.run { "retried" })
    }
}
