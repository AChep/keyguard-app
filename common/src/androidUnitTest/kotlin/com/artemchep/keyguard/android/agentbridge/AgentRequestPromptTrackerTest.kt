package com.artemchep.keyguard.android.agentbridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRequestPromptTrackerTest {
    @Test
    fun `closed waits for the last open prompt`() = runTest {
        val tracker = AgentRequestPromptTracker()
        tracker.open("a")
        tracker.open("b")

        val closed = async { tracker.awaitClosed() }
        runCurrent()
        tracker.close("a")
        runCurrent()
        assertFalse(closed.isCompleted)

        tracker.close("b")
        runCurrent()
        assertTrue(closed.isCompleted)
    }

    @Test
    fun `a prompt recreated with its token closes once`() = runTest {
        val tracker = AgentRequestPromptTracker()
        tracker.open("a")
        // A configuration change opens the same prompt again.
        tracker.open("a")
        tracker.close("a")

        val closed = async { tracker.awaitClosed() }
        runCurrent()
        assertTrue(closed.isCompleted)
    }
}
