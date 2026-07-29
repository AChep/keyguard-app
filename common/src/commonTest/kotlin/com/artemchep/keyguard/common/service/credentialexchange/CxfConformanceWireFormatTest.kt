package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Conformance coverage for the CXF v1.0 wire format — the §2 encoding rules,
 * the §3.1 header and the §1.3 identifier rules (cxf-v1.0-ps-errata-20260309):
 * unpadded base64url everywhere, required arrays present when empty, optional
 * arrays absent when empty, and id size limits. The integral epoch-second
 * timestamps are pinned by the golden vector, which compares them as a parsed
 * tree.
 */
class CxfConformanceWireFormatTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    private val identitySecret = cxfSecret(
        name = "Example Identity",
        favorite = false,
        tags = emptyList(),
        type = DSecret.Type.Identity,
        identity = DSecret.Identity(
            title = "Dr",
            firstName = "Alice",
            middleName = "Betty",
            lastName = "Example",
            address1 = "1 Main St",
            address2 = "Apt 2",
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
    )

    private fun buildDocument(
        ciphers: List<DSecret>,
        folders: List<com.artemchep.keyguard.common.model.DFolder> = emptyList(),
        profileName: String = "Alice Example",
    ): CxfDocument = service.buildDocument(
        accounts = listOfNotNull(
            service.buildAccount(
                profile = cxfProfile(name = profileName),
                ciphers = ciphers,
                allowedTypes = CxfCredentialType.ALL,
                folders = folders,
            ),
        ),
        exporterRpId = "com.example.importer",
        exporterDisplayName = "Example Importer",
        timestamp = now,
    )

    private fun identityDocument() = buildDocument(ciphers = listOf(identitySecret))

    private fun folderedDocument() = buildDocument(
        ciphers = listOf(
            cxfSecret(
                name = "Example Note",
                favorite = false,
                tags = emptyList(),
                notes = "hi",
                folderId = "f1",
                type = DSecret.Type.SecureNote,
            ),
        ),
        folders = listOf(cxfFolder(id = "f1", name = "Work")),
    )

    @Test
    fun `identity golden vector encodes to the expected json element`() {
        val encoded = service.encode(identityDocument())
        assertEquals(
            Json.parseToJsonElement(IDENTITY_VECTOR_JSON),
            Json.parseToJsonElement(encoded),
        )
    }

    @Test
    fun `optional empty arrays are omitted from the payload`() {
        // §2.1.2: an optional array member MUST NOT be present when empty —
        // the mirror rule of required-arrays-present, which
        // CxfExportServiceTest pins.
        val root = Json.parseToJsonElement(service.encode(folderedDocument())).jsonObject
        val account = root["accounts"]!!.jsonArray.single().jsonObject
        val item = account["items"]!!.jsonArray.single().jsonObject
        assertFalse("tags" in item, "empty tags must be omitted")
        assertFalse("scope" in item, "an empty scope must be omitted")
        val collection = account["collections"]!!.jsonArray.single().jsonObject
        assertFalse("subCollections" in collection, "empty subCollections must be omitted")
    }

    @Test
    fun `a urls-only scope still carries an empty androidApps array`() {
        // Both scope members are required arrays, so a web-only item must
        // still emit `androidApps` (§2.1.2).
        val document = buildDocument(
            ciphers = listOf(
                cxfLoginSecret(
                    uris = listOf(DSecret.Uri(uri = "https://example.com")),
                    login = DSecret.Login(username = "alice", password = "s3cr3t"),
                ),
            ),
        )
        val root = Json.parseToJsonElement(service.encode(document)).jsonObject
        val item = root["accounts"]!!.jsonArray.single().jsonObject
            .get("items")!!.jsonArray.single().jsonObject
        val scope = item["scope"]!!.jsonObject
        assertTrue(scope["androidApps"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `every exported id decodes to at most 64 bytes and is unique`() {
        listOf(identityDocument(), folderedDocument()).forEach { document ->
            val encoded = service.encode(document)
            // Every §2.1 b64url member is emitted unpadded. None of the
            // controlled fixture values contains a literal '=', so any '=' in
            // the output would be encoder padding.
            assertFalse(encoded.contains('='), "padded base64url in the payload")
            val ids = collectJsonMembers(
                Json.parseToJsonElement(encoded),
                "id",
            ).map { it.content }
            assertTrue(ids.isNotEmpty())
            // §1.3: identifiers "MUST be unique for a given exchanged Account
            // and have a maximum of 64 bytes in length" — decoded bytes.
            assertEquals(ids.size, ids.toSet().size, "duplicate ids: $ids")
            ids.forEach { id ->
                assertTrue(decodedB64UrlSize(id) <= 64, "id exceeds 64 bytes: $id")
            }
        }
    }

    /**
     * The `streetAddress` value of the single item's address credential, read
     * back off the encoded payload.
     */
    private fun streetAddressOf(
        secret: DSecret,
    ): String? {
        val encoded = service.encode(buildDocument(ciphers = listOf(secret)))
        val credentials = Json.parseToJsonElement(encoded).jsonObject
            .get("accounts")!!.jsonArray.single().jsonObject
            .get("items")!!.jsonArray.single().jsonObject
            .get("credentials")!!.jsonArray
        val address = credentials
            .map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "address" }
        return address["streetAddress"]?.jsonObject?.get("value")?.jsonPrimitive?.content
    }

    @Test
    fun `the street-address separator is a literal newline whatever the host uses`() {
        // The separator is wire data, so it cannot be the host's: joining with
        // `System.lineSeparator()` emits `\r\n` on a Windows JVM, and a reader
        // that splits on '\n' — Keyguard's own importer included — then sees a
        // trailing carriage return on every line but the last. The golden vector
        // pins the same byte; this states the rule it is pinning.
        assertEquals("1 Main St\nApt 2", streetAddressOf(identitySecret))
    }

    @Test
    fun `three address lines are packed with that one separator`() {
        // Not `\r\n\r\n`, not a mixture: the same single byte between every pair,
        // whatever the exporting host would have written.
        val secret = cxfSecret(
            type = DSecret.Type.Identity,
            identity = DSecret.Identity(
                address1 = "1 Main St",
                address2 = "Apt 2",
                address3 = "Rear building",
            ),
        )
        assertEquals("1 Main St\nApt 2\nRear building", streetAddressOf(secret))
    }

    @Test
    fun `a profile with a blank name omits the optional fullName member`() {
        val document = buildDocument(
            ciphers = listOf(identitySecret),
            profileName = "",
        )
        val account = Json.parseToJsonElement(service.encode(document)).jsonObject
            .get("accounts")!!.jsonArray.single().jsonObject
        assertFalse("fullName" in account)
    }
}

// Expected wire output for the identity golden vector: one identity item split
// into person-name + address + custom-fields credentials, pinning the exact
// camelCase member names of the §3.3 credential dictionaries. Compared as a
// decoded JsonElement, so key order and whitespace are irrelevant.
private const val IDENTITY_VECTOR_JSON = """
{
  "version": { "major": 1, "minor": 0 },
  "exporterRpId": "com.example.importer",
  "exporterDisplayName": "Example Importer",
  "timestamp": 1706623773,
  "accounts": [
    {
      "id": "YWNjLTE",
      "username": "Alice Example",
      "email": "alice@example.com",
      "fullName": "Alice Example",
      "collections": [],
      "items": [
        {
          "id": "aXRlbS0x",
          "creationAt": 1706613834,
          "modifiedAt": 1706623773,
          "title": "Example Identity",
          "favorite": false,
          "credentials": [
            {
              "type": "person-name",
              "title": { "fieldType": "string", "value": "Dr" },
              "given": { "fieldType": "string", "value": "Alice" },
              "given2": { "fieldType": "string", "value": "Betty" },
              "surname": { "fieldType": "string", "value": "Example" }
            },
            {
              "type": "address",
              "streetAddress": { "fieldType": "string", "value": "1 Main St\nApt 2" },
              "postalCode": { "fieldType": "string", "value": "97477" },
              "city": { "fieldType": "string", "value": "Springfield" },
              "territory": { "fieldType": "subdivision-code", "value": "OR" },
              "country": { "fieldType": "country-code", "value": "US" },
              "tel": { "fieldType": "string", "value": "555-0100" }
            },
            {
              "type": "custom-fields",
              "fields": [
                { "fieldType": "string", "value": "Acme", "label": "Company" },
                { "fieldType": "email", "value": "alice@example.com", "label": "Email" },
                { "fieldType": "string", "value": "acme", "label": "Username" },
                { "fieldType": "concealed-string", "value": "078-05-1120", "label": "Social Security Number" },
                { "fieldType": "concealed-string", "value": "P123456", "label": "Passport Number" },
                { "fieldType": "concealed-string", "value": "DL123456", "label": "License Number" }
              ]
            }
          ]
        }
      ]
    }
  ]
}
"""
