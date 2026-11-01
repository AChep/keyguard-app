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
import kotlinx.coroutines.test.runTest

/**
 * A card expiry date the vault cannot hold is kept as a field, as written.
 */
class ImportCardExpiryTest {
    @Test
    fun `proton pass keeps an unreadable expiry date as a field`() = runTest {
        val request = parseProtonCard(expiry = "sometime 2031")
        assertNull(request.card.expMonth)
        assertNull(request.card.expYear)
        assertEquals(
            listOf(
                DSecret.Field("PIN", "0000", type = DSecret.Field.Type.Hidden),
                DSecret.Field("Expiry date", "sometime 2031", type = DSecret.Field.Type.Text),
            ),
            request.fields,
        )
    }

    @Test
    fun `proton pass reads a valid expiry date into the card`() = runTest {
        val request = parseProtonCard(expiry = "2031-01")
        assertEquals("1", request.card.expMonth)
        assertEquals("2031", request.card.expYear)
        assertEquals(listOf("PIN"), request.fields.map { it.name })
    }

    @Test
    fun `1password keeps an unreadable expiry and valid-from date as fields`() = runTest {
        val request = parseOnePasswordCard(expiry = "203013", validFrom = "\"soon\"")
        assertNull(request.card.expMonth)
        assertNull(request.card.expYear)
        assertEquals(
            listOf(
                DSecret.Field("Expiry date", "203013", type = DSecret.Field.Type.Text),
                DSecret.Field("Valid from", "soon", type = DSecret.Field.Type.Text),
            ),
            request.fields,
        )
    }

    @Test
    fun `1password reads a valid expiry date into the card`() = runTest {
        val request = parseOnePasswordCard(expiry = "203012", validFrom = "202401")
        assertEquals("12", request.card.expMonth)
        assertEquals("2030", request.card.expYear)
        assertEquals(
            listOf(DSecret.Field("Valid from", "01/2024", type = DSecret.Field.Type.Text)),
            request.fields,
        )
    }

    private suspend fun parseProtonCard(
        expiry: String,
    ): CreateRequest {
        val plan = parseImportPlan(
            ImportFormatId.ProtonPass,
            """
                {"vaults": {"v": {"name": "Personal", "items": [
                  {"data": {"type": "creditCard", "metadata": {"name": "Visa"}, "content": {
                    "number": "4111111111111111", "expirationDate": "$expiry", "pin": "0000"}}}
                ]}}}
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Card, request.type)
        return request
    }

    private suspend fun parseOnePasswordCard(
        expiry: String,
        validFrom: String,
    ): CreateRequest {
        val data = """
            {"accounts": [{"vaults": [{"items": [{
              "categoryUuid": "002", "overview": {"title": "Visa"},
              "details": {"sections": [{"fields": [
                {"title": "number", "id": "ccnum", "value": {"creditCardNumber": "4111111111111111"}},
                {"title": "expiry date", "id": "expiry", "value": {"monthYear": $expiry}},
                {"title": "valid from", "id": "validFrom", "value": {"monthYear": $validFrom}}
              ]}]}
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
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Card, request.type)
        assertEquals("4111111111111111", request.card.number)
        return request
    }
}
