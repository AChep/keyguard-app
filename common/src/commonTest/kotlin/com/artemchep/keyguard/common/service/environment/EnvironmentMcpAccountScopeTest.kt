package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.ManageResponse
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.manageRequest
import com.artemchep.keyguard.common.service.cli.agent.manageResult
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentMcpAccountScopeTest {
    @Test
    fun `every environment selector rejects ambiguity and mismatched accounts`() = runTest {
        val fixture = fixture(backgroundScope)
        val handler = fixture.broker()
        for (tool in ENVIRONMENT_TOOLS) {
            suspend fun error(environment: String, account: String? = null): String = handler.handle(
                manageRequest(tool, buildMap {
                    put("environment_id", environment)
                    if (tool == "rename_environment") put("name", "Renamed")
                    if (tool == "create_local_env_file") put("path", "/tmp/scoped.env")
                    if (account != null) put("account_id", account)
                }, variables = if (tool == "append_variables") {
                    listOf(CliAgentMessages.VariableUpdate("ADDED", null))
                } else emptyList()),
            ).error()
            assertEquals("ambiguous_environment", error("shared"), tool)
            assertEquals("not_found", error("environment-2", "account"), tool)
            assertEquals("account_not_found", error("shared", "missing"), tool)
        }
        assertTrue(fixture.saved.isEmpty())
        assertTrue(fixture.approvedEnvironmentIds.all { it == null })
        fixture.profiles.value = listOf(
            createProfile("account", name = "Work"),
            createProfile("account-2", name = "Work"),
        )
        fixture.repository.state.first { state -> state.accounts.all { it.name == "Work" } }
        for (tool in ENVIRONMENT_TOOLS) {
            assertEquals("ambiguous_account", handler.handle(manageRequest(
                tool, mapOf("environment_id" to "shared", "account_id" to "Work"),
            )).error(), tool)
        }
    }

    @Test
    fun `variable metadata and approvals follow the selected account`() = runTest {
        val fixture = fixture(backgroundScope)
        val handler = fixture.broker()
        for (account in listOf("profile-account-2", "account-2", "User account-2")) {
            val result = handler.handle(manageRequest(
                "list_variables", mapOf("environment_id" to "shared", "account_id" to account),
            )).manageResult()
            assertEquals("SECOND", assertIs<ManageResponse.Result.Variables>(result).value.variables.single().name)
        }
        val first = handler.handle(manageRequest(
            "list_variables", mapOf("environment_id" to "shared", "account_id" to "account"),
        )).manageResult()
        assertEquals("TOKEN", assertIs<ManageResponse.Result.Variables>(first).value.variables.single().name)
        assertEquals(listOf(null, "environment-2", "environment"), fixture.approvedEnvironmentIds)
    }

    @Test
    fun `rename and append use the selected local environment and return its account`() = runTest {
        val fixture = fixture(backgroundScope)
        val handler = fixture.broker()
        for ((account, id) in listOf(
            "account" to "environment",
            "account-2" to "environment-2",
        )) {
            val arguments = mapOf("environment_id" to "shared", "account_id" to account)
            val rename = handler.handle(manageRequest("rename_environment", arguments + ("name" to "Renamed")))
            assertEquals(
                CliAgentMessages.EnvironmentId("shared", "profile-$account"),
                assertIs<ManageResponse.Result.Environment>(rename.manageResult()).value,
            )
            assertEquals(id, fixture.savedIds.last())
            assertEquals(account, fixture.saved.last().ownership!!.accountId)
            assertEquals("Renamed", fixture.saved.last().title)
            assertEquals(id, fixture.approvedEnvironmentIds.last())
            val append = handler.handle(manageRequest(
                "append_variables", arguments,
                variables = listOf(CliAgentMessages.VariableUpdate("ADDED", "value".encodeToByteArray())),
            ))
            assertEquals(
                CliAgentMessages.EnvironmentId("shared", "profile-$account"),
                assertIs<ManageResponse.Result.Environment>(append.manageResult()).value,
            )
            assertEquals(id, fixture.savedIds.last())
            assertEquals(account, fixture.saved.last().ownership!!.accountId)
            assertEquals(listOf(if (account == "account") "TOKEN" else "SECOND", "ADDED"),
                fixture.saved.last().environment!!.variables.map { it.name })
            assertEquals(id, fixture.approvedEnvironmentIds.last())
        }
        assertEquals(listOf<String?>("environment", "environment", "environment-2", "environment-2"), fixture.savedIds)
    }

    @Test
    fun `create returns account scope before the new environment reaches state`() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.ciphers.value = emptyList()
        fixture.repository.state.first { it.environments.isEmpty() }
        val result = fixture.broker().handle(manageRequest(
            "create_environment", mapOf("account_id" to "profile-account-2", "name" to "Created"),
        )).manageResult()
        assertEquals(CliAgentMessages.EnvironmentId("environment", "profile-account-2"),
            assertIs<ManageResponse.Result.Environment>(result).value)
        assertNull(fixture.savedIds.single())
        assertEquals("account-2", fixture.saved.single().ownership!!.accountId)
    }

    @Test
    fun `mount creation and listing preserve account scope and unavailable mounts`() = runTest {
        val fixture = fixture(
            backgroundScope,
            mounts = listOf(EnvironmentMount("orphan", "missing", "/tmp/orphan.env")),
        )
        fixture.mounts.configure(supported = true)
        val handler = fixture.broker()
        for (account in listOf("account", "account-2")) {
            val result = handler.handle(manageRequest("create_local_env_file", mapOf(
                "environment_id" to "shared", "account_id" to account, "path" to "/tmp/$account.env",
            ))).manageResult()
            val mount = assertIs<ManageResponse.Result.LocalEnvFile>(result).value
            assertEquals("shared", mount.environmentId)
            assertEquals("profile-$account", mount.accountId)
            assertEquals(if (account == "account") "environment" else "environment-2",
                fixture.mounts.state.value.mounts.single { it.id == mount.id }.environmentId)
            assertEquals(if (account == "account") "environment" else "environment-2",
                fixture.approvedEnvironmentIds.last())
        }
        suspend fun list(arguments: Map<String, String> = emptyMap()) =
            assertIs<ManageResponse.Result.LocalEnvFiles>(handler.handle(
                manageRequest("list_local_env_files", arguments),
            ).manageResult()).value.localEnvFiles
        assertEquals(3, list().size)
        assertNull(list().single { it.id == "orphan" }.accountId)
        for (account in listOf("account", "account-2")) {
            val scoped = list(mapOf("account_id" to account))
            assertEquals("profile-$account", scoped.single().accountId)
            assertEquals(scoped, list(mapOf("account_id" to account, "environment_id" to "shared")))
        }
        assertEquals("profile-account-2", list(mapOf("environment_id" to "environment-2")).single().accountId)
    }

    private suspend fun fixture(
        scope: CoroutineScope,
        mounts: List<EnvironmentMount> = emptyList(),
    ): EnvironmentFixture {
        val fixture = EnvironmentFixture(scope, configuredMounts = mounts)
        fixture.profiles.value = listOf(createProfile("account"), createProfile("account-2"))
        fixture.ciphers.value = listOf(
            fixture.secret.copy(name = "First").withRemoteId("shared"),
            fixture.secret.copy(id = "environment-2", accountId = "account-2", name = "Second",
                environment = environmentPayload(EnvironmentDefinition(listOf(
                    EnvironmentVariable("SECOND", EnvironmentValueSource.Unset),
                )))).withRemoteId("shared"),
        )
        fixture.ready()
        fixture.repository.state.first { it.environments.size == 2 && it.accounts.size == 2 }
        fixture.autoApprove(scope)
        return fixture
    }

    companion object {
        private val ENVIRONMENT_TOOLS = listOf(
            "rename_environment", "list_variables", "append_variables", "create_local_env_file", "list_local_env_files",
        )
    }
}
