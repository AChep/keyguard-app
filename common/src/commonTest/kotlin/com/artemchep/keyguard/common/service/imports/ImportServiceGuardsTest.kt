package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_JSON_MAX_DEPTH
import com.artemchep.keyguard.common.service.imports.impl.ImportJsonTooDeepException
import com.artemchep.keyguard.common.service.imports.impl.ImporterImportService
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.decodeFromString
import com.artemchep.keyguard.common.service.imports.impl.importJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement

/**
 * The guards an importer cannot opt out of: the nesting bound of the JSON
 * parser, and the attachments of a plan having an extractor.
 */
class ImportServiceGuardsTest {
    @Test
    fun `the import parser bounds the nesting of every text it reads`() {
        val shallow = nested(IMPORT_JSON_MAX_DEPTH)
        importJson.parseToJsonElement(shallow)

        val deep = nested(IMPORT_JSON_MAX_DEPTH + 1)
        assertFailsWith<ImportJsonTooDeepException> {
            importJson.parseToJsonElement(deep)
        }
        assertFailsWith<ImportJsonTooDeepException> {
            importJson.decodeFromString<JsonElement>(deep)
        }
    }

    @Test
    fun `json nested too deep inside a csv cell is kept as text`() = runTest {
        val cell = nested(IMPORT_JSON_MAX_DEPTH + 1)
        val plan = parseImportPlan(
            format = ImportFormatId.NordPass,
            text = """
                name,url,username,password,type,custom_fields
                Site,https://site.example.com,ana,s3cret,password,$cell
            """.trimIndent(),
        )
        assertEquals(
            listOf(DSecret.Field("Custom fields", cell, type = DSecret.Field.Type.Text)),
            plan.items.single().request.fields,
        )
    }

    @Test
    fun `a plan with attachments its importer cannot extract fails the parse`() = runTest {
        val service = ImporterImportService(
            importers = listOf(AttachmentPlanningImporter()),
        )
        assertFailsWith<IllegalStateException> {
            service.parse(
                format = ImportFormatId.Chromium,
                input = BytesImportInput(""),
                now = IMPORT_TEST_NOW,
            )
        }
    }

    @Test
    fun `extracting attachments of a format without any fails with an explicit error`() = runTest {
        assertFailsWith<IllegalStateException> {
            importTestService().extractAttachments(
                format = ImportFormatId.Chromium,
                input = BytesImportInput(""),
                password = null,
                refs = setOf("a"),
            ) { _, _ -> error("Not expected to be written to.") }
        }
    }
}

private fun nested(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

/**
 * Plans an attachment without implementing the extractor that reads it.
 */
private class AttachmentPlanningImporter : Importer {
    override val format = ImportFormat(
        id = ImportFormatId.Chromium,
        title = "Broken",
        extensions = emptyList(),
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult = ImportResult.Success(
        plan = ImportPlan(
            source = ImportPlan.Source(),
            folders = emptyList(),
            items = listOf(
                ImportPlan.Item(
                    request = CreateRequest(
                        title = "Doc",
                        type = DSecret.Type.SecureNote,
                        now = now,
                    ),
                    folderKey = null,
                    attachments = listOf(
                        ImportPlan.Attachment(
                            ref = "a",
                            name = "a.txt",
                            size = 1,
                        ),
                    ),
                ),
            ),
            skips = importSkips(),
        ),
    )
}
