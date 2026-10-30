package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_WINDOW_NEVER_ASK
import com.artemchep.keyguard.common.service.cli.agent.manageRequest
import com.artemchep.keyguard.common.service.cli.agent.manageResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The user's MCP approval window applies to the MCP approvals, like the windows of the key agents. */
class EnvironmentApprovalWindowTest {
    @Test
    fun `an MCP approval window of zero asks every time`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, mcpApprovalWindow = Duration.ZERO)
        fixture.ready()
        val handler = fixture.broker()
        repeat(2) {
            val response = async { handler.handle(listVariables()) }
            // The connection and the tool are approved again on every call.
            listOf(EnvironmentCapability.Authenticate, EnvironmentCapability.ListVariables).forEach { capability ->
                val request = fixture.access.approvals.first { it.isNotEmpty() }.single()
                assertEquals(capability, request.capability)
                fixture.access.respondToApproval(request.id, true)
            }
            response.await().manageResult()
        }
    }

    @Test
    fun `an MCP approval is remembered within the window`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, mcpApprovalWindow = Duration.INFINITE)
        fixture.ready()
        val handler = fixture.broker()
        val first = async { handler.handle(listVariables()) }
        repeat(2) {
            val request = fixture.access.approvals.first { it.isNotEmpty() }.single()
            fixture.access.respondToApproval(request.id, true)
        }
        first.await().manageResult()

        // The second call needs no prompt.
        handler.handle(listVariables()).manageResult()
    }

    @Test
    fun `an MCP approval window of never ask needs no prompt`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, mcpApprovalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        fixture.ready()
        val handler = fixture.broker()
        repeat(2) {
            handler.handle(listVariables()).manageResult()
        }
        assertTrue(fixture.access.approvals.value.isEmpty())
    }

    @Test
    fun `an MCP approval window of never ask still asks for an item that needs authentication`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, mcpApprovalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        fixture.ready()
        val handler = fixture.broker()
        val response = async { handler.handle(listVariables()) }
        val request = fixture.access.approvals.first { it.isNotEmpty() }.single()
        assertEquals(EnvironmentCapability.ListVariables, request.capability)
        assertNotNull(request.elevationChallenge)
        fixture.access.respondToApproval(request.id, false)
        response.await()
    }

    private fun listVariables() = manageRequest(
        tool = "list_variables",
        arguments = mapOf("environment_id" to "environment"),
    )
}
