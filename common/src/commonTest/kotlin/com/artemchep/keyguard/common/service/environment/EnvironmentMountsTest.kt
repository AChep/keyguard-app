package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.ManageResponse
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.manageRequest
import com.artemchep.keyguard.common.service.cli.agent.manageResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnvironmentMountsTest {
    @Test
    fun `a path is stored in its normalized form`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = true)
        val mount = fixture.mounts.create("environment", "/tmp//./environment-test.env")
        assertEquals("/tmp/environment-test.env", mount.path)
        assertEquals(listOf(mount), fixture.mounts.state.value.mounts)
    }

    @Test
    fun `another spelling of a path conflicts with another environment`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value += fixture.secret.copy(id = "other")
        fixture.ready()
        fixture.mounts.configure(supported = true)
        fixture.mounts.create("environment", "/tmp/environment-test.env")
        assertEquals("path_conflict", assertFailsWith<EnvironmentServiceException> {
            fixture.mounts.create("other", "/tmp/./environment-test.env")
        }.code)
        assertEquals(1, fixture.mounts.state.value.mounts.size)
    }

    @Test
    fun `another spelling of a path returns the mount of the same environment`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = true)
        val mount = fixture.mounts.create("environment", "/tmp/environment-test.env")
        assertEquals(mount, fixture.mounts.create("environment", "/tmp//environment-test.env"))
        assertEquals(listOf(mount), fixture.mounts.state.value.mounts)
    }

    @Test
    fun `a path the agent refuses is invalid`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = true)
        for (path in listOf("environment-test.env", "/tmp/", "/tmp/../environment-test.env")) {
            assertEquals("invalid_path", assertFailsWith<EnvironmentServiceException> {
                fixture.mounts.create("environment", path)
            }.code)
        }
        assertTrue(fixture.mounts.state.value.mounts.isEmpty())
    }

    @Test
    fun `MCP approves the normalized path`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = true)
        fixture.autoApprove(backgroundScope)
        val result = fixture.broker().handle(createLocalEnvFile("/tmp//./environment-test.env")).manageResult()
        assertEquals("/tmp/environment-test.env", assertIs<ManageResponse.Result.LocalEnvFile>(result).value.path)
        assertEquals(setOf(null, "/tmp/environment-test.env"), fixture.approvedPaths.toSet())
    }

    @Test
    fun `MCP refuses an invalid path before asking to approve it`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = true)
        fixture.autoApprove(backgroundScope)
        val response = fixture.broker().handle(createLocalEnvFile("/tmp/../environment-test.env"))
        assertEquals("invalid_path", response.error())
        assertFalse(EnvironmentCapability.CreateLocalEnvFile.name in fixture.approvedCapabilities.value)
    }

    private fun createLocalEnvFile(path: String) = manageRequest(
        tool = "create_local_env_file",
        arguments = mapOf("environment_id" to "environment", "path" to path),
    )
}
