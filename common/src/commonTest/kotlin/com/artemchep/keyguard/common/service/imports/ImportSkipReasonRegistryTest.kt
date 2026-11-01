package com.artemchep.keyguard.common.service.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The skip reasons only a file importer raises. `CxfSkipReasonRegistryTest`
 * proves every other reason.
 */
internal val FILE_IMPORT_ONLY_SKIP_REASONS = setOf(
    ImportSkipReason.Attachment,
    ImportSkipReason.PasswordHistory,
    ImportSkipReason.File,
    ImportSkipReason.MalformedRecord,
)

class ImportSkipReasonRegistryTest {
    private val fixtures: Map<ImportSkipReason, Pair<ImportFormatId, String>> = mapOf(
        ImportSkipReason.MalformedRecord to (
            ImportFormatId.Chromium to
                "name,url,username,password\nfragment\n"
            ),
        ImportSkipReason.Attachment to (
            ImportFormatId.Bitwarden to
                """{"items": [{"type": 2, "name": "Doc", "attachments": [{"id": "a", "fileName": "a.txt"}]}]}"""
            ),
        ImportSkipReason.PasswordHistory to (
            ImportFormatId.Bitwarden to
                """{"items": [{"type": 1, "name": "Site", "passwordHistory": [{"password": "old"}]}]}"""
            ),
    )

    @Test
    fun `every file-only skip reason is produced by a fixture`() = runTest {
        // File requires an archive; DashlaneArchiveImporterTest covers its producer.
        assertEquals(FILE_IMPORT_ONLY_SKIP_REASONS - ImportSkipReason.File, fixtures.keys)
        fixtures.forEach { (reason, fixture) ->
            val (format, text) = fixture
            val plan = parseImportPlan(format, text)
            assertTrue(
                plan.skips[reason] > 0,
                "$reason was not raised by its fixture; tally was ${plan.skips}",
            )
        }
    }
}
