package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.cli.agent.manageRequest
import com.artemchep.keyguard.common.service.cli.agent.manageResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun listVariables() = manageRequest(
        tool = "list_variables",
        arguments = mapOf("environment_id" to "environment"),
    )
}
