package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnvironmentMountStatusTest {
    @Test
    fun `broker mount statuses are observable by the UI and retain their MCP values`() = runTest {
        val mount = EnvironmentMount("mount", "environment", "/tmp/environment-test.env")
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = listOf(mount))
        fixture.ready()
        val handler = fixture.broker()
        assertEquals(EnvironmentMountStatus.Pending, fixture.mountStatus(mount.id))
        for (status in EnvironmentMountStatus.entries) {
            val reported = CliAgentMessages.MountStatus.valueOf(status.key.uppercase())
            val response = handler.handle(statusReport(mount.id, reported))
            assertIs<CliAgentMessages.Response.Kind.MountStatus>(response)
            assertEquals(status, fixture.mounts.state.value.statuses[mount.id])
            assertEquals(status, fixture.mountStatus(mount.id))
        }
        // A report without a status is malformed.
        val response = handler.handle(statusReport(mount.id, CliAgentMessages.MountStatus.UNSPECIFIED))
        assertEquals("invalid_request", response.error())
    }

    @Test
    fun `restarting the runtime clears stale ready mount statuses`() = runTest {
        val mount = EnvironmentMount("mount", "environment", "/tmp/environment-test.env")
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = listOf(mount))
        fixture.ready()
        fixture.mounts.setStatus(mount.id, EnvironmentMountStatus.Ready)
        fixture.mounts.onAgentStatus(ready = true)
        assertEquals(EnvironmentMountStatus.Ready, fixture.mounts.state.value.statuses[mount.id])

        fixture.mounts.onAgentStatus(ready = false)
        assertEquals(EnvironmentMountStatus.Pending, fixture.mountStatus(mount.id))
        fixture.mounts.onAgentStatus(ready = false)
        fixture.mounts.onAgentStatus(ready = true)
        assertEquals(EnvironmentMountStatus.Pending, fixture.mountStatus(mount.id))
        fixture.mounts.setStatus(mount.id, EnvironmentMountStatus.Error)
        assertEquals(EnvironmentMountStatus.Error, fixture.mounts.state.value.statuses[mount.id])
    }

    @Test
    fun `disabling a mount discards its status and ignores late broker reports`() = runTest {
        val mount = EnvironmentMount("mount", "environment", "/tmp/environment-test.env")
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = listOf(mount))
        fixture.ready()
        fixture.mounts.setStatus(mount.id, EnvironmentMountStatus.Ready)
        fixture.mounts.disable(mount.id)
        assertTrue(fixture.mounts.state.value.statuses.isEmpty())

        fixture.mounts.setStatus(mount.id, EnvironmentMountStatus.Error)
        fixture.mounts.setStatus("unknown", EnvironmentMountStatus.Ready)
        assertTrue(fixture.mounts.state.value.statuses.isEmpty())
    }

    @Test
    fun `the mount watch wakes only when the served mounts change`() = runTest {
        val mount = EnvironmentMount("mount", "environment", "/tmp/environment-test.env")
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = listOf(mount))
        fixture.ready()
        runCurrent()
        val revision = fixture.mounts.revision
        fixture.ciphers.value += createSecret("unrelated")
        runCurrent()
        assertEquals(revision, fixture.mounts.revision)

        fixture.ciphers.value = fixture.ciphers.value.filter { it.id != "environment" }
        runCurrent()
        assertNotEquals(revision, fixture.mounts.revision)
        assertTrue(fixture.mounts.served(fixture.repository.state.value).isEmpty())
    }

    private fun statusReport(id: String, status: CliAgentMessages.MountStatus) =
        CliAgentMessages.Request.Kind.MountStatus(CliAgentMessages.MountStatusRequest(id, status))
}
