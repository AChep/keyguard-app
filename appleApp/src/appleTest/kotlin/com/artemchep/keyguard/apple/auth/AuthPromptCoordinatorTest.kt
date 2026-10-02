package com.artemchep.keyguard.apple.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthPromptCoordinatorTest {
    @Test
    fun `another owner cannot replace or queue behind an active prompt`() = runTest {
        val coordinator = AuthPromptCoordinator()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            coordinator.run {
                events += "first"
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        assertTrue(coordinator.isActive.value)
        coordinator.run(onRejected = { events += "rejected" }) { events += "second" }
        assertTrue(coordinator.isActive.value)
        finish.complete(Unit)
        first.await()
        assertFalse(coordinator.isActive.value)
        coordinator.run { events += "retry" }
        assertEquals(listOf("first", "rejected", "retry"), events)
    }

    @Test
    fun `closing the prompt owner releases the host for another form`() = runTest {
        val coordinator = AuthPromptCoordinator()
        val entered = CompletableDeferred<Unit>()
        val pending = async {
            coordinator.run {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        pending.cancel()
        pending.join()
        assertFalse(coordinator.isActive.value)
        var ran = false
        coordinator.run { ran = true }
        assertTrue(ran)
    }
}
