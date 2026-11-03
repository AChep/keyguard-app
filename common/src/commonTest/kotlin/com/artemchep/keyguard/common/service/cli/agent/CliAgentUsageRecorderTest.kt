package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.cli.UNSANDBOXED
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import com.artemchep.keyguard.common.service.environment.EnvironmentMount
import com.artemchep.keyguard.common.service.environment.manage
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CliAgentUsageRecorderTest {
    @Test
    fun `run records the environment, the command and its directory`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        fixture.autoApprove(backgroundScope)

        fixture.agent().handle(run())

        val usage = fixture.recordedUsage.single()
        assertEquals(CliAgentUsageHistoryClient.CLI, usage.client)
        assertEquals(CliAgentUsageHistoryRequestType.RUN, usage.request)
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, usage.response)
        assertEquals("environment", usage.cipherId)
        assertEquals("npm start 'hello world'", usage.command)
        assertEquals("/project", usage.path)
        assertNull(usage.errorCode)
        assertTrue(assertNotNull(usage.caller).contains("keyguard"))
    }

    @Test
    fun `a denied run is recorded as denied`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val handler = fixture.agent()

        val pending = async { handler.handle(run()) }
        val request = fixture.cli.approvals.first { it.isNotEmpty() }.single()
        fixture.cli.respondToApproval(request.id, false)
        assertEquals("user_denied", pending.await().error())

        val usage = fixture.recordedUsage.single()
        assertEquals(CliAgentUsageHistoryResponseType.USER_DENIED, usage.response)
        assertEquals("environment", usage.cipherId)
    }

    @Test
    fun `a client that leaves is recorded as cancelled`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val handler = fixture.agent()

        val pending = async { handler.handle(run()) }
        fixture.cli.approvals.first { it.isNotEmpty() }
        pending.cancel()
        runCurrent()

        val usage = fixture.recordedUsage.single()
        assertEquals(CliAgentUsageHistoryResponseType.CANCELLED, usage.response)
    }

    @Test
    fun `a request while the vault is locked is queued`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        fixture.lock()
        fixture.repository.state.first { !it.unlocked }

        assertEquals("vault_locked", fixture.agent().handle(run()).error())

        assertTrue(fixture.recordedUsage.isEmpty())
        val event = fixture.queuedUsage.single()
        assertEquals(PendingUsageHistory.Protocol.CLI_AGENT, event.protocol)
        assertEquals(CliAgentUsageHistoryRequestType.RUN.name, event.requestType)
        assertEquals(CliAgentUsageHistoryResponseType.VAULT_LOCKED.name, event.responseType)
        val details = assertNotNull(event.cliAgent)
        assertEquals(CliAgentUsageHistoryClient.CLI.name, details.client)
        assertNull(details.errorCode)
        // The dispatcher rejects the request before the command reads it.
        assertNull(details.command)
    }

    @Test
    fun `the agent's own requests are not recorded`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val handler = fixture.agent()

        handler.handle(CliAgentMessages.Request.Kind.Mounts(CliAgentMessages.Empty))
        handler.handle(CliAgentMessages.Request.Kind.Authenticate(AgentIpcMessages.AuthenticateRequest()))

        assertTrue(fixture.recordedUsage.isEmpty())
        assertTrue(fixture.queuedUsage.isEmpty())
    }

    @Test
    fun `an MCP tool is recorded with the environment it reaches and the client name`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.autoApprove(backgroundScope)

        fixture.agent().handle(manage("list_variables", clientName = "Claude Code"))

        val usage = fixture.recordedUsage.single()
        assertEquals(CliAgentUsageHistoryClient.MCP, usage.client)
        assertEquals(CliAgentUsageHistoryRequestType.MCP_LIST_VARIABLES, usage.request)
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, usage.response)
        assertEquals("environment", usage.cipherId)
        assertEquals("Claude Code", usage.clientName)
    }

    @Test
    fun `an MCP environment is recorded once it is created`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.autoApprove(backgroundScope)

        fixture.agent().handle(
            manageRequest(
                tool = "create_environment",
                arguments = mapOf("account_id" to "profile-account", "name" to "Staging"),
            ),
        )

        val usage = fixture.recordedUsage.single()
        assertEquals(CliAgentUsageHistoryRequestType.MCP_CREATE_ENVIRONMENT, usage.request)
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, usage.response)
        assertEquals("environment", usage.cipherId)
    }

    @Test
    fun `an unknown MCP tool is recorded without its name`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.autoApprove(backgroundScope)

        assertEquals("unknown_tool", fixture.agent().handle(manage("drop_everything")).error())

        val usage = fixture.recordedUsage.single()
        assertEquals(CliAgentUsageHistoryRequestType.UNKNOWN, usage.request)
        assertEquals(CliAgentUsageHistoryResponseType.FAILURE, usage.response)
        assertEquals("unknown_tool", usage.errorCode)
    }

    @Test
    fun `a local env file read is recorded with its path but no caller`() = runTest {
        val mount = EnvironmentMount("mount", "environment", "/tmp/environment.env")
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = listOf(mount))
        fixture.ready()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.agent()
        val mounts = handler.handle(CliAgentMessages.Request.Kind.Mounts(CliAgentMessages.Empty))
        val generation = assertIs<CliAgentMessages.Response.Kind.Mounts>(mounts).value.mounts.single().generation

        handler.handle(
            CliAgentMessages.Request.Kind.ResolveMount(
                CliAgentMessages.ResolveMountRequest(mountId = "mount", generation = generation),
            ),
        )

        val usage = fixture.recordedUsage.single()
        assertEquals(CliAgentUsageHistoryClient.ENV, usage.client)
        assertEquals(CliAgentUsageHistoryRequestType.READ_LOCAL_ENV_FILE, usage.request)
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, usage.response)
        assertEquals("environment", usage.cipherId)
        assertEquals("/tmp/environment.env", usage.path)
        assertNull(usage.caller)
    }

    private suspend fun EnvironmentFixture.readyCli() {
        ready()
        cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        cli.setEnabled(true)
        cli.state.first { it.enabled }
    }

    private fun run() = CliAgentMessages.Request.Kind.Cli(
        runRequest(
            environment = "environment",
            command = listOf("npm", "start", "hello world"),
            workingDirectory = "/project",
            caller = CallerIdentity(pid = 42, processName = "keyguard"),
        ),
    )
}
