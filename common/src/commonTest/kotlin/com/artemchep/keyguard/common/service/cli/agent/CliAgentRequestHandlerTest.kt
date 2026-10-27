package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CliAgentRequestHandlerTest {
    @Test
    fun `requests are routed to the feature that serves them`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val handler = fixture.agent()
        // This build does not support the CLI, which only its dispatcher knows.
        val cli = handler.handle(CliAgentMessages.Request.Kind.Cli(runRequest()))
        assertEquals("unsupported_platform", cli.error())
        val mounts = handler.handle(CliAgentMessages.Request.Kind.Mounts(CliAgentMessages.Empty))
        assertIs<CliAgentMessages.Response.Kind.Mounts>(mounts)
    }

    @Test
    fun `a connection authenticates only once`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val request = CliAgentMessages.Request.Kind.Authenticate(AgentIpcMessages.AuthenticateRequest())
        assertEquals("invalid_request", fixture.agent().handle(request).error())
    }
}
