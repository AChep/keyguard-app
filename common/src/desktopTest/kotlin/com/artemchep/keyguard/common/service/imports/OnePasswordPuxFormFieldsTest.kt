package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.imports.mapping.IMPORT_DEFAULT_FIELD_LABEL
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class OnePasswordPuxFormFieldsTest {
    @Test
    fun `passwords and hidden form fields preserve whitespace`() = runTest {
        for (password in listOf("   ", "\t", "  secret  ", "", null)) {
            for (category in listOf("001", "005")) {
                val value = JsonPrimitive(password)
                val plan = parse(category, """
                    {"designation": "password", "fieldType": "P", "value": $value},
                    {"name": "hidden", "fieldType": "P", "value": $value}
                """.trimIndent())
                val request = plan.items.single().request
                val expected = password?.takeIf { it.isNotEmpty() }
                assertEquals(expected, request.login.password, category)
                assertEquals(expected, request.fields.find { it.name == "hidden" }?.value, category)
                assertEquals(
                    expected,
                    parse(category, "", detailsPassword = password).items.single().request.login.password,
                    category,
                )
            }
        }
    }

    @Test
    fun `checkboxes preserve checked and unchecked states as Boolean fields`() = runTest {
        for (category in listOf("001", "005")) {
            val plan = parse(category, """
                {"name": "terms", "fieldType": "C", "value": ""},
                {"name": "policies", "fieldType": "C", "value": "✓"},
                {"name": "other marker", "fieldType": "C", "value": "on"},
                {"name": "literal false", "fieldType": "C", "value": "false"},
                {"name": "whitespace", "fieldType": "C", "value": " "}
            """.trimIndent())
            assertEquals(
                listOf(
                    DSecret.Field("terms", "false", type = DSecret.Field.Type.Boolean),
                    DSecret.Field("policies", "true", type = DSecret.Field.Type.Boolean),
                    DSecret.Field("other marker", "true", type = DSecret.Field.Type.Boolean),
                    DSecret.Field("literal false", "true", type = DSecret.Field.Type.Boolean),
                    DSecret.Field("whitespace", "true", type = DSecret.Field.Type.Boolean),
                ),
                plan.items.single().request.fields,
                category,
            )
        }
    }

    @Test
    fun `checkboxes omit unknown states and use fallback names`() = runTest {
        for (category in listOf("001", "005")) {
            val plan = parse(category, """
                {"name": "missing value", "fieldType": "C"},
                {"name": "null value", "fieldType": "C", "value": null},
                {"name": " ", "fieldType": "C", "value": ""},
                {"fieldType": "C", "value": "✓"}
            """.trimIndent())
            assertEquals(
                listOf(
                    DSecret.Field(IMPORT_DEFAULT_FIELD_LABEL, "false", type = DSecret.Field.Type.Boolean),
                    DSecret.Field(IMPORT_DEFAULT_FIELD_LABEL, "true", type = DSecret.Field.Type.Boolean),
                ),
                plan.items.single().request.fields,
                category,
            )
        }
    }

    @Test
    fun `form fields retain text hidden and designated credential mappings`() = runTest {
        for (category in listOf("001", "005")) {
            val plan = parse(category, """
                {"name": "username", "fieldType": "T", "designation": "username", "value": "alice"},
                {"name": "password", "fieldType": "P", "designation": "password", "value": "secret"},
                {"name": "text", "fieldType": "T", "value": "✓"},
                {"name": "hidden", "fieldType": "P", "value": "hidden secret"},
                {"name": "empty text", "fieldType": "T", "value": ""},
                {"name": "empty hidden", "fieldType": "P", "value": ""}
            """.trimIndent())
            val request = plan.items.single().request
            assertEquals(DSecret.Type.Login, request.type, category)
            assertEquals("alice", request.login.username, category)
            assertEquals("secret", request.login.password, category)
            assertEquals(
                listOf(
                    DSecret.Field("text", "✓", type = DSecret.Field.Type.Text),
                    DSecret.Field("hidden", "hidden secret", type = DSecret.Field.Type.Hidden),
                ),
                request.fields,
                category,
            )
        }
    }

    private suspend fun parse(
        category: String,
        fields: String,
        detailsPassword: String? = null,
    ): ImportPlan {
        val data = """
            {"accounts": [{"vaults": [{"items": [{
              "categoryUuid": "$category", "overview": {"title": "Form fields"},
              "details": {"loginFields": [$fields], "password": ${JsonPrimitive(detailsPassword)}}
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
        return assertIs<ImportResult.Success>(result).plan.also { plan ->
            plan.assertVaultStorable()
            assertTrue(plan.skips.isEmpty, plan.skips.toString())
        }
    }
}
