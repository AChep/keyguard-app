package com.artemchep.keyguard.android.agentbridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentBridgeRunWhileEnabledTest {
    @Test
    fun `bridge runs to its end while the agent stays enabled`() = runTest {
        var finished = false

        runWhileAgentEnabled(MutableStateFlow(true)) {
            finished = true
        }

        assertTrue(finished)
    }

    @Test
    fun `disabling the agent ends a running bridge`() = runTest {
        val enabled = MutableStateFlow(true)
        var cancelled = false
        val bridge = launch {
            runWhileAgentEnabled(enabled) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        runCurrent()
        assertFalse(bridge.isCompleted)

        enabled.value = false
        runCurrent()

        assertTrue(cancelled)
        assertTrue(bridge.isCompleted)
    }

    @Test
    fun `a disabled agent never starts a bridge`() = runTest {
        var started = false

        runWhileAgentEnabled(MutableStateFlow(false)) {
            started = true
        }

        assertFalse(started)
    }
}
