package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalPrompt
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GpgAgentApprovalQueueTest {
    @Test
    fun simultaneousRequestsHaveIndependentDecisionsAndOperationContext() = runTest {
        val queue = GpgAgentApprovalQueue(StandardTestDispatcher(testScheduler), nowEpochMs = { 1_000L })
        val sign = async { queue.await(prompt(GpgAgentOperation.SIGN)) }
        val decrypt = async { queue.await(prompt(GpgAgentOperation.DECRYPT)) }
        runCurrent()
        val requests = queue.requests.value
        assertEquals(2, requests.map { it.id }.distinct().size)
        assertEquals(GpgAgentOperationSnapshot.SIGN, requests[0].operation)
        assertEquals(GpgAgentOperationSnapshot.DECRYPT, requests[1].operation)
        assertEquals("Terminal", requests[0].callerName)
        assertEquals("/usr/local/bin/gpg", requests[0].callerPath)
        assertEquals(61_000L, requests[0].expiresAtEpochMs)
        queue.resolve(requests[0].id, true)
        queue.resolve(requests[1].id, false)
        assertTrue(sign.await())
        assertFalse(decrypt.await())
        assertTrue(queue.requests.value.isEmpty())
    }

    @Test
    fun expiryDeniesAndRemovesThePrompt() = runTest {
        val queue = GpgAgentApprovalQueue(StandardTestDispatcher(testScheduler), timeoutMs = 1_000L)
        val result = async { queue.await(prompt(GpgAgentOperation.SIGN)) }
        runCurrent()
        assertEquals(1, queue.requests.value.size)
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(result.await())
        assertTrue(queue.requests.value.isEmpty())
    }

    @Test
    fun cancellationAndStoppingDrainEveryPrompt() = runTest {
        val queue = GpgAgentApprovalQueue(StandardTestDispatcher(testScheduler))
        val cancelled = async { queue.await(prompt(GpgAgentOperation.SIGN)) }
        val stopped = async { queue.await(prompt(GpgAgentOperation.DECRYPT)) }
        runCurrent()
        val cancelledId = queue.requests.value.first().id
        cancelled.cancelAndJoin()
        assertEquals(1, queue.requests.value.size)
        queue.resolve(cancelledId, true)
        queue.denyAll()
        assertFalse(stopped.await())
        assertTrue(queue.requests.value.isEmpty())

        val next = async { queue.await(prompt(GpgAgentOperation.SIGN)) }
        runCurrent()
        queue.resolve(cancelledId, true)
        assertFalse(next.isCompleted)
        queue.resolve(queue.requests.value.single().id, true)
        assertTrue(next.await())
    }

    @Test
    fun unknownCallerRemainsUnknownAndProcessNameIsOnlyDisplayFallback() {
        val knownProcess = prompt(GpgAgentOperation.SIGN).copy(
            caller = GpgAgentMessages.CallerIdentity(processName = "gpg"),
        ).toSnapshot("id", 1_000L, 2_000L)
        assertEquals("gpg", knownProcess.callerName)
        assertEquals("", knownProcess.callerPath)
        val unknown = prompt(GpgAgentOperation.SIGN).copy(caller = null).toSnapshot("id", 1_000L, 2_000L)
        assertEquals("", unknown.callerName)
        assertEquals("", unknown.callerPath)
    }

    private fun prompt(operation: GpgAgentOperation) = GpgAgentApprovalPrompt(
        operation = operation,
        caller = GpgAgentMessages.CallerIdentity(
            appName = "Terminal",
            processName = "gpg",
            executablePath = "/usr/local/bin/gpg",
        ),
        keyName = "Test key",
        keyFingerprint = "fingerprint",
        keygrip = "keygrip",
        accountId = "account",
        cipherId = "cipher",
    )
}
