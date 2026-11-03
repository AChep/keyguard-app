package com.artemchep.keyguard.android.agentbridge

import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRequestDismissPromptTest {
    private val broker = AgentApprovalBroker<String>(idOf = { it })

    private fun shown(id: String) = AgentRequestUiState(
        request = id,
        id = id,
        expiresAt = Instant.DISTANT_FUTURE,
        onDecision = { approved, _ -> broker.respond(id, approved) },
    )

    @Test
    fun `dismissing denies only the shown request and keeps the prompt for the next one`() = runTest {
        val first = async { broker.request { "a" } }
        val second = async { broker.request { "b" } }
        runCurrent()

        assertFalse(listOf(broker).dismissPrompt(shown("a")))
        assertEquals(AgentApprovalBroker.Result.Denied, first.await())
        assertEquals(listOf("b"), broker.pending.value)

        // Nothing is shown during the pause before the next request.
        assertFalse(listOf(broker).dismissPrompt(null))
        assertEquals(listOf("b"), broker.pending.value)

        assertTrue(listOf(broker).dismissPrompt(shown("b")))
        assertEquals(AgentApprovalBroker.Result.Denied, second.await())
    }

    @Test
    fun `dismissing without waiting requests closes the prompt`() {
        assertTrue(listOf(broker).dismissPrompt(null))
    }

    @Test
    fun `the prompt stays open while requests of another agent wait`() = runTest {
        val other = AgentApprovalBroker<String>(idOf = { it })
        val first = async { broker.request { "a" } }
        val second = async { other.request { "b" } }
        runCurrent()

        assertFalse(listOf(broker, other).dismissPrompt(shown("a")))
        assertEquals(AgentApprovalBroker.Result.Denied, first.await())

        other.respond("b", false)
        assertEquals(AgentApprovalBroker.Result.Denied, second.await())
        assertTrue(listOf(broker, other).dismissPrompt(null))
    }
}
