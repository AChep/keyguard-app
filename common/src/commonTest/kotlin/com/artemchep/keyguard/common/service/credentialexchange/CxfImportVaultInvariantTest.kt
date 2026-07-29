package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Guards the seam between an import plan and the vault write.
 *
 * `AddCipher` validates every field while it builds the cipher models, which
 * happens *before* the database transaction opens — so a single request the
 * vault refuses does not fail one item, it fails the entire import and leaves
 * the already-created folders behind. None of that validation is reachable
 * from a unit test (it needs a database), so the invariants are restated here
 * and asserted against a parsed plan.
 *
 * Keep this list in sync with the `require` calls in
 * `com.artemchep.keyguard.provider.bitwarden.usecase.AddCipher`.
 */
class CxfImportVaultInvariantTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    /**
     * Asserts what `AddCipher` asserts: a field must be named, a text or
     * hidden field must have a value, a boolean field's value must survive
     * `toBooleanStrict`, and a linked field must carry a linked id.
     */
    private fun assertVaultStorable(request: CreateRequest) {
        request.fields.forEach { field ->
            assertNotNull(field.name, "a field with no name fails the whole import")
            when (field.type) {
                DSecret.Field.Type.Text,
                DSecret.Field.Type.Hidden,
                -> assertNotNull(field.value, "field ${field.name} has no value")

                DSecret.Field.Type.Boolean -> {
                    val value = assertNotNull(field.value, "field ${field.name} has no value")
                    assertTrue(
                        value == "true" || value == "false",
                        "field ${field.name} holds \"$value\", which toBooleanStrict rejects",
                    )
                }

                DSecret.Field.Type.Linked ->
                    assertNotNull(field.linkedId, "field ${field.name} has no linked id")
            }
        }
    }

    @Test
    fun `fields the vault would reject stay storable`() {
        // Every member here is spec-valid yet sits on an `AddCipher` edge: an
        // absent `label`, and boolean spellings other exporters emit that
        // `toBooleanStrict` refuses.
        val plan = service.parseSuccessPlan(
            payload = hostileFieldDocument(),
            now = now,
        )
        val request = plan.items.single().request
        assertVaultStorable(request)
        // Nothing was dropped to achieve it.
        assertEquals(5, request.fields.size)
    }
}

/**
 * A spec-valid document whose custom fields sit on every edge of Keyguard's
 * own field constraints.
 */
private fun hostileFieldDocument(): String = """
    {
      "version": {"major": 1, "minor": 0},
      "exporterRpId": "com.example.exporter",
      "exporterDisplayName": "Example Exporter",
      "timestamp": 1706623773,
      "accounts": [
        {
          "id": "YWNjLTE",
          "username": "Alice Example",
          "email": "alice@example.com",
          "collections": [],
          "items": [
            {
              "id": "aXRlbTAx",
              "title": "Fields",
              "credentials": [
                {
                  "type": "custom-fields",
                  "fields": [
                    {"fieldType": "string", "value": "no label at all"},
                    {"fieldType": "boolean", "value": "1"},
                    {"fieldType": "boolean", "value": "yes", "label": "Enabled"},
                    {"fieldType": "boolean", "value": "TRUE", "label": "Shouty"},
                    {"fieldType": "concealed-string", "value": "s3cr3t", "label": "Token"}
                  ]
                }
              ]
            }
          ]
        }
      ]
    }
""".trimIndent()
