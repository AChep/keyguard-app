package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.ManageResponse
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.manageRequest
import com.artemchep.keyguard.common.service.cli.agent.manageResult
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentReferenceResolution
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class EnvironmentReferenceTest {
    @Test
    fun `references resolve the password and custom fields of an item of the same account`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(
            fixture.referencingEnvironment(),
            referenceTarget(),
        )
        fixture.ready()
        val snapshot = fixture.repository.resolveForDelivery("environment")
        assertEquals(
            mapOf("DB_PASSWORD" to "target-password", "API_KEY" to "target-key", "FLAG" to "true"),
            snapshot.variables,
        )
    }

    @Test
    fun `references fail closed for ambiguous hidden foreign trashed and missing targets`() = runTest {
        val cases = listOf(
            // Two custom fields with the referenced name.
            referenceTarget(
                fields = REFERENCE_FIELDS + DSecret.Field("API_KEY", "other", type = DSecret.Field.Type.Text),
            ),
            // Collections hide the password of the target.
            referenceTarget(collectionIds = setOf("collection")),
            // Another account.
            referenceTarget(accountId = "other"),
            // In the trash.
            referenceTarget(deleted = true),
            // Linked fields are never referenced.
            referenceTarget(fields = REFERENCE_FIELDS.map {
                if (it.name == "API_KEY") it.copy(type = DSecret.Field.Type.Linked) else it
            }),
            null,
        )
        for (target in cases) {
            val fixture = EnvironmentFixture(backgroundScope)
            fixture.ciphers.value = listOfNotNull(fixture.referencingEnvironment(), target)
            fixture.collections.value = listOf(collection(hidden = true))
            fixture.ready()
            val error = assertFailsWith<EnvironmentServiceException> {
                fixture.repository.resolveForDelivery("environment")
            }
            assertEquals("unresolved_environment", error.code)
            assertFalse("target-password" in error.toString())
        }
    }

    @Test
    fun `a display lookup denies the values of targets that ask for the master password`() = runTest {
        val environment = EnvironmentFixture(backgroundScope).referencingEnvironment()
        val reference = EnvironmentValueSource.Reference(REFERENCE_TARGET_ID, EnvironmentFieldReference.Password)
        fun resolve(target: DSecret) = environmentReferenceLookup(
            environment = environment,
            ciphers = listOf(environment, target),
            collections = emptyList(),
            denyReprompt = true,
        ).resolve(reference)
        assertEquals(EnvironmentReferenceResolution.Denied, resolve(referenceTarget(reprompt = true)))
        val value = assertIs<EnvironmentReferenceResolution.Value>(resolve(referenceTarget(reprompt = false)))
        assertEquals("target-password", value.value)
    }

    @Test
    fun `a change of a referenced item reaches the local env files`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.referencingEnvironment(), referenceTarget())
        fixture.ready()
        runCurrent()
        val deliveryEpoch = fixture.access.deliveryGeneration("environment")
        fixture.ciphers.value = listOf(
            fixture.referencingEnvironment(),
            referenceTarget(password = "rotated-password"),
        )
        runCurrent()
        assertTrue(deliveryEpoch != fixture.access.deliveryGeneration("environment"))
        assertEquals("rotated-password", fixture.repository.resolveForDelivery("environment").variables["DB_PASSWORD"])
    }

    @Test
    fun `MCP reports references but can neither read nor overwrite them`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.referencingEnvironment(), referenceTarget())
        fixture.ready()
        fixture.autoApprove(backgroundScope)
        val handler = fixture.broker()
        val listed = handler.handle(manage("list_variables"))
        val variables = assertIs<ManageResponse.Result.Variables>(listed.manageResult()).value.variables
        assertEquals(CliAgentMessages.VariableSource.REFERENCE, variables.first { it.name == "DB_PASSWORD" }.source)
        assertFalse("target-password" in listed.toString())
        assertFalse(REFERENCE_TARGET_ID in listed.toString())
        val overwritten = handler.handle(
            manageRequest(
                tool = "append_variables",
                arguments = mapOf("environment_id" to "environment"),
                variables = listOf(CliAgentMessages.VariableUpdate("DB_PASSWORD", "replaced".encodeToByteArray())),
            ),
        )
        assertEquals("variable_is_reference", overwritten.error())
        assertTrue(fixture.saved.isEmpty())
    }

    private fun EnvironmentFixture.referencingEnvironment(): DSecret = secret.copy(
        environment = environmentPayload(EnvironmentDefinition(listOf(
            EnvironmentVariable(
                "DB_PASSWORD",
                EnvironmentValueSource.Reference(REFERENCE_TARGET_ID, EnvironmentFieldReference.Password),
            ),
            EnvironmentVariable(
                "API_KEY",
                EnvironmentValueSource.Reference(REFERENCE_TARGET_ID, EnvironmentFieldReference.Custom("API_KEY")),
            ),
            EnvironmentVariable(
                "FLAG",
                EnvironmentValueSource.Reference(REFERENCE_TARGET_ID, EnvironmentFieldReference.Custom("FLAG")),
            ),
        ))),
    )

    private fun referenceTarget(
        accountId: String = "account",
        password: String = "target-password",
        fields: List<DSecret.Field> = REFERENCE_FIELDS,
        collectionIds: Set<String> = emptySet(),
        deleted: Boolean = false,
        // The master password prompt of a target does not change its delivery.
        reprompt: Boolean = true,
    ) = createSecret(
        "target",
        accountId = accountId,
        collectionIds = collectionIds,
        reprompt = reprompt,
        service = BitwardenService(
            remote = BitwardenService.Remote(
                id = REFERENCE_TARGET_ID,
                revisionDate = Instant.fromEpochSeconds(0),
                deletedDate = null,
            ),
        ),
        login = DSecret.Login(password = password),
        fields = fields,
        deletedDate = Instant.fromEpochSeconds(0).takeIf { deleted },
    )

    private companion object {
        const val REFERENCE_TARGET_ID = "6f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41"
        val REFERENCE_FIELDS = listOf(
            DSecret.Field("API_KEY", "target-key", type = DSecret.Field.Type.Hidden),
            DSecret.Field("FLAG", "true", type = DSecret.Field.Type.Boolean),
        )
    }
}
