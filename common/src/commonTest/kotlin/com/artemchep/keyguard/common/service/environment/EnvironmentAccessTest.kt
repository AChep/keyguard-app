package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Response
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class EnvironmentAccessTest {
    @Test
    fun `locking while approval is pending prevents completion and clears sensitive state`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val pending = async { fixture.broker().handle(manage("list_variables")) }
        fixture.access.approvals.first { it.isNotEmpty() }
        fixture.lock()
        val response = pending.await()
        assertIs<Response.Kind.Error>(response)
        fixture.repository.state.first { !it.unlocked }
        assertTrue(fixture.repository.state.value.environments.isEmpty())
        assertTrue(fixture.access.approvals.value.isEmpty())
        assertFalse(SECRET in response.toString())
        assertEquals("vault_locked", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.load("environment")
        }.code)
    }

    @Test
    fun `locking during a vault operation fails it as locked`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val started = CompletableDeferred<Unit>()
        val pending = async {
            runCatching {
                fixture.environmentVault.withVault {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        fixture.lock()
        val e = assertIs<EnvironmentServiceException>(pending.await().exceptionOrNull())
        assertEquals("vault_locked", e.code)
    }

    @Test
    fun `cancelling the caller of a vault operation still cancels it`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val started = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val pending = launch {
            try {
                fixture.environmentVault.withVault {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        started.await()
        pending.cancelAndJoin()
        assertIs<CancellationException>(failure)
    }

    @Test
    fun `a create approval names its account and is remembered for that account only`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        suspend fun create(accountId: String) =
            fixture.access.approve("caller", null, EnvironmentCapability.CreateEnvironment, accountId = accountId)

        val first = async { create("account") }
        val request = fixture.access.approvals.first { it.isNotEmpty() }.single()
        assertEquals(EnvironmentCapability.CreateEnvironment, request.capability)
        assertEquals("account", request.accountId)
        assertNull(request.environmentName)
        fixture.access.respondToApproval(request.id, true)
        first.await()
        create("account")

        val other = async { create("account-2") }
        assertEquals("account-2", fixture.access.approvals.first { it.isNotEmpty() }.single().accountId)
        other.cancelAndJoin()
    }

    @Test
    fun `a change of the environment asks again before its local env file is read`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value += fixture.secret.copy(id = "other")
        fixture.ready()
        fixture.autoApprove(backgroundScope)
        suspend fun prompts(block: suspend () -> Unit): Int {
            val before = fixture.approvedCapabilities.value.size
            block()
            return fixture.approvedCapabilities.value.size - before
        }
        suspend fun read() = prompts {
            fixture.access.approve(
                "mount:mount", null, EnvironmentCapability.ReadLocalEnvFile, "environment", "/tmp/environment.env",
            )
        }
        suspend fun list() = prompts {
            fixture.access.approve("caller", null, EnvironmentCapability.ListVariables, "environment")
        }
        fun edit(id: String) {
            fixture.ciphers.value = fixture.ciphers.value.map {
                if (it.id == id) it.copy(environment = environmentPayload(EnvironmentDefinition())) else it
            }
        }
        assertEquals(1, read())
        assertEquals(1, list())
        assertEquals(0, read())

        edit("other")
        runCurrent()
        assertEquals(0, read())

        edit("environment")
        runCurrent()
        assertEquals(1, read())
        // An MCP client never receives the values, so its approval stays.
        assertEquals(0, list())
    }

    @Test
    fun `a FIFO read initiated while locked completes after unlock and approval`() = runTest {
        val mount = EnvironmentMount("mount", "environment", "/tmp/environment-test.env")
        val fixture = EnvironmentFixture(backgroundScope, initiallyLocked = true, configuredMounts = listOf(mount))
        fixture.mounts.state.first { it.mounts.isNotEmpty() }
        runCurrent()
        val epoch = fixture.access.deliveryGeneration("environment")
        val pending = async { fixture.broker().handle(resolveMount(epoch)) }
        val lockedRequest = fixture.access.approvals.first { it.isNotEmpty() }.single()
        assertEquals(null, lockedRequest.environmentName)

        fixture.unlock()
        val unlockedRequest = fixture.access.approvals.first {
            it.singleOrNull()?.environmentName == fixture.secret.name
        }.single()
        assertEquals(lockedRequest.id, unlockedRequest.id)
        assertEquals(epoch, fixture.access.deliveryGeneration("environment"))
        fixture.access.respondToApproval(unlockedRequest.id, true)
        val response = pending.await().resolved()
        assertEquals(epoch, response.generation)
        assertTrue(SECRET in response.content.decodeToString())

        fixture.lock()
        runCurrent()
        assertTrue(epoch != fixture.access.deliveryGeneration("environment"))
    }

    @Test
    fun `unlock refreshes a pending FIFO approval with its re prompt challenge`() = runTest {
        val mount = EnvironmentMount("mount", "environment", "/tmp/environment-test.env")
        val fixture = EnvironmentFixture(backgroundScope, initiallyLocked = true, configuredMounts = listOf(mount))
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        fixture.mounts.state.first { it.mounts.isNotEmpty() }
        runCurrent()
        val epoch = fixture.access.deliveryGeneration("environment")
        val handler = fixture.broker()
        val pending = async { handler.handle(resolveMount(epoch)) }
        val lockedRequest = fixture.access.approvals.first { it.isNotEmpty() }.single()
        assertEquals(null, lockedRequest.elevationChallenge)

        fixture.unlock()
        val unlockedRequest = fixture.access.approvals.first {
            it.singleOrNull()?.elevationChallenge != null
        }.single()
        assertEquals(lockedRequest.id, unlockedRequest.id)
        assertEquals(epoch, fixture.access.deliveryGeneration("environment"))
        fixture.access.respondToApproval(unlockedRequest.id, true)
        val refused = pending.await()
        assertEquals("reauthentication_required", refused.error())

        fixture.access.grantElevatedAccess("environment", assertNotNull(unlockedRequest.elevationChallenge))
        val approved = handler.handle(resolveMount(epoch)).resolved()
        assertTrue(SECRET in approved.content.decodeToString())
    }

    @Test
    fun `denied and timed out approvals produce safe failures and remove pending requests`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val handler = fixture.broker()
        val denied = async { handler.handle(manage("list_variables")) }
        val request = fixture.access.approvals.first { it.isNotEmpty() }.single()
        fixture.access.respondToApproval(request.id, false)
        assertEquals("user_denied", denied.await().error())
        assertTrue(fixture.access.approvals.value.isEmpty())
        val timedOut = async { handler.handle(manage("list_variables")) }
        fixture.access.approvals.first { it.isNotEmpty() }
        advanceTimeBy(60_001)
        val timeoutCode = timedOut.await().error()
        assertTrue(timeoutCode in setOf("request_timeout", "approval_timeout"))
        assertTrue(fixture.access.approvals.value.isEmpty())
    }

    @Test
    fun `disconnected MCP caller cancels pending approval without changing data`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val request = async {
            fixture.broker().handle(manage("rename_environment", "changed"))
        }
        fixture.access.approvals.first { it.isNotEmpty() }
        request.cancelAndJoin()
        assertTrue(fixture.access.approvals.value.isEmpty())
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun `re prompt requires verified elevation and rejects stale session challenges`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        fixture.ready()
        assertEquals("reauthentication_required", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.load("environment")
        }.code)
        val challenge = assertNotNull(fixture.access.elevationChallenge("environment"))
        fixture.access.grantElevatedAccess("environment", challenge)
        assertTrue(fixture.repository.load("environment").definition != null)
        fixture.replaceSession()
        assertEquals("vault_locked", assertFailsWith<EnvironmentServiceException> {
            fixture.access.grantElevatedAccess("environment", challenge)
        }.code)
        assertEquals("reauthentication_required", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.load("environment")
        }.code)
    }

    @Test
    fun `payload changes retain MCP grants while permission changes revoke them`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(collectionIds = setOf("collection")))
        fixture.collections.value = listOf(collection())
        fixture.ready()
        runCurrent()
        val capabilityEpoch = fixture.access.generation.value
        val deliveryEpoch = fixture.access.deliveryGeneration("environment")
        fixture.ciphers.value += createSecret("unrelated")
        runCurrent()
        assertEquals(deliveryEpoch, fixture.access.deliveryGeneration("environment"))
        fixture.ciphers.value = fixture.ciphers.value.map {
            if (it.id == "environment") {
                it.copy(environment = environmentPayload(EnvironmentDefinition()))
            } else it
        }
        runCurrent()
        assertTrue(deliveryEpoch != fixture.access.deliveryGeneration("environment"))
        assertEquals(capabilityEpoch, fixture.access.generation.value)
        val changedEpoch = fixture.access.deliveryGeneration("environment")
        fixture.collections.value = listOf(collection(hidden = true))
        runCurrent()
        assertTrue(changedEpoch != fixture.access.deliveryGeneration("environment"))
        assertTrue(capabilityEpoch != fixture.access.generation.value)
    }

    @Test
    fun `bookmark refresh preserves session approval and elevation grants`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.mounts.configure(supported = true)
        val mount = fixture.mounts.create("environment", "/tmp/environment-test.env", "initial")
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        runCurrent()
        val challenge = assertNotNull(fixture.access.elevationChallenge("environment"))
        fixture.access.grantElevatedAccess("environment", challenge)
        val capabilityEpoch = fixture.access.generation.value
        val deliveryEpoch = fixture.access.deliveryGeneration("environment")
        fixture.mounts.refreshBookmark(mount.id, mount.path, "refreshed")
        runCurrent()
        assertEquals(capabilityEpoch, fixture.access.generation.value)
        assertEquals(deliveryEpoch, fixture.access.deliveryGeneration("environment"))
        assertTrue(fixture.repository.load("environment").definition != null)
    }

    @Test
    fun `configuration changes revoke caller grants but keep elevated access`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        fixture.ready()
        fixture.mounts.configure(supported = true)
        val challenge = assertNotNull(fixture.access.elevationChallenge("environment"))
        fixture.access.grantElevatedAccess("environment", challenge)
        val capabilityEpoch = fixture.access.generation.value
        val mount = fixture.mounts.create("environment", "/tmp/environment-test.env", "bookmark")
        fixture.mounts.disable(mount.id)
        fixture.mcp.setEnabled(false)
        runCurrent()
        assertTrue(capabilityEpoch != fixture.access.generation.value)
        assertEquals(null, fixture.access.elevationChallenge("environment"))
        assertTrue(fixture.repository.load("environment").definition != null)
        // Retiring the session still revokes the confirmed access.
        fixture.replaceSession()
        assertEquals("reauthentication_required", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.load("environment")
        }.code)
    }

    @Test
    fun `cached approval requests elevation when reprompt is newly enabled`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        suspend fun read() =
            fixture.access.approve("reader", null, EnvironmentCapability.ReadLocalEnvFile, "environment")

        val first = async { read() }
        val initial = fixture.access.approvals.first { it.isNotEmpty() }.single()
        fixture.access.respondToApproval(initial.id, true)
        first.await()
        fixture.ciphers.value = listOf(fixture.secret.copy(reprompt = true))
        val second = async { read() }
        val request = fixture.access.approvals.first { it.isNotEmpty() }.single()
        val challenge = assertNotNull(request.elevationChallenge)
        fixture.access.grantElevatedAccess("environment", challenge)
        fixture.access.respondToApproval(request.id, true)
        second.await()
        assertNotNull(fixture.repository.load("environment").definition)
    }

    @Test
    fun `changed collection permissions revoke queued operation authorization`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(collectionIds = setOf("collection")))
        fixture.collections.value = listOf(collection())
        fixture.ready()
        runCurrent()
        val epoch = fixture.access.generation.value
        fixture.collections.value = listOf(collection(readOnly = true))
        fixture.access.generation.first { it != epoch }
        assertFalse(fixture.repository.state.value.environments.single().editable)
    }

    @Test
    fun `approval requests carry the verified caller and the reported client name`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val pending = async {
            fixture.broker().handle(
                manage(
                    tool = "list_variables",
                    caller = CallerIdentity(
                        pid = 42,
                        processName = "keyguard",
                        executablePath = "/usr/local/bin/keyguard",
                        appName = "Terminal",
                    ),
                    clientName = "Claude Code",
                ),
            )
        }
        val request = fixture.access.approvals.first { it.isNotEmpty() }.single()
        assertEquals(
            EnvironmentCaller(
                pid = 42,
                processName = "keyguard",
                executablePath = "/usr/local/bin/keyguard",
                appName = "Terminal",
                clientName = "Claude Code",
            ),
            request.caller,
        )
        fixture.access.respondToApproval(request.id, false)
        assertEquals("user_denied", pending.await().error())
    }
}
