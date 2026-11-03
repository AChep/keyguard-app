package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.importSkips
import com.artemchep.keyguard.test.FakeSshKeyImportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The `parse` gate as one truth table: which documents are refused, and with
 * which error.
 *
 * The subtle part is the **precedence**: the `accounts` check is evaluated in
 * the same expression as the version check and wins, so a document that is both
 * an unsupported version *and* structurally broken reports `Parse`, never
 * `UnsupportedVersion`.
 */
class CxfImportDocumentGateTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    private data class GateCase(
        val name: String,
        val payload: String,
        /** `null` means the document is accepted. */
        val expected: ImportError?,
    )

    private fun assertCases(cases: List<GateCase>) {
        cases.forEach { case ->
            val result = service.parse(payload = case.payload, now = now)
            when (val expected = case.expected) {
                null -> assertIs<ImportResult.Success>(result, case.name)
                else -> assertEquals(
                    expected,
                    assertIs<ImportResult.Failure>(result, case.name).error,
                    case.name,
                )
            }
        }
    }

    // region The version member

    private val versionCases = listOf(
        GateCase("1.0 is the current version", documentWithVersion("""{"major": 1, "minor": 0}"""), null),
        GateCase("any 1.x minor is additive", documentWithVersion("""{"major": 1, "minor": 99}"""), null),
        GateCase("a negative 1.x minor still parses", documentWithVersion("""{"major": 1, "minor": -1}"""), null),
        GateCase(
            // The legacy carve-out: some providers emitted 0.0 for payloads that
            // already follow the 1.0 shape.
            name = "0.0 is accepted as legacy",
            payload = documentWithVersion("""{"major": 0, "minor": 0}"""),
            expected = null,
        ),
        GateCase(
            name = "0.1 is not the legacy carve-out",
            payload = documentWithVersion("""{"major": 0, "minor": 1}"""),
            expected = ImportError.UnsupportedVersion,
        ),
        GateCase(
            name = "a future major is refused",
            payload = documentWithVersion("""{"major": 2, "minor": 0}"""),
            expected = ImportError.UnsupportedVersion,
        ),
        GateCase(
            // A quoted number decodes as a number, so a sloppy exporter's `"1"`
            // still imports. Pinned as tolerance rather than a defect.
            name = "a quoted numeric version is tolerated",
            payload = documentWithVersion("""{"major": "1", "minor": "0"}"""),
            expected = null,
        ),
        GateCase(
            name = "a negative major is refused",
            payload = documentWithVersion("""{"major": -1, "minor": 0}"""),
            expected = ImportError.UnsupportedVersion,
        ),
    )

    @Test
    fun `the version gate accepts one major and one legacy pair`() {
        assertCases(versionCases)
    }

    private val malformedVersionCases = listOf(
        GateCase("an absent version", documentWithoutVersion(), ImportError.Parse),
        GateCase("a version given as a string", documentWithVersion(""""1.0""""), ImportError.Parse),
        GateCase("a version missing its minor", documentWithVersion("""{"major": 1}"""), ImportError.Parse),
        GateCase(
            name = "a version whose major is not numeric at all",
            payload = documentWithVersion("""{"major": "one", "minor": 0}"""),
            expected = ImportError.Parse,
        ),
        GateCase("a null version", documentWithVersion("null"), ImportError.Parse),
        GateCase("a version given as an array", documentWithVersion("[1, 0]"), ImportError.Parse),
    )

    @Test
    fun `a version it cannot even read is a parse failure, not an unsupported one`() {
        assertCases(malformedVersionCases)
    }

    // endregion

    // region The accounts member and the root

    private val structureCases = listOf(
        GateCase("an absent accounts member", documentWithoutAccounts(), ImportError.Parse),
        // §2.1.2 makes `[]` the only conforming spelling of an empty required
        // array, so absent, null and non-array are one violation — the same rule
        // an account's `items` member gets in `CxfImportSkipCounterMatrixTest`,
        // only there it costs one account instead of the whole document.
        GateCase("accounts given as an object", documentWithAccountsMember("{}"), ImportError.Parse),
        GateCase("accounts given as a string", documentWithAccountsMember(""""x""""), ImportError.Parse),
        GateCase("a null accounts member", documentWithAccountsMember("null"), ImportError.Parse),
        GateCase("an empty accounts array", documentWithAccountsMember("[]"), null),
        GateCase("a root that is an array", "[]", ImportError.Parse),
        GateCase("a root that is a string", """"nope"""", ImportError.Parse),
        GateCase("an empty payload", "", ImportError.Parse),
        GateCase("a payload that is not json", "not json at all", ImportError.Parse),
    )

    @Test
    fun `the structure gate requires an accounts array`() {
        assertCases(structureCases)
    }

    @Test
    fun `the structure check wins over the version check`() {
        val result = service.parse(
            payload = """
                {
                  "version": {"major": 2, "minor": 0},
                  "exporterRpId": "com.example.exporter",
                  "exporterDisplayName": "Example Exporter",
                  "timestamp": 1706623773
                }
            """.trimIndent(),
            now = now,
        )
        assertEquals(ImportError.Parse, assertIs<ImportResult.Failure>(result).error)
    }

    // endregion

    @Test
    fun `an empty document parses to an empty plan`() {
        val plan = service.parseSuccessPlan(
            payload = """
                {
                  "version": {"major": 1, "minor": 0},
                  "timestamp": 1706623773,
                  "accounts": []
                }
            """.trimIndent(),
            now = now,
        )
        assertEquals(0, plan.sourceAccountCount)
        assertTrue(plan.folders.isEmpty())
        assertTrue(plan.items.isEmpty())
        assertEquals(importSkips(), plan.skips)
        // Both exporter strings are optional on the wire and fall back to null.
        assertNull(plan.source.identifier)
        assertNull(plan.source.displayName)
    }

    @Test
    fun `a non-primitive exporter name degrades to null`() {
        val plan = service.parseSuccessPlan(
            payload = """
                {
                  "version": {"major": 1, "minor": 0},
                  "exporterRpId": {"nested": "object"},
                  "exporterDisplayName": ["array"],
                  "timestamp": 1706623773,
                  "accounts": []
                }
            """.trimIndent(),
            now = now,
        )
        assertNull(plan.source.identifier)
        assertNull(plan.source.displayName)
    }
}

private fun documentWithVersion(versionJson: String): String = """
    {
      "version": $versionJson,
      "exporterRpId": "com.example.exporter",
      "exporterDisplayName": "Example Exporter",
      "timestamp": 1706623773,
      "accounts": []
    }
""".trimIndent()

private fun documentWithoutVersion(): String = """
    {
      "exporterRpId": "com.example.exporter",
      "exporterDisplayName": "Example Exporter",
      "timestamp": 1706623773,
      "accounts": []
    }
""".trimIndent()

private fun documentWithAccountsMember(accountsJson: String): String = """
    {
      "version": {"major": 1, "minor": 0},
      "exporterRpId": "com.example.exporter",
      "exporterDisplayName": "Example Exporter",
      "timestamp": 1706623773,
      "accounts": $accountsJson
    }
""".trimIndent()

private fun documentWithoutAccounts(): String = """
    {
      "version": {"major": 1, "minor": 0},
      "exporterRpId": "com.example.exporter",
      "exporterDisplayName": "Example Exporter",
      "timestamp": 1706623773
    }
""".trimIndent()
