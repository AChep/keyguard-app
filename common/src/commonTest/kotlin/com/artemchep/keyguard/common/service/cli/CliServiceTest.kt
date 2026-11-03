package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.runRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliServiceTest {
    @Test
    fun `setup uses the configured runtime socket and clears stale overrides`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        val socketPath = "/tmp/keyguard-501/cli.sock"
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED, socketPath = socketPath)
        assertEquals(socketPath, fixture.cli.state.value.socketPath)

        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        assertEquals(null, fixture.cli.state.value.socketPath)
    }

    @Test
    fun `the agent is needed only by an enabled and supported CLI`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        assertFalse(fixture.cli.state.value.needsAgent)
        fixture.cli.setEnabled(true)
        assertTrue(fixture.cli.state.value.enabled)
        assertFalse(fixture.cli.state.value.needsAgent)
        // Every command of this version needs an unsandboxed CLI.
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = emptySet())
        assertFalse(fixture.cli.state.value.supported)
        assertFalse(fixture.cli.state.value.needsAgent)
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        assertTrue(fixture.cli.state.value.needsAgent)
        fixture.cli.setEnabled(false)
        assertFalse(fixture.cli.state.value.needsAgent)
    }

    @Test
    fun `locking denies a pending prompt`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        fixture.cli.setEnabled(true)
        val pending = async {
            fixture.agent().handle(CliAgentMessages.Request.Kind.Cli(runRequest(environment = "environment")))
        }
        fixture.cli.approvals.first { it.isNotEmpty() }
        fixture.lock()
        assertEquals("user_denied", pending.await().error())
        assertTrue(fixture.cli.approvals.value.isEmpty())
    }

    @Test
    fun `an approval revoked while the user is asked names the cause`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        fixture.cli.setEnabled(true)
        suspend fun approveAfter(change: suspend () -> Unit): String {
            val pending = async {
                fixture.agent().handle(CliAgentMessages.Request.Kind.Cli(runRequest(environment = "environment")))
            }
            val request = fixture.cli.approvals.first { it.isNotEmpty() }.single()
            change()
            fixture.cli.respondToApproval(request.id, true)
            return pending.await().error()
        }
        assertEquals("integration_disabled", approveAfter { fixture.cli.setEnabled(false) })
        fixture.cli.setEnabled(true)
        val revoked = approveAfter {
            fixture.cli.setEnabled(false)
            fixture.cli.setEnabled(true)
        }
        assertEquals("approval_revoked", revoked)
    }
}
