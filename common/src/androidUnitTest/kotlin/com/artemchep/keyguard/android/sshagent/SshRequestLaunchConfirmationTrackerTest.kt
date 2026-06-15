package com.artemchep.keyguard.android.sshagent

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshRequestLaunchConfirmationTrackerTest {
    @Test
    fun `acknowledged newer launch satisfies older wait immediately`() = runTest {
        val tracker = SshRequestLaunchConfirmationTracker()

        tracker.acknowledge(2L)

        assertTrue(
            tracker.awaitLaunch(
                launchId = 1L,
                timeoutMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `older waiter completes after newer acknowledgement arrives`() = runTest {
        val tracker = SshRequestLaunchConfirmationTracker()

        val result = async {
            tracker.awaitLaunch(
                launchId = 1L,
                timeoutMillis = 1_000L,
            )
        }

        runCurrent()
        tracker.acknowledge(2L)
        runCurrent()

        assertTrue(result.await())
    }

    @Test
    fun `concurrent older and newer waiters both complete after newer acknowledgement`() = runTest {
        val tracker = SshRequestLaunchConfirmationTracker()

        val olderWaiter = async {
            tracker.awaitLaunch(
                launchId = 1L,
                timeoutMillis = 1_000L,
            )
        }
        val newerWaiter = async {
            tracker.awaitLaunch(
                launchId = 2L,
                timeoutMillis = 1_000L,
            )
        }

        runCurrent()
        tracker.acknowledge(2L)
        runCurrent()

        assertTrue(olderWaiter.await())
        assertTrue(newerWaiter.await())
    }

    @Test
    fun `await launch times out when no qualifying acknowledgement arrives`() = runTest {
        val tracker = SshRequestLaunchConfirmationTracker()

        val result = async {
            tracker.awaitLaunch(
                launchId = 1L,
                timeoutMillis = 1_000L,
            )
        }

        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertFalse(result.await())
    }
}
