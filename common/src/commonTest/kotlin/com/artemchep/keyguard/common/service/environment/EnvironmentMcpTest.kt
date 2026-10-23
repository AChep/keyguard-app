package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.ManageResponse
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.manageRequest
import com.artemchep.keyguard.common.service.cli.agent.manageResult
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class EnvironmentMcpTest {
    @Test
    fun `unsupported local files reject Windows paths before approval`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = false)
        fixture.autoApprove(backgroundScope)
        val response = fixture.broker().handle(manageRequest(
            tool = "create_local_env_file",
            arguments = mapOf("environment_id" to "environment", "path" to "C:\\Temp\\qa.env"),
        ))
        assertEquals("unsupported_platform", response.error())
        assertEquals(listOf(EnvironmentCapability.Authenticate.name), fixture.approvedCapabilities.value)
        assertTrue(fixture.mounts.state.value.mounts.isEmpty())
    }

    @Test
    fun `unsupported local files reject Unix paths before approval`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = false)
        fixture.autoApprove(backgroundScope)
        val response = fixture.broker().handle(manageRequest(
            tool = "create_local_env_file",
            arguments = mapOf("environment_id" to "environment", "path" to "/tmp/qa.env"),
        ))
        assertEquals("unsupported_platform", response.error())
        assertEquals(listOf(EnvironmentCapability.Authenticate.name), fixture.approvedCapabilities.value)
        assertTrue(fixture.mounts.state.value.mounts.isEmpty())
    }

    @Test
    fun `MCP variable listing exposes metadata but no stored values or notes`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.autoApprove(backgroundScope)
        val response = fixture.broker().handle(manage("list_variables"))
        val variables = assertIs<ManageResponse.Result.Variables>(response.manageResult()).value.variables
        val token = CliAgentMessages.Variable(
            name = "TOKEN",
            source = CliAgentMessages.VariableSource.VALUE,
            isSet = true,
            supported = true,
        )
        assertEquals(token, variables.single())
        assertFalse(SECRET in response.toString())
        assertFalse("secret description" in response.toString())
    }

    @Test
    fun `MCP cannot request raw values and disabled integration rejects calls`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.broker()
        val unknown = handler.handle(manage("get_environment_values"))
        assertEquals("unknown_tool", unknown.error())
        fixture.mcp.setEnabled(false)
        val disabled = handler.handle(manage("list_variables"))
        assertEquals("integration_disabled", disabled.error())
        assertFalse(SECRET in unknown.toString() + disabled.toString())
    }

    @Test
    fun `disabled MCP cannot execute an approved mutation queued behind another write`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fixture.beforeSave = { entered.complete(Unit); resume.await() }
        val first = async { fixture.repository.save("environment", "account", "first", EnvironmentDefinition()) }
        entered.await()
        fixture.autoApprove(backgroundScope)
        val queued = async { fixture.broker().handle(manage("rename_environment", "second")) }
        fixture.approvedCapabilities.first { EnvironmentCapability.RenameEnvironment.name in it }
        runCurrent()
        fixture.mcp.setEnabled(false)
        resume.complete(Unit)
        first.await()
        assertEquals("integration_disabled", queued.await().error())
        assertEquals(listOf("first"), fixture.saved.map { it.title })
    }

    @Test
    fun `only an invalid request is answered as one`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.broker()
        val large = CliAgentMessages.VariableUpdate(
            name = "LARGE",
            value = ByteArray(EnvironmentFormat.MAX_PAYLOAD_BYTES) { 'x'.code.toByte() },
        )
        val tooLarge = handler.handle(
            manageRequest(
                tool = "append_variables",
                arguments = mapOf("environment_id" to "environment"),
                variables = listOf(large),
            ),
        )
        assertEquals("environment_too_large", tooLarge.error())
        assertTrue(fixture.saved.isEmpty())
        // A failing check inside the vault is not the request's.
        fixture.beforeSave = { throw IllegalArgumentException(SECRET) }
        val failed = handler.handle(manage("rename_environment", "renamed"))
        assertEquals("operation_failed", failed.error())
        assertFalse(SECRET in failed.toString())
    }

    @Test
    fun `MCP names accounts and environments by their remote IDs`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(name = "Production").withRemoteId("remote-1"))
        fixture.ready()
        fixture.repository.state.first { it.environments.singleOrNull()?.remoteId == "remote-1" }
        fixture.autoApprove(backgroundScope)
        val handler = fixture.broker()
        suspend fun handle(tool: String, arguments: Map<String, String> = emptyMap()) =
            handler.handle(manageRequest(tool = tool, arguments = arguments))

        val accounts = assertIs<ManageResponse.Result.Accounts>(handle("authenticate").manageResult())
        assertEquals("profile-account", accounts.value.accounts.single().id)
        val environments = assertIs<ManageResponse.Result.Environments>(
            handle("list_environments", mapOf("account_id" to "profile-account")).manageResult(),
        )
        val environment = environments.value.environments.single()
        assertEquals("remote-1", environment.id)
        assertEquals("profile-account", environment.accountId)
        // Both IDs select the environment; the local one is a fallback.
        for (id in listOf("remote-1", "environment")) {
            val variables = assertIs<ManageResponse.Result.Variables>(
                handle("list_variables", mapOf("environment_id" to id)).manageResult(),
            )
            assertEquals("TOKEN", variables.value.variables.single().name)
        }
        // Names never select an environment.
        val byName = handle("list_variables", mapOf("environment_id" to "Production"))
        assertEquals("not_found", byName.error())
    }
}
