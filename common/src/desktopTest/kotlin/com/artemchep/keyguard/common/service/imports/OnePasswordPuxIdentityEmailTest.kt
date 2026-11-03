package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OnePasswordPuxIdentityEmailTest {
    @Test
    fun `identity takes typed email regardless of field id or value shape`() = runTest {
        val values = listOf(
            """{"email": {"email_address": "alice@example.org"}}""",
            """{"email": "alice@example.org"}""",
        )
        for (id in listOf("E-mail", "courriel", "generated-id")) {
            for (value in values) {
                val request = parse(
                    fields = """{"id": "$id", "title": "Email", "value": $value}""",
                )
                assertEquals(DSecret.Type.Identity, request.type)
                assertEquals("alice@example.org", request.identity.email, "$id: $value")
                assertTrue(request.fields.isEmpty(), request.fields.toString())
            }
        }
    }

    @Test
    fun `template email takes priority over an earlier typed alternative`() = runTest {
        for (value in listOf(
            """{"string": "primary@example.org"}""",
            """{"email": {"email_address": "primary@example.org"}}""",
        )) {
            val request = parse(
                fields = """
                    {"id": "E-mail", "title": "Alternative", "value": {"email": "other@example.org"}},
                    {"id": "email", "title": "Primary", "value": $value}
                """.trimIndent(),
            )
            assertEquals("primary@example.org", request.identity.email)
            assertEquals(
                listOf(DSecret.Field("Alternative", "other@example.org", type = DSecret.Field.Type.Text)),
                request.fields,
            )
        }
    }

    @Test
    fun `fallback skips blank candidates and preserves additional emails`() = runTest {
        val request = parse(
            fields = """
                {"id": "email", "title": "Empty template", "value": {"string": " "}},
                {"id": "empty", "title": "Empty typed", "value": {"email": {"email_address": ""}}},
                {"id": "blank", "title": "Blank typed", "value": {"email": " "}},
                {"id": "missing", "title": "Missing address", "value": {"email": {}}},
                {"id": "first", "title": "Primary", "value": {"email": {"email_address": "first@example.org"}}},
                {"id": "second", "title": "Backup", "value": {"email": "second@example.org"}},
                {"id": "other", "title": "Other field", "value": {"string": "preserved"}}
            """.trimIndent(),
        )
        assertEquals("first@example.org", request.identity.email)
        assertEquals(
            listOf(
                DSecret.Field("Backup", "second@example.org", type = DSecret.Field.Type.Text),
                DSecret.Field("Other field", "preserved", type = DSecret.Field.Type.Text),
            ),
            request.fields,
        )
    }

    @Test
    fun `identity without a nonblank email does not promote ordinary text`() = runTest {
        val request = parse(
            fields = """
                {"id": "email", "title": "Empty template", "value": {"string": ""}},
                {"id": "blank", "title": "Blank typed", "value": {"email": " "}},
                {"id": "E-mail", "title": "Email", "value": {"string": "custom@example.org"}}
            """.trimIndent(),
        )
        assertNull(request.identity.email)
        assertEquals(
            listOf(DSecret.Field("Email", "custom@example.org", type = DSecret.Field.Type.Text)),
            request.fields,
        )
    }

    @Test
    fun `nonidentity items retain typed emails as custom fields`() = runTest {
        for (category in listOf("001", "002", "003", "005", "006")) {
            val request = parse(
                category = category,
                fields = """
                    {"id": "E-mail", "title": "Email", "value": {"email": {"email_address": "alice@example.org"}}}
                """.trimIndent(),
            )
            assertNull(request.identity.email, category)
            assertEquals(
                listOf(DSecret.Field("Email", "alice@example.org", type = DSecret.Field.Type.Text)),
                request.fields,
                category,
            )
        }
    }

    private suspend fun parse(
        fields: String,
        category: String = "004",
    ): CreateRequest {
        val data = """
            {"accounts": [{"vaults": [{"items": [{
              "categoryUuid": "$category", "overview": {"title": "Email fields"},
              "details": {"sections": [{"fields": [$fields]}]}
            }]}]}]}
        """.trimIndent()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("export.data"))
            zip.write(data.encodeToByteArray())
            zip.closeEntry()
        }
        val result = importTestService().parse(
            format = ImportFormatId.OnePassword,
            input = BytesImportInput(output.toByteArray()),
            now = IMPORT_TEST_NOW,
        )
        val plan = assertIs<ImportResult.Success>(result).plan
        plan.assertVaultStorable()
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        return plan.items.single().request
    }
}
