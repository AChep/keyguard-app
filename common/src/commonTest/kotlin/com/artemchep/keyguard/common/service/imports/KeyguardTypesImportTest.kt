package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.CxfRoundTripHarness
import com.artemchep.keyguard.common.service.credentialexchange.cxfSecret
import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.test.environmentPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * GPG keys and environments, the item types only Keyguard has, out through
 * Keyguard's own export and back in. A Keyguard ZIP and a backup hold the same
 * JSON export, so this is their restore too.
 */
class KeyguardTypesImportTest {
    @Test
    fun `gpg keys and environments come back from a keyguard export`() = runTest {
        val environment = environmentPayload(definitionOf(LITERAL))
        val plan = importExport(
            createSecret(
                id = "gpg",
                name = "Key",
                type = DSecret.Type.GpgKey,
                gpgKey = DSecret.GpgKey(
                    privateKeyArmored = "PRIVATE",
                    publicKeyArmored = "PUBLIC",
                    fingerprint = "ABCD",
                ),
            ),
            environmentSecret("Env", environment),
        )
        plan.assertVaultStorable()
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        val (gpg, env) = plan.items.map { it.request }
        assertEquals(DSecret.Type.GpgKey, gpg.type)
        assertEquals("PRIVATE", gpg.gpgKey.privateKeyArmored)
        assertEquals("PUBLIC", gpg.gpgKey.publicKeyArmored)
        assertEquals("ABCD", gpg.gpgKey.fingerprint)
        assertEquals(DSecret.Type.Environment, env.type)
        assertEquals(environment, env.environment)
        assertTrue(env.fields.isEmpty())
    }

    @Test
    fun `an environment comes back without its item references`() = runTest {
        // A reference names an item of the source account: nothing here, or
        // the original rather than its imported copy.
        val plan = importExport(
            environmentSecret("Env", environmentPayload(definitionOf(LITERAL, REFERENCE))),
        )
        plan.assertVaultStorable()
        assertEquals(
            definitionOf(LITERAL, REFERENCE.copy(source = EnvironmentValueSource.Unset)),
            plan.items.single().request.environment?.definition,
        )
        assertEquals(1, plan.skips[ImportSkipReason.EnvironmentReference])
        assertEquals(mapOf("Env" to 1), plan.skips.titlesOf(ImportSkipReason.EnvironmentReference))
    }

    @Test
    fun `an environment keeps the keys this version does not read`() = runTest {
        val document = withUnknownKey(EnvironmentFormat.encode(definitionOf(LITERAL)))
        val plan = importExport(
            environmentSecret("Env", EnvironmentFormat.decode(document)!!),
        )
        plan.assertVaultStorable()
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        assertEquals(document, plan.items.single().request.environment?.document)
    }

    @Test
    fun `an environment the vault would not write is counted, not imported`() = runTest {
        val plan = importExport(
            environmentSecret("Newer", EnvironmentPayload.Unsupported("""{"version": 2, "variables": []}""")),
            environmentSecret("Invalid", environmentPayload(definitionOf(LITERAL, LITERAL))),
            // A reference this version can not clear without rewriting the
            // keys it does not read.
            environmentSecret(
                "Unclearable",
                EnvironmentFormat.decode(withUnknownKey(EnvironmentFormat.encode(definitionOf(REFERENCE))))!!,
            ),
        )
        assertTrue(plan.items.isEmpty())
        assertEquals(
            mapOf("Newer" to 1, "Invalid" to 1, "Unclearable" to 1),
            plan.skips.titlesOf(ImportSkipReason.Environment),
        )
        assertEquals(3, plan.skips.totalCount, plan.skips.toString())
    }

    @Test
    fun `an environment comes back through credential exchange`() {
        val environment = environmentPayload(definitionOf(LITERAL))
        val plan = CxfRoundTripHarness()
            .roundTrip(
                secrets = listOf(
                    cxfSecret(type = DSecret.Type.Environment).copy(environment = environment),
                ),
            )
            .plan
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Environment, request.type)
        assertEquals(environment, request.environment)
        assertTrue(request.fields.isEmpty())
    }
}

private val LITERAL = EnvironmentVariable(
    name = "TOKEN",
    source = EnvironmentValueSource.Literal("value"),
)

private val REFERENCE = EnvironmentVariable(
    name = "PASSWORD",
    source = EnvironmentValueSource.Reference(
        cipherId = "00000000-0000-0000-0000-000000000001",
        field = EnvironmentFieldReference.Password,
    ),
)

private fun definitionOf(
    vararg variables: EnvironmentVariable,
) = EnvironmentDefinition(variables.toList())

/** The [document] as a newer Keyguard writes it, with a key this version does not read. */
private fun withUnknownKey(
    document: String,
) = document.replaceFirst("{", """{"future": true, """)

private fun environmentSecret(
    name: String,
    environment: EnvironmentPayload,
) = createSecret(
    id = name,
    name = name,
    type = DSecret.Type.Environment,
).copy(environment = environment)

private suspend fun importExport(
    vararg secrets: DSecret,
): ImportPlan {
    val exported = JsonExportServiceImpl(Json).export(
        organizations = emptyList(),
        collections = emptyList(),
        folders = emptyList(),
        ciphers = secrets.toList(),
    )
    return parseImportPlan(ImportFormatId.Bitwarden, exported)
}
