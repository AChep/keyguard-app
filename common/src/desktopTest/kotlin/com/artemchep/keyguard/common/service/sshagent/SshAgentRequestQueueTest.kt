package com.artemchep.keyguard.common.service.sshagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SshAgentRequestQueueTest {
    @Test
    fun `single request resolves and clears active state`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(notificationTag = "session-1")

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        assertSame(request, queue.state.value?.request)

        request.deferred.complete(true)

        assertTrue(result.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `multiple requests are served in fifo order`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        assertSame(first, queue.state.value?.request)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        assertSame(second, queue.state.value?.request)

        second.deferred.complete(false)
        assertFalse(secondResult.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `dismissCurrentRequest denies the active request`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(notificationTag = "session-1")

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        queue.dismissCurrentRequest()

        assertFalse(result.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `request timeout denies the active request`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(
            notificationTag = "session-1",
            timeout = 5.seconds,
        )

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        advanceTimeBy(5_000L)
        advanceUntilIdle()

        assertFalse(result.await())
        assertNull(queue.state.value)
    }

    @Test
    fun `queued request activation timestamp starts when it becomes active`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
            timeout = 5.seconds,
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
            timeout = 5.seconds,
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val firstActivatedAtMonotonicMillis = assertNotNull(queue.state.value)
            .activatedAtMonotonicMillis

        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        Thread.sleep(80L)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        val secondState = assertNotNull(queue.state.value)
        assertSame(second, secondState.request)
        assertTrue(
            secondState.activatedAtMonotonicMillis >= firstActivatedAtMonotonicMillis + 50L,
        )

        second.deferred.complete(false)
        assertFalse(secondResult.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `completed queued request is skipped during activation`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val observedRequests = mutableListOf<SshAgentRequest?>()
        val observeJob = backgroundScope.launch {
            queue.state.collect { state ->
                observedRequests.add(state?.request)
            }
        }
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
        )
        val third = createApprovalRequest(
            keyName = "third",
            notificationTag = "session-3",
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()
        val thirdResult = async {
            queue.enqueueAndAwait(third)
        }
        runCurrent()

        // Register this after the queue's own first-request completion
        // handler so first cleanup can run before second cleanup. The queue
        // must still not publish the already-completed second request.
        first.deferred.invokeOnCompletion {
            second.deferred.complete(false)
        }
        first.deferred.complete(true)

        assertTrue(firstResult.await())
        runCurrent()

        assertTrue(second.deferred.isCompleted)
        assertFalse(observedRequests.contains(second))
        assertSame(third, queue.state.value?.request)
        assertFalse(secondResult.await())

        third.deferred.complete(false)
        assertFalse(thirdResult.await())
        advanceUntilIdle()
        observeJob.cancel()
        assertNull(queue.state.value)
    }

    @Test
    fun `queued request expires on its own deadline while waiting`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
            timeout = 60.seconds,
        )
        // The deadline is intrinsic to the request and is enforced from the
        // moment it is enqueued, not only once it becomes the active prompt.
        // A request whose deadline has already elapsed is therefore denied
        // while still queued behind another, without affecting that request.
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
            timeout = Duration.ZERO,
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        assertSame(first, queue.state.value?.request)

        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        assertFalse(secondResult.await())
        assertSame(first, queue.state.value?.request)
        assertFalse(first.deferred.isCompleted)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `dismissRequest denies the matching active request`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(notificationTag = "session-1")

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        queue.dismissRequest("session-1")
        runCurrent()

        assertFalse(result.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `dismissRequest removes a queued request without affecting other requests`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        queue.dismissRequest("session-2")
        runCurrent()

        assertTrue(second.deferred.isCompleted)
        assertSame(first, queue.state.value?.request)
        assertFalse(secondResult.await())

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        val timeBeforeIdle = currentTime
        advanceUntilIdle()

        assertEquals(timeBeforeIdle, currentTime)
        assertNull(queue.state.value)
    }

    @Test
    fun `dismissRequest ignores a non-matching tag`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        queue.dismissRequest("session-3")
        runCurrent()

        assertFalse(first.deferred.isCompleted)
        assertFalse(second.deferred.isCompleted)
        assertSame(first, queue.state.value?.request)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        assertSame(second, queue.state.value?.request)
        second.deferred.complete(false)
        assertFalse(secondResult.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    private fun createApprovalRequest(
        keyName: String = "key",
        notificationTag: String,
        timeout: Duration = 60.seconds,
    ) = SshAgentApprovalRequest(
        keyName = keyName,
        keyFingerprint = "SHA256:test",
        caller = null,
        notificationTag = notificationTag,
        expiresAt = Clock.System.now() + timeout,
        deferred = CompletableDeferred(),
    )
}
