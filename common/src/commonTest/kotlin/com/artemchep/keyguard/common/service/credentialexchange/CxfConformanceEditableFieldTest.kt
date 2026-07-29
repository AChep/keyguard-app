package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.DEFAULT_FIELD_LABEL
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json

/**
 * Conformance coverage for CXF v1.0 §3.4.2 EditableField and the §3.4.3
 * FieldType enum (cxf-v1.0-ps-errata-20260309): every spec field type imports,
 * the exporter only ever emits spec field-type strings, and the documented
 * deviations from the unknown-enum rules are pinned.
 */
class CxfConformanceEditableFieldTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val importService = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    private val exportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    private fun importField(
        fieldType: String,
        value: String = "example value",
    ): DSecret.Field {
        val plan = importService.parseSuccessPlan(
            payload = customFieldDocument(fieldType = fieldType, value = value),
            now = now,
        )
        return plan.items.single().request.fields.single()
    }

    @Test
    fun `each spec field type imports to its keyguard field type`() {
        // The full FieldType enum of §3.4.3; only concealed-string and boolean
        // have dedicated Keyguard counterparts, every other kind is text.
        val expected = mapOf(
            "string" to DSecret.Field.Type.Text,
            "concealed-string" to DSecret.Field.Type.Hidden,
            "email" to DSecret.Field.Type.Text,
            "number" to DSecret.Field.Type.Text,
            "boolean" to DSecret.Field.Type.Boolean,
            "date" to DSecret.Field.Type.Text,
            "year-month" to DSecret.Field.Type.Text,
            "wifi-network-security-type" to DSecret.Field.Type.Text,
            "country-code" to DSecret.Field.Type.Text,
            "subdivision-code" to DSecret.Field.Type.Text,
        )
        expected.forEach { (fieldType, keyguardType) ->
            val value = if (fieldType == "boolean") "true" else "example value"
            val field = importField(fieldType = fieldType, value = value)
            assertEquals(keyguardType, field.type, "fieldType: $fieldType")
            assertEquals(value, field.value, "fieldType: $fieldType")
        }
    }

    @Test
    fun `an unknown field type is kept as text`() {
        // Deviation from CXF v1.0 §3.4.2, pinned deliberately: its opening
        // paragraph wants an unknown value in the required `fieldType` member to
        // drop the enclosing EditableField, while its `fieldType` description
        // says to default unknown values to string. Carrying the value as plain
        // text is the reading that cannot misinterpret or lose user data.
        val field = importField(fieldType = "otp-secret")
        assertEquals(DSecret.Field.Type.Text, field.type)
        assertEquals("example value", field.value)
    }

    @Test
    fun `a field label becomes the field name and the field id is ignored`() {
        val plan = importService.parseSuccessPlan(
            payload = customFieldDocument(
                fieldType = "string",
                value = "example value",
                extraFieldMembers = """, "id": "ZmllbGQtMQ", "label": "Website"""",
            ),
            now = now,
        )
        val field = plan.items.single().request.fields.single()
        assertEquals("Website", field.name)
    }

    @Test
    fun `a field without a label imports with a generic name`() {
        // `label` is optional in §3.4.2, but Keyguard refuses to store a nameless
        // field while building the cipher — a null name would fail the whole
        // import transaction rather than this one field.
        val field = importField(fieldType = "string")
        assertEquals(DEFAULT_FIELD_LABEL, field.name)
    }

    @Test
    fun `a boolean field keeps a canonical value and its boolean type`() {
        listOf("true", "false", "TRUE", " False ").forEach { value ->
            val field = importField(fieldType = "boolean", value = value)
            assertEquals(DSecret.Field.Type.Boolean, field.type, "value: $value")
            assertEquals(value.trim().lowercase(), field.value, "value: $value")
        }
    }

    @Test
    fun `a boolean field with an unparseable value degrades to text`() {
        // Keyguard stores booleans as exactly `true`/`false` and rejects
        // anything else while creating the cipher, which would take the whole
        // import down. Other exporters legitimately emit these, so the value
        // is carried as text rather than dropped.
        listOf("1", "0", "yes", "no", "example value").forEach { value ->
            val field = importField(fieldType = "boolean", value = value)
            assertEquals(DSecret.Field.Type.Text, field.type, "value: $value")
            assertEquals(value, field.value, "value: $value")
        }
    }

    // A vault touching every field-type-emitting export mapper: basic-auth,
    // credit-card (year-month), the identity split (email, country-code,
    // subdivision-code, concealed-string) and the three custom-field kinds.
    private val maximalVaultCiphers = listOf(
        cxfLoginSecret(
            login = DSecret.Login(
                username = "alice@example.com",
                password = "s3cr3t",
                totp = cxfTotpAuth(),
            ),
        ),
        cxfSecret(
            id = "card-1",
            name = "Card",
            type = DSecret.Type.Card,
            card = DSecret.Card(
                cardholderName = "Alice Example",
                brand = "Visa",
                number = "4111",
                code = "123",
                expMonth = "5",
                expYear = "2027",
            ),
        ),
        cxfSecret(
            id = "identity-1",
            name = "Identity",
            type = DSecret.Type.Identity,
            identity = DSecret.Identity(
                title = "Dr",
                firstName = "Alice",
                lastName = "Example",
                address1 = "1 Main St",
                city = "Springfield",
                state = "OR",
                postalCode = "97477",
                country = "US",
                company = "Acme",
                email = "alice@example.com",
                phone = "555-0100",
                ssn = "078-05-1120",
                username = "acme",
                passportNumber = "P123456",
                licenseNumber = "DL123456",
            ),
        ),
        cxfSecret(
            id = "fields-1",
            name = "Fields",
            type = DSecret.Type.SecureNote,
            notes = "hi",
            fields = listOf(
                DSecret.Field(
                    name = "Text",
                    value = "v",
                    type = DSecret.Field.Type.Text,
                ),
                DSecret.Field(
                    name = "Hidden",
                    value = "v",
                    type = DSecret.Field.Type.Hidden,
                ),
                DSecret.Field(
                    name = "Boolean",
                    value = "true",
                    type = DSecret.Field.Type.Boolean,
                ),
            ),
        ),
    )

    private fun encodeDocument(ciphers: List<DSecret>): String {
        val document = exportService.buildDocument(
            accounts = listOfNotNull(
                exportService.buildAccount(
                    profile = cxfProfile(),
                    ciphers = ciphers,
                    allowedTypes = CxfCredentialType.ALL,
                ),
            ),
            exporterRpId = "com.example.importer",
            exporterDisplayName = "Example Importer",
            timestamp = now,
        )
        return exportService.encode(document)
    }

    @Test
    fun `the exporter only emits spec field type strings`() {
        val specFieldTypes = setOf(
            "string",
            "concealed-string",
            "email",
            "number",
            "boolean",
            "date",
            "year-month",
            "wifi-network-security-type",
            "country-code",
            "subdivision-code",
        )
        val encoded = encodeDocument(maximalVaultCiphers)
        val emitted = collectJsonMembers(Json.parseToJsonElement(encoded), "fieldType")
            .map { it.content }
        assertTrue(emitted.isNotEmpty())
        val offenders = emitted.filter { it !in specFieldTypes }
        assertTrue(
            offenders.isEmpty(),
            "non-spec fieldType values emitted: $offenders",
        )
    }

    @Test
    fun `year-month values keep the spec YYYY-MM shape on export`() {
        fun encodedCard(expMonth: String, expYear: String): String = encodeDocument(
            listOf(
                cxfSecret(
                    id = "card-1",
                    name = "Card",
                    type = DSecret.Type.Card,
                    card = DSecret.Card(
                        number = "4111",
                        expMonth = expMonth,
                        expYear = expYear,
                    ),
                ),
            ),
        )
        // §3.4.3: year-month is `YYYY-MM` — four-digit zero-padded year,
        // two-digit zero-padded month.
        assertTrue(encodedCard(expMonth = "6", expYear = "645").contains("\"0645-06\""))
        // Two-digit years shift into 2000-2099 instead of being zero-padded
        // into ancient dates.
        assertTrue(encodedCard(expMonth = "12", expYear = "25").contains("\"2025-12\""))
    }
}

/**
 * A minimal valid CXF v1.0 document with a single custom-fields item carrying
 * one editable field of the given [fieldType].
 */
private fun customFieldDocument(
    fieldType: String,
    value: String,
    extraFieldMembers: String = "",
): String = """
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
                    {"fieldType": "$fieldType", "value": "$value"$extraFieldMembers}
                  ]
                }
              ]
            }
          ]
        }
      ]
    }
""".trimIndent()
