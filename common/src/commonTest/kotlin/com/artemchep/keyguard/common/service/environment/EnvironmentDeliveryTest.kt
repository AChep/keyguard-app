package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnvironmentDeliveryTest {
    @Test
    fun `editing another mounted environment preserves an outstanding FIFO read`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment", "other"))
        val other = fixture.secret.copy(id = "other")
        fixture.ciphers.value += other
        fixture.ready()
        val handler = fixture.broker()
        val before = handler.generations()
        val pending = async { handler.resolve("environment", before.getValue("environment")) }
        val approval = fixture.access.approvals.first { it.isNotEmpty() }.single()

        fixture.ciphers.value = listOf(fixture.secret, other.copy(
            name = "Renamed environment",
            environment = environmentPayload(EnvironmentDefinition()),
        ))
        runCurrent()

        val after = handler.generations()
        assertEquals(before.getValue("environment"), after.getValue("environment"))
        assertNotEquals(before.getValue("other"), after.getValue("other"))
        assertEquals(approval.id, fixture.access.approvals.value.single().id)
        fixture.access.respondToApproval(approval.id, true)
        val response = pending.await()
        val content = assertIs<CliAgentMessages.Response.Kind.ResolveMount>(response).value.content
        assertTrue(SECRET in content.decodeToString())
    }

    @Test
    fun `removing or replacing another environment reference preserves pending reads and caller grants`() = runTest {
        val replacementId = "90585e93-69fd-47bb-8ce5-778c879dc3f3"
        val replacements = listOf(
            EnvironmentValueSource.Literal("literal-value"),
            EnvironmentValueSource.Reference(replacementId, EnvironmentFieldReference.Password),
        )
        for (replacement in replacements) {
            val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment", "other"))
            val other = fixture.secret.copy(id = "other").withReference()
            val targets = listOf(referenceTarget(), referenceTarget().copy(id = "replacement")
                .withRemoteId(replacementId))
            fixture.ciphers.value = listOf(fixture.secret, other) + targets
            fixture.ready()
            val handler = fixture.broker()
            val before = handler.generations()
            val authorization = fixture.access.generation.value
            val pending = async { handler.resolve("environment", before.getValue("environment")) }
            val approval = fixture.access.approvals.first { it.isNotEmpty() }.single()

            fixture.ciphers.value = listOf(fixture.secret, other.copy(environment = environmentPayload(
                EnvironmentDefinition(listOf(EnvironmentVariable("TOKEN", replacement))),
            ))) + targets
            runCurrent()

            val after = handler.generations()
            assertEquals(authorization, fixture.access.generation.value)
            assertEquals(before.getValue("environment"), after.getValue("environment"))
            assertNotEquals(before.getValue("other"), after.getValue("other"))
            fixture.access.respondToApproval(approval.id, true)
            val response = pending.await()
            val content = assertIs<CliAgentMessages.Response.Kind.ResolveMount>(response).value.content
            assertTrue(SECRET in content.decodeToString())
        }
    }

    @Test
    fun `editing an unmounted environment does not wake the mount watch`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment"))
        val other = fixture.secret.copy(id = "other")
        fixture.ciphers.value += other
        fixture.ready()
        val revision = fixture.mounts.revision

        fixture.ciphers.value = listOf(fixture.secret, other.copy(name = "Renamed environment"))
        runCurrent()

        assertEquals(revision, fixture.mounts.revision)
    }

    @Test
    fun `reordering and unrelated creation or removal preserve mount generations`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment"))
        fixture.ready()
        val handler = fixture.broker()
        val before = handler.generations()
        val other = fixture.secret.copy(id = "other")

        fixture.ciphers.value += other
        runCurrent()
        assertEquals(before, handler.generations())
        val revision = fixture.mounts.revision
        fixture.ciphers.value = fixture.ciphers.value.reversed()
        runCurrent()
        assertEquals(before, handler.generations())
        assertEquals(revision, fixture.mounts.revision)

        val authorization = fixture.access.generation.value
        fixture.ciphers.value = listOf(fixture.secret)
        runCurrent()
        assertEquals(before, handler.generations())
        // Deletion still revokes caller authorizations conservatively.
        assertNotEquals(authorization, fixture.access.generation.value)
    }

    @Test
    fun `a removed environment stops being served and restoration uses a new generation`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment"))
        fixture.ready()
        val handler = fixture.broker()
        val before = handler.generations().getValue("environment")

        fixture.ciphers.value = emptyList()
        runCurrent()
        assertTrue(handler.generations().isEmpty())
        fixture.ciphers.value = listOf(fixture.secret)
        runCurrent()

        assertNotEquals(before, handler.generations().getValue("environment"))
        assertEquals("vault_locked", handler.resolve("environment", before).error())
    }

    @Test
    fun `referenced item edits and removal invalidate only its dependent environments`() = runTest {
        val fixture = EnvironmentFixture(
            backgroundScope,
            configuredMounts = mounts("environment", "shared", "unrelated"),
        )
        val environment = fixture.secret.withReference()
        val shared = environment.copy(id = "shared")
        val unrelated = fixture.secret.copy(id = "unrelated")
        val target = referenceTarget()
        fixture.ciphers.value = listOf(environment, shared, unrelated, target)
        fixture.ready()
        val handler = fixture.broker()
        val before = handler.generations()

        fixture.ciphers.value = listOf(environment, shared, unrelated, target.copy(
            login = DSecret.Login(password = "rotated-password"),
        ))
        runCurrent()
        val rotated = handler.generations()
        assertNotEquals(before.getValue("environment"), rotated.getValue("environment"))
        assertNotEquals(before.getValue("shared"), rotated.getValue("shared"))
        assertEquals(before.getValue("unrelated"), rotated.getValue("unrelated"))
        assertEquals("rotated-password", fixture.repository.resolveForDelivery("environment").variables["TOKEN"])
        assertEquals("vault_locked", handler.resolve("environment", before.getValue("environment"))
            .error())

        val authorization = fixture.access.generation.value
        fixture.ciphers.value = listOf(environment, shared, unrelated)
        runCurrent()
        val removed = handler.generations()
        assertNotEquals(authorization, fixture.access.generation.value)
        assertNotEquals(rotated.getValue("environment"), removed.getValue("environment"))
        assertNotEquals(rotated.getValue("shared"), removed.getValue("shared"))
        assertEquals(rotated.getValue("unrelated"), removed.getValue("unrelated"))
        fixture.autoApprove(backgroundScope)
        val response = handler.resolve("environment", removed.getValue("environment"))
        assertEquals("unresolved_environment", response.error())
        assertFalse("rotated-password" in response.toString())
    }

    @Test
    fun `trashing or moving a referenced target revokes caller grants and dependent delivery`() = runTest {
        val changes = listOf(
            referenceTarget().copy(deletedDate = Instant.fromEpochSeconds(1)),
            referenceTarget().copy(accountId = "other-account"),
        )
        for (changedTarget in changes) {
            val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment", "unrelated"))
            val environment = fixture.secret.withReference()
            val unrelated = fixture.secret.copy(id = "unrelated")
            fixture.ciphers.value = listOf(environment, unrelated, referenceTarget())
            fixture.ready()
            val handler = fixture.broker()
            val before = handler.generations()
            val authorization = fixture.access.generation.value

            fixture.ciphers.value = listOf(environment, unrelated, changedTarget)
            runCurrent()

            val after = handler.generations()
            assertNotEquals(authorization, fixture.access.generation.value)
            assertNotEquals(before.getValue("environment"), after.getValue("environment"))
            assertEquals(before.getValue("unrelated"), after.getValue("unrelated"))
            fixture.autoApprove(backgroundScope)
            assertEquals("unresolved_environment", handler.resolve("environment", after.getValue("environment"))
                .error())
        }
    }

    @Test
    fun `reference permission revocation rejects stale generations and current delivery`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment", "unrelated"))
        fixture.ciphers.value = listOf(
            fixture.secret.withReference(), fixture.secret.copy(id = "unrelated"),
            referenceTarget().copy(collectionIds = setOf("collection")),
        )
        val collection = DCollection(
            id = "collection", externalId = null, organizationId = "organization", accountId = "account",
            revisionDate = Instant.fromEpochSeconds(0), name = "Collection", readOnly = false, hidePasswords = false,
        )
        fixture.collections.value = listOf(collection)
        fixture.ready()
        val handler = fixture.broker()
        val before = handler.generations()
        val authorization = fixture.access.generation.value

        fixture.collections.value = listOf(collection.copy(hidePasswords = true))
        runCurrent()

        val after = handler.generations()
        assertNotEquals(authorization, fixture.access.generation.value)
        assertNotEquals(before.getValue("environment"), after.getValue("environment"))
        assertEquals(before.getValue("unrelated"), after.getValue("unrelated"))
        assertEquals("vault_locked", handler.resolve("environment", before.getValue("environment"))
            .error())
        fixture.autoApprove(backgroundScope)
        assertEquals("unresolved_environment", handler.resolve("environment", after.getValue("environment"))
            .error())
    }

    @Test
    fun `session replacement and lock revoke both default and changed environment generations`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, configuredMounts = mounts("environment", "other"))
        val other = fixture.secret.copy(id = "other")
        fixture.ciphers.value += other
        fixture.ready()
        fixture.ciphers.value = listOf(fixture.secret.copy(name = "Renamed environment"), other)
        runCurrent()
        val handler = fixture.broker()
        val before = handler.generations()
        assertNotEquals(before.getValue("environment"), before.getValue("other"))

        fixture.replaceSession()
        runCurrent()
        val replaced = handler.generations()
        before.forEach { (id, generation) -> assertNotEquals(generation, replaced.getValue(id)) }
        assertEquals("vault_locked", handler.resolve("environment", before.getValue("environment"))
            .error())

        fixture.lock()
        runCurrent()
        val locked = handler.generations()
        replaced.forEach { (id, generation) -> assertNotEquals(generation, locked.getValue(id)) }
        assertTrue(fixture.repository.state.value.environments.isEmpty())
    }

    private fun mounts(vararg ids: String) = ids.map { id ->
        EnvironmentMount("mount-$id", id, "/tmp/$id.env")
    }

    /** The generation of each served mount, by its environment: see [mounts]. */
    private suspend fun EnvironmentBrokerHandler.generations(): Map<String, String> {
        val response = handle(CliAgentMessages.Request.Kind.Mounts(CliAgentMessages.Empty))
        return assertIs<CliAgentMessages.Response.Kind.Mounts>(response).value.mounts
            .associate { mount -> mount.id.removePrefix("mount-") to mount.generation }
    }

    private suspend fun EnvironmentBrokerHandler.resolve(environmentId: String, generation: String) = handle(
        CliAgentMessages.Request.Kind.ResolveMount(
            CliAgentMessages.ResolveMountRequest(mountId = "mount-$environmentId", generation = generation),
        ),
    )

    private fun DSecret.withReference() = copy(environment = environmentPayload(EnvironmentDefinition(listOf(
        EnvironmentVariable("TOKEN", EnvironmentValueSource.Reference(TARGET_ID, EnvironmentFieldReference.Password)),
    ))))

    private fun referenceTarget() = createSecret(
        "target", accountId = "account", login = DSecret.Login(password = "initial-password"),
    ).withRemoteId(TARGET_ID)

    companion object {
        private const val TARGET_ID = "6f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41"
    }
}
