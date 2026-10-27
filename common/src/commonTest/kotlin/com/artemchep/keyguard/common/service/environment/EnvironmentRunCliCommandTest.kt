package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.cli.CliOperation
import com.artemchep.keyguard.common.service.cli.UNSANDBOXED
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.cliResult
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.runRequest
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/** `keyguard run`: the private CLI command and its terminal-tab approvals. */
class EnvironmentRunCliCommandTest {
    @Test
    fun `run delivers values only after approval and only when the CLI is enabled and supported`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val handler = fixture.agent()
        val pending = async { handler.handle(run()) }
        val request = fixture.cli.approvals.first { it.isNotEmpty() }.single()
        val operation = assertIs<CliOperation.Run>(request.operation)
        assertEquals("environment", operation.environmentId)
        assertEquals("environment", operation.environmentName)
        assertEquals("User account", operation.accountName)
        assertEquals(listOf("npm", "start"), operation.command)
        assertEquals("/project", operation.workingDirectory)
        assertEquals("keyguard", request.caller.processName)
        assertFalse(request.terminalSession)
        fixture.cli.respondToApproval(request.id, true)
        val response = pending.await()
        assertEquals(SECRET, response.token())

        fixture.cli.setEnabled(false)
        assertEquals("integration_disabled", handler.handle(run()).error())
        fixture.cli.setEnabled(true)
        // A sandboxed CLI would start the command in its sandbox.
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = emptySet())
        assertEquals("unsupported_platform", handler.handle(run()).error())
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        fixture.lock()
        fixture.repository.state.first { !it.unlocked }
        assertEquals("vault_locked", handler.handle(run()).error())
    }

    @Test
    fun `run selects an environment by its remote ID, or else by its local ID`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.agent()
        assertEquals(SECRET, handler.handle(run("environment")).token())

        fixture.ciphers.value = listOf(fixture.secret.copy(name = "Production").withRemoteId("remote-1"))
        fixture.repository.state.first { it.environments.singleOrNull()?.remoteId == "remote-1" }
        assertEquals(SECRET, handler.handle(run("remote-1")).token())
        assertEquals(SECRET, handler.handle(run("environment")).token())
        assertEquals("not_found", handler.handle(run("Production")).error())

        // A remote ID wins over the same local ID of another environment.
        fixture.ciphers.value = listOf(
            fixture.secret.withRemoteId("remote-1"),
            fixture.secret.copy(id = "remote-1").withToken("other"),
        )
        fixture.repository.state.first { it.environments.size == 2 }
        assertEquals(SECRET, handler.handle(run("remote-1")).token())
    }

    @Test
    fun `run narrows an ID shared by several accounts to one account`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.profiles.value = listOf(createProfile("account"), createProfile("account-2"))
        fixture.ciphers.value = listOf(
            fixture.secret.withRemoteId("shared"),
            fixture.secret.copy(id = "environment-2", accountId = "account-2")
                .withRemoteId("shared")
                .withToken("other"),
        )
        fixture.readyCli()
        fixture.repository.state.first { it.environments.size == 2 && it.accounts.size == 2 }
        fixture.autoApprove(backgroundScope)
        val handler = fixture.agent()
        assertEquals("ambiguous_environment", handler.handle(run("shared")).error())
        // An account is selected by its remote ID, its local ID, or its name.
        for (account in listOf("profile-account-2", "account-2", "User account-2")) {
            assertEquals("other", handler.handle(run("shared", account = account)).token())
        }
        assertEquals(SECRET, handler.handle(run("shared", account = "account")).token())
        assertEquals("not_found", handler.handle(run("environment-2", account = "account")).error())
        assertEquals("account_not_found", handler.handle(run("shared", account = "missing")).error())

        fixture.profiles.value = listOf(
            createProfile("account", name = "Work"),
            createProfile("account-2", name = "Work"),
        )
        fixture.repository.state.first { state -> state.accounts.all { it.name == "Work" } }
        assertEquals("ambiguous_account", handler.handle(run("shared", account = "Work")).error())
    }

    @Test
    fun `a verified terminal tab reuses a run approval until it is idle or revoked`() = runTest {
        val time = TestTimeSource()
        val fixture = EnvironmentFixture(backgroundScope, timeSource = time)
        fixture.readyCli()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.agent()
        suspend fun prompts(authorization: CallerAuthorization?): Int {
            val before = fixture.approvedCapabilities.value.size
            val response = handler.handle(run(authorization = authorization))
            assertIs<CliAgentMessages.Response.Kind.Cli>(response)
            return fixture.approvedCapabilities.value.size - before
        }
        assertEquals(1, prompts(terminal('a')))
        assertEquals(0, prompts(terminal('a')))
        assertEquals(1, prompts(terminal('b')))
        // Neither a missing nor an application-only identity is a terminal tab.
        assertEquals(1, prompts(null))
        assertEquals(1, prompts(null))
        assertEquals(1, prompts(terminal('d', kind = 2, source = 12)))
        assertEquals(1, prompts(terminal('d', kind = 2, source = 12)))
        // Each use extends the approval; ten idle minutes expire it.
        time += 9.minutes
        assertEquals(0, prompts(terminal('a')))
        time += 10.minutes
        assertEquals(1, prompts(terminal('a')))
        // A revocation of the environments' grants also revokes the approval.
        fixture.mcp.setEnabled(false)
        assertEquals(1, prompts(terminal('a')))
        assertEquals(0, prompts(terminal('a')))
        // So does turning the CLI off and on.
        fixture.cli.setEnabled(false)
        fixture.cli.setEnabled(true)
        assertEquals(1, prompts(terminal('a')))
        assertEquals(0, prompts(terminal('a')))
        // And a new vault session.
        fixture.replaceSession()
        fixture.repository.state.first { it.unlocked && it.sessionVersion == "session-2" }
        assertEquals(1, prompts(terminal('a')))
    }

    @Test
    fun `a CLI approval window of zero asks every run, even from the same terminal tab`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, cliApprovalWindow = Duration.ZERO)
        fixture.readyCli()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.agent()
        repeat(2) {
            val before = fixture.approvedCapabilities.value.size
            val response = handler.handle(run(authorization = terminal('a')))
            assertIs<CliAgentMessages.Response.Kind.Cli>(response)
            assertEquals(1, fixture.approvedCapabilities.value.size - before)
        }
    }

    @Test
    fun `a remembered approval never skips a pending identity confirmation`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        fixture.readyCli()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.agent()
        repeat(2) {
            val before = fixture.approvedCapabilities.value.size
            assertEquals("reauthentication_required", handler.handle(run(authorization = terminal('a'))).error())
            assertEquals(1, fixture.approvedCapabilities.value.size - before)
        }
        val challenge = assertNotNull(fixture.access.elevationChallenge("environment"))
        fixture.access.grantElevatedAccess("environment", challenge)
        val before = fixture.approvedCapabilities.value.size
        assertEquals(SECRET, handler.handle(run(authorization = terminal('a'))).token())
        assertEquals(0, fixture.approvedCapabilities.value.size - before)
    }

    @Test
    fun `the user confirms their identity for a pending run through the CLI service`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        fixture.readyCli()
        val handler = fixture.agent()
        val pending = async { handler.handle(run()) }
        val request = fixture.cli.approvals.first { it.isNotEmpty() }.single()
        assertFalse(fixture.cli.confirmIdentity(request.id) { false })
        assertTrue(fixture.cli.confirmIdentity(request.id) { true })
        fixture.cli.respondToApproval(request.id, true)
        assertEquals(SECRET, pending.await().token())
        // A prompt that is no longer pending can not be confirmed.
        assertFalse(fixture.cli.confirmIdentity(request.id) { true })
    }

    @Test
    fun `an approval given after the environments' grants are revoked is never used`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val handler = fixture.agent()
        val pending = async { handler.handle(run(authorization = terminal('a'))) }
        val request = fixture.cli.approvals.first { it.isNotEmpty() }.single()
        fixture.mcp.setEnabled(false)
        fixture.cli.respondToApproval(request.id, true)
        assertEquals("approval_revoked", pending.await().error())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a change of the environment ends the run approval of a terminal tab`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value += fixture.secret.copy(id = "other", name = "Other")
        fixture.readyCli()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.agent()
        suspend fun prompts(): Int {
            val before = fixture.approvedCapabilities.value.size
            assertIs<CliAgentMessages.Response.Kind.Cli>(handler.handle(run(authorization = terminal('a'))))
            return fixture.approvedCapabilities.value.size - before
        }
        fun rotate(id: String) {
            fixture.ciphers.value = fixture.ciphers.value.map { if (it.id == id) it.withToken("rotated") else it }
        }
        assertEquals(1, prompts())
        assertEquals(0, prompts())

        rotate("other")
        runCurrent()
        assertEquals(0, prompts())

        rotate("environment")
        runCurrent()
        assertEquals(1, prompts())
        assertEquals(0, prompts())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an approval given after the environment changes is never used`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val handler = fixture.agent()
        val pending = async { handler.handle(run(authorization = terminal('a'))) }
        val request = fixture.cli.approvals.first { it.isNotEmpty() }.single()
        fixture.ciphers.value = listOf(fixture.secret.withToken("rotated"))
        runCurrent()
        fixture.cli.respondToApproval(request.id, true)
        assertEquals("approval_revoked", pending.await().error())
    }

    private suspend fun EnvironmentFixture.readyCli() {
        ready()
        cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        cli.setEnabled(true)
        cli.state.first { it.enabled }
    }

    private fun CliAgentMessages.Response.Kind.token(): String =
        Json.parseToJsonElement(cliResult()).jsonObject
            .getValue("variables").jsonObject
            .getValue("TOKEN").jsonPrimitive.content

    private fun DSecret.withToken(value: String) = copy(
        environment = environmentPayload(EnvironmentDefinition(listOf(
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal(value)),
        ))),
    )

    private fun run(
        environment: String = "environment",
        account: String? = null,
        authorization: CallerAuthorization? = null,
    ) = CliAgentMessages.Request.Kind.Cli(
        runRequest(
            environment = environment,
            account = account,
            command = listOf("npm", "start"),
            workingDirectory = "/project",
            caller = CallerIdentity(pid = 42, processName = "keyguard", authorization = authorization),
        ),
    )

    /** Agent evidence of a macOS terminal session, or of another [kind]. */
    private fun terminal(fingerprint: Char, kind: Int = 4, source: Int = 13) = CallerAuthorization(
        connectionFingerprint = ByteArray(32) { 0xcc.toByte() },
        subjects = listOf(
            CallerAuthorizationSubject(
                kind = kind,
                evidenceSource = source,
                fingerprint = ByteArray(32) { "$fingerprint$fingerprint".toInt(16).toByte() },
            ),
        ),
    )
}
