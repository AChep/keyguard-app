package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.test.IdentityBase64Service
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.TEST_INSTANT
import com.artemchep.keyguard.test.TestCryptoGenerator
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.test.gpgMetadata
import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentLimits
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CipherDuplicatesCheckImplTest {
    private val duplicateChecker = CipherDuplicatesCheckImpl(
        cryptoGenerator = DuplicateTestCryptoGenerator,
        base64Service = IdentityBase64Service,
        similarityService = DuplicateTestSimilarityService,
        logRepository = LogRepositoryBridge(emptyList()),
    )

    @Test
    fun `archived and trashed items are excluded from duplicate groups until restored`() {
        val active = gpgSecret(id = "active", name = "Key")
        val archived = active.copy(id = "archived", archivedDate = TEST_INSTANT)
        val trashed = active.copy(id = "trashed", deletedDate = TEST_INSTANT)

        assertEquals(emptyList(), duplicatesCheck(listOf(active, archived, trashed)))
        assertEquals(
            setOf("active", "archived"),
            duplicatesCheck(listOf(active, archived.copy(archivedDate = null), trashed))
                .single().ciphers.map { it.id }.toSet(),
        )
    }

    @Test
    fun `gpg keys with same normalized fingerprint are duplicates despite different names`() {
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "local",
                    name = "Work signing key",
                    fingerprint = "d0bb cfbb 250d 3bb0 658e 5384 f83d 947d 29ef ecf7",
                ),
                gpgSecret(
                    id = "remote",
                    name = "Imported backup",
                    fingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
                ),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(
            setOf("local", "remote"),
            groups.single().ciphers.map { it.id }.toSet(),
        )
    }

    @Test
    fun `gpg keys with same public key are duplicates when fingerprint is missing`() {
        val publicKey = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----
            test-public-key
            -----END PGP PUBLIC KEY BLOCK-----
        """.trimIndent()

        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "First key",
                    fingerprint = null,
                    publicKeyArmored = publicKey,
                ),
                gpgSecret(
                    id = "second",
                    name = "Second key",
                    fingerprint = null,
                    publicKeyArmored = publicKey.replace("\n", "\r\n"),
                ),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(
            setOf("first", "second"),
            groups.single().ciphers.map { it.id }.toSet(),
        )
    }

    @Test
    fun `gpg keys with different fingerprints and same placeholder name are not duplicates`() {
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "GPG key",
                    fingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
                ),
                gpgSecret(
                    id = "second",
                    name = "GPG key",
                    fingerprint = "55C9BA78E6D4B1F84467EEC2FBA61A6B8021220A",
                ),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    @Test
    fun `derived gpg metadata does not make keys duplicates when key material is missing`() {
        val metadata = gpgMetadata(
            GpgAgentKeyMetadataKey(
                    keygrip = "keygrip-a",
                    fingerprint = "d0bb cfbb 250d 3bb0 658e 5384 f83d 947d 29ef ecf7",
            ),
        )
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "Imported key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                    metadata = metadata,
                ),
                gpgSecret(
                    id = "second",
                    name = "Backup key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                    metadata = metadata,
                ),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    @Test
    fun `gpg keys without key material and same placeholder name are not duplicates`() {
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "GPG key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                ),
                gpgSecret(
                    id = "second",
                    name = "GPG key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                ),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    @Test
    fun `environment variable order and different metadata do not prevent exact content matches`() {
        val variables = listOf(literalVariable("TOKEN", "secret"), literalVariable("PORT", "8080"))
        val first = environmentSecret("first", variables, name = "Project", notes = "Original")
        val second = environmentSecret("second", variables.reversed(), name = "Backup", notes = "Imported")

        CipherDuplicatesCheck.Sensitivity.entries.forEach { sensitivity ->
            val group = duplicatesCheck(listOf(first, second), sensitivity).single()
            assertEquals(setOf("first", "second"), group.ciphers.map { it.id }.toSet())
            assertEquals(0.7f, group.accuracy, absoluteTolerance = 0.00001f)
        }
    }

    @Test
    fun `environment conflicts and missing variables produce different symmetric scores`() {
        val variables = listOf(
            literalVariable("A", "a"),
            literalVariable("B", "b"),
            literalVariable("C", "c"),
        )
        val first = environmentSecret("first", variables)
        val changed = environmentSecret("changed", variables.dropLast(1) + literalVariable("C", "other"))
        val missing = environmentSecret("missing", variables.dropLast(1))

        listOf(changed to 0.1f, missing to (0.85f / 3f + 0.1f)).forEach { (second, expected) ->
            val forward = duplicatesCheck(listOf(first, second)).single().accuracy
            val backward = duplicatesCheck(listOf(second, first)).single().accuracy
            assertEquals(expected, forward, absoluteTolerance = 0.00001f)
            assertEquals(forward, backward)
        }
        assertEquals(emptyList(), duplicatesCheck(listOf(first, changed), CipherDuplicatesCheck.Sensitivity.HIGH))
        assertEquals(1, duplicatesCheck(listOf(first, missing), CipherDuplicatesCheck.Sensitivity.MAX).size)
    }

    @Test
    fun `environment matching follows every sensitivity threshold with strict equality excluded`() {
        val variables = listOf(
            literalVariable("A", "a"),
            literalVariable("B", "b"),
            literalVariable("C", "c"),
        )
        val first = environmentSecret("first", variables, name = "")
        val second = environmentSecret("second", variables.dropLast(1) + literalVariable("C", "other"), name = "")

        CipherDuplicatesCheck.Sensitivity.entries.forEach { sensitivity ->
            val groups = duplicatesCheck(listOf(first, second), sensitivity)
            val expectedCount = when (sensitivity) {
                CipherDuplicatesCheck.Sensitivity.LOW,
                CipherDuplicatesCheck.Sensitivity.MIN,
                -> 1
                else -> 0
            }
            assertEquals(expectedCount, groups.size, sensitivity.name)
            groups.singleOrNull()?.let { assertEquals(0f, it.accuracy) }
        }
    }

    @Test
    fun `normalized titles and notes help borderline environments`() {
        val variables = listOf(
            literalVariable("A", "a"),
            literalVariable("B", "b"),
            literalVariable("C", "c"),
        )
        val first = environmentSecret("first", variables, name = "Project")
        val second = environmentSecret(
            "second", variables.dropLast(1) + literalVariable("C", "other"), name = "Project copy 2",
        )
        assertEquals(1, duplicatesCheck(listOf(first, second)).size)
        assertEquals(emptyList(), duplicatesCheck(listOf(first, second.copy(name = "Unrelated"))))

        val withoutTitle = first.copy(name = "", notes = "Project notes")
        val withoutTitleCopy = second.copy(name = "", notes = "PROJECT NOTES!")
        assertEquals(0.05f, duplicatesCheck(listOf(withoutTitle, withoutTitleCopy)).single().accuracy)
        assertEquals(emptyList(), duplicatesCheck(listOf(withoutTitle, withoutTitleCopy.copy(notes = ""))))
        assertEquals(emptyList(), duplicatesCheck(listOf(first.copy(name = ""), second)))
    }

    @Test
    fun `environment names and literal values preserve case and whitespace`() {
        val first = environmentSecret("first", listOf(literalVariable("TOKEN", "Secret")))
        val differences = listOf(
            literalVariable("token", "Secret"),
            literalVariable("OTHER", "Secret"),
            literalVariable("TOKEN", "secret"),
            literalVariable("TOKEN", " Secret"),
            literalVariable("TOKEN", "Secret "),
            literalVariable("TOKEN", "Secret\n"),
        )
        differences.forEach { variable ->
            assertNotDuplicates(first, environmentSecret("second", listOf(variable)))
        }
    }

    @Test
    fun `unrelated environment contents cannot match through identical metadata`() {
        val first = environmentSecret("first", listOf(literalVariable("TOKEN", "first")), notes = "Same notes")
        val second = environmentSecret("second", listOf(literalVariable("TOKEN", "second")), notes = "Same notes")
        val disjoint = environmentSecret("third", listOf(literalVariable("OTHER", "first")), notes = "Same notes")

        assertNotDuplicates(first, second)
        assertNotDuplicates(first, disjoint)
    }

    @Test
    fun `matching unset templates and empty literals are duplicates but remain distinct`() {
        val unset = EnvironmentVariable("TOKEN", EnvironmentValueSource.Unset)
        val empty = literalVariable("TOKEN", "")
        listOf(unset, empty).forEach { variable ->
            val first = environmentSecret("first", listOf(variable))
            CipherDuplicatesCheck.Sensitivity.entries.forEach { sensitivity ->
                assertEquals(1, duplicatesCheck(listOf(first, first.copy(id = "second")), sensitivity).size)
            }
        }
        assertNotDuplicates(
            environmentSecret("first", listOf(unset)),
            environmentSecret("second", listOf(empty)),
        )
    }

    @Test
    fun `environment reference identity includes account item and field without resolving values`() {
        val reference = EnvironmentValueSource.Reference(
            cipherId = "6f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41",
            field = EnvironmentFieldReference.Password,
        )
        val first = environmentSecret("first", listOf(EnvironmentVariable("TOKEN", reference)))
        val second = first.copy(id = "second")
        CipherDuplicatesCheck.Sensitivity.entries.forEach { sensitivity ->
            assertEquals(1, duplicatesCheck(listOf(first, second), sensitivity).size)
        }
        assertNotDuplicates(first, second.copy(accountId = "other-account"))
        val differentSources = listOf(
            reference.copy(cipherId = "7f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41"),
            reference.copy(field = EnvironmentFieldReference.Custom("password")),
            EnvironmentValueSource.Literal(reference.cipherId),
            EnvironmentValueSource.Unset,
        )
        differentSources.forEach { source ->
            assertNotDuplicates(first, environmentSecret("second", listOf(EnvironmentVariable("TOKEN", source))))
        }
        val custom = environmentSecret("custom", listOf(EnvironmentVariable(
            "TOKEN", reference.copy(field = EnvironmentFieldReference.Custom("ApiKey")),
        )))
        assertEquals(1, duplicatesCheck(listOf(custom, custom.copy(id = "copy"))).size)
        assertNotDuplicates(custom, environmentSecret("other", listOf(EnvironmentVariable(
            "TOKEN", reference.copy(field = EnvironmentFieldReference.Custom("apikey")),
        ))))
    }

    @Test
    fun `literal environments can match across accounts`() {
        val first = environmentSecret("first", listOf(literalVariable("TOKEN", "secret")))
        val second = first.copy(id = "second", accountId = "other-account")
        assertEquals(1, duplicatesCheck(listOf(first, second)).size)
    }

    @Test
    fun `invalid empty and unsupported environments never match even identical copies`() {
        val variable = literalVariable("TOKEN", "secret")
        val first = environmentSecret("first", listOf(variable))
        val definitions = listOf(
            emptyList(),
            listOf(variable, variable),
            listOf(variable, variable.copy(source = EnvironmentValueSource.Literal("other"))),
            listOf(literalVariable("INVALID-NAME", "secret")),
            listOf(literalVariable("TOKEN", "secret\u0000")),
            listOf(
                variable,
                EnvironmentVariable("FUTURE", EnvironmentValueSource.Unsupported(JsonPrimitive("future"))),
            ),
        )
        val payloads = definitions.map { environmentPayload(EnvironmentDefinition(it)) } +
            listOf(null, EnvironmentPayload.Unsupported("""{"version":2}"""))
        payloads.forEach { payload ->
            val invalid = first.copy(id = "invalid", environment = payload)
            assertNotDuplicates(first, invalid)
            assertNotDuplicates(invalid, invalid.copy(id = "copy"))
        }
    }

    @Test
    fun `large legacy environment scores stay bounded and do not reject over limit definitions`() {
        val variables = List(EnvironmentLimits.MAX_VARIABLES + 1) { literalVariable("VAR_$it", "value-$it") }
        val first = environmentSecret("first", variables, notes = "Notes")
        val second = environmentSecret("second", variables.reversed(), notes = "Notes")
        val score = duplicatesCheck(listOf(first, second), CipherDuplicatesCheck.Sensitivity.MAX).single().accuracy
        assertTrue(score in -1f..1f)
        assertEquals(1f, score, absoluteTolerance = 0.00001f)

        val longName = environmentSecret("long", listOf(literalVariable(
            "A".repeat(EnvironmentLimits.MAX_VARIABLE_NAME_LENGTH + 1), "value",
        )))
        assertEquals(1, duplicatesCheck(listOf(longName, longName.copy(id = "copy"))).size)
        assertNotDuplicates(first, environmentSecret("small", variables.take(1), notes = "Notes"))
    }

    @Test
    fun `environment duplicates respect archived trashed and ignored exclusions`() {
        val first = environmentSecret("first", listOf(literalVariable("TOKEN", "secret")))
        val excluded = listOf(
            first.copy(id = "archived", archivedDate = TEST_INSTANT),
            first.copy(id = "trashed", deletedDate = TEST_INSTANT),
            first.copy(id = "ignored", ignoredAlerts = mapOf(DWatchtowerAlertType.DUPLICATE to TEST_INSTANT)),
        )
        excluded.forEach { assertNotDuplicates(first, it) }
        assertEquals(1, duplicatesCheck(listOf(first, excluded.first().copy(archivedDate = null))).size)
    }

    @Test
    fun `environment debug summaries and logs contain no variable names values or reference identifiers`() {
        val reference = EnvironmentValueSource.Reference(
            "6f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41", EnvironmentFieldReference.Custom("HiddenField"),
        )
        val first = environmentSecret("first", listOf(
            literalVariable("SENSITIVE_TOKEN", "sensitive-value"),
            EnvironmentVariable("SENSITIVE_REFERENCE", reference),
        ))
        listOf(false, true).forEach { includeDebugSummary ->
            val logs = RecordingLogRepository()
            val checker = CipherDuplicatesCheckImpl(
                cryptoGenerator = DuplicateTestCryptoGenerator,
                base64Service = IdentityBase64Service,
                similarityService = DuplicateTestSimilarityService,
                logRepository = logs,
                includeDebugSummary = includeDebugSummary,
            )
            val group = checker(
                listOf(first, first.copy(id = "copy")),
                CipherDuplicatesCheck.Sensitivity.NORMAL,
            ).single()
            if (includeDebugSummary) {
                assertEquals("environment=${group.accuracy}, ", group.summary)
            } else {
                assertNull(group.summary)
            }
            val output = group.summary.orEmpty() + logs.messages.joinToString()
            listOf("SENSITIVE_TOKEN", "sensitive-value", "SENSITIVE_REFERENCE", reference.cipherId, "HiddenField")
                .forEach { assertFalse(output.contains(it)) }
        }
    }

    private fun assertNotDuplicates(a: DSecret, b: DSecret) {
        CipherDuplicatesCheck.Sensitivity.entries.forEach { sensitivity ->
            assertEquals(emptyList(), duplicatesCheck(listOf(a, b), sensitivity), sensitivity.name)
            assertEquals(emptyList(), duplicatesCheck(listOf(b, a), sensitivity), sensitivity.name)
        }
    }

    private fun duplicatesCheck(
        ciphers: List<DSecret>,
        sensitivity: CipherDuplicatesCheck.Sensitivity = CipherDuplicatesCheck.Sensitivity.NORMAL,
    ) = duplicateChecker(
        ciphers = ciphers,
        sensitivity = sensitivity,
    )
}

private fun literalVariable(name: String, value: String) =
    EnvironmentVariable(name, EnvironmentValueSource.Literal(value))

private fun environmentSecret(
    id: String,
    variables: List<EnvironmentVariable>,
    name: String = "Environment",
    notes: String = "",
) = createSecret(
    id = id,
    name = name,
    notes = notes,
    type = DSecret.Type.Environment,
).copy(
    // Legacy definitions can exceed the write limit, so the document is not encoded here.
    environment = EnvironmentPayload.Decoded(EnvironmentDefinition(variables), document = "", lossless = true),
)

private fun gpgSecret(
    id: String,
    name: String,
    privateKeyArmored: String? = "private-$id",
    publicKeyArmored: String? = "public-$id",
    fingerprint: String? = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
    metadata: GpgAgentKeyMetadata? = null,
) = createSecret(
    id = id,
    name = name,
    accountId = "account",
    type = DSecret.Type.GpgKey,
    gpgKey = DSecret.GpgKey(
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadata = metadata,
    ),
)

private object DuplicateTestCryptoGenerator : TestCryptoGenerator() {
    override fun hashSha256(data: ByteArray): ByteArray = data

    override fun uuid(): String = "duplicate-test-uuid"
}

private object DuplicateTestSimilarityService : SimilarityService {
    override fun score(
        a: String,
        b: String,
    ): Float = if (a == b) 1f else 0f
}
