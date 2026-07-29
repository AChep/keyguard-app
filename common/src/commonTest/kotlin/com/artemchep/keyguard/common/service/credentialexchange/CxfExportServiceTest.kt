package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class CxfExportServiceTest {
    private val service = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    private val goldenSecret = cxfLoginSecret(
        uris = listOf(
            DSecret.Uri(uri = "https://example.com"),
            DSecret.Uri(
                uri = "androidapp://com.example.app",
                signatures = listOf(
                    // A real 32-byte fingerprint: a hash that is not SHA-256 may
                    // not be labelled sha256, so a shorter one is dropped.
                    DSecret.Uri.Signature(certFingerprintSha256 = cxfCertFingerprint()),
                ),
            ),
            // A bare domain, as written by the autofill save flow; pins that a
            // non-URI value reaches the wire verbatim.
            DSecret.Uri(uri = "example.com"),
        ),
        login = DSecret.Login(
            username = "alice@example.com",
            password = "s3cr3t",
            fido2Credentials = listOf(cxfFido2Credential()),
            totp = cxfTotpAuth(),
        ),
    )

    private fun goldenDocument() = service.buildDocument(
        accounts = listOfNotNull(
            service.buildAccount(
                profile = cxfProfile(),
                ciphers = listOf(goldenSecret),
                allowedTypes = CxfCredentialType.ALL,
            ),
        ),
        exporterRpId = "com.example.importer",
        exporterDisplayName = "Example Importer",
        timestamp = Instant.parse("2024-01-30T14:09:33Z"),
    )

    @Test
    fun `golden vector encodes to the expected json element`() {
        val encoded = service.encode(goldenDocument())
        assertEquals(
            Json.parseToJsonElement(GOLDEN_VECTOR_JSON),
            Json.parseToJsonElement(encoded),
        )
    }

    //
    // A second golden vector: a full-vault account with a foldered credit card,
    // a secure note and an SSH key, exercising collections and linked items.
    //

    private val cardSecret = cxfSecret(
        id = "card-1",
        name = "My Card",
        favorite = false,
        tags = emptyList(),
        folderId = "f1",
        type = DSecret.Type.Card,
        card = DSecret.Card(
            cardholderName = "John Doe",
            brand = "Visa",
            number = "4111",
            code = "123",
            expMonth = "12",
            expYear = "2025",
        ),
    )

    private val noteSecret = cxfSecret(
        id = "note-1",
        name = "My Note",
        favorite = false,
        tags = emptyList(),
        notes = "hi",
        type = DSecret.Type.SecureNote,
    )

    private val sshSecret = cxfSecret(
        id = "ssh-1",
        name = "My Key",
        favorite = false,
        tags = emptyList(),
        type = DSecret.Type.SshKey,
        sshKey = DSecret.SshKey(
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----",
            publicKey = "ssh-ed25519 AAAAC3Nz work-laptop",
            fingerprint = "SHA256:abc",
        ),
    )

    private fun fullVaultDocument() = service.buildDocument(
        accounts = listOfNotNull(
            service.buildAccount(
                profile = cxfProfile(),
                ciphers = listOf(cardSecret, noteSecret, sshSecret),
                allowedTypes = CxfCredentialType.ALL,
                folders = listOf(cxfFolder(id = "f1", name = "Work")),
            ),
        ),
        exporterRpId = "com.example.importer",
        exporterDisplayName = "Example Importer",
        timestamp = Instant.parse("2024-01-30T14:09:33Z"),
    )

    @Test
    fun `full vault golden vector encodes to the expected json element`() {
        val encoded = service.encode(fullVaultDocument())
        assertEquals(
            Json.parseToJsonElement(FULL_VAULT_VECTOR_JSON),
            Json.parseToJsonElement(encoded),
        )
    }

    @Test
    fun `empty required arrays stay present in the payload`() {
        // CXF v1.0 §2.1.2: a required array MUST be present in the encoded
        // payload even if the array is empty. The account-without-folders half
        // of the rule is pinned by the golden vectors' `"collections": []`; a
        // folder without exported items must likewise still carry `items`,
        // which no golden covers.
        val document = service.buildDocument(
            accounts = listOfNotNull(
                service.buildAccount(
                    profile = cxfProfile(),
                    ciphers = listOf(noteSecret),
                    allowedTypes = CxfCredentialType.ALL,
                    folders = listOf(cxfFolder(id = "f1", name = "Empty")),
                ),
            ),
            exporterRpId = "com.example.importer",
            exporterDisplayName = "Example Importer",
            timestamp = Instant.parse("2024-01-30T14:09:33Z"),
        )
        assertTrue(service.encode(document).contains("\"items\":[]"))
    }
}

// Expected wire output for the login golden vector. Compared as a decoded
// JsonElement, so key order and whitespace are irrelevant.
private const val GOLDEN_VECTOR_JSON = """
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
          "title": "Example",
          "favorite": true,
          "scope": {
            "urls": [ "https://example.com", "example.com" ],
            "androidApps": [
              {
                "bundleId": "com.example.app",
                "certificate": {
                  "fingerprint": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                  "hashAlg": "sha256"
                }
              }
            ]
          },
          "credentials": [
            {
              "type": "passkey",
              "credentialId": "6NiHiekW4ZY8vYHa-ucbvA",
              "rpId": "example.com",
              "username": "alice",
              "userDisplayName": "Alice",
              "userHandle": "AAECAwQFBg",
              "key": "$CXF_TEST_PASSKEY_KEY_URL"
            },
            {
              "type": "basic-auth",
              "username": { "fieldType": "string", "value": "alice@example.com" },
              "password": { "fieldType": "concealed-string", "value": "s3cr3t" }
            },
            {
              "type": "totp",
              "secret": "JBSWY3DPEHPK3PXP",
              "period": 30,
              "digits": 6,
              "algorithm": "sha1",
              "username": "alice@example.com"
            }
          ],
          "tags": [ "work" ]
        }
      ]
    }
  ]
}
"""

// Expected wire output for the full-vault golden vector.
private const val FULL_VAULT_VECTOR_JSON = """
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
      "collections": [
        {
          "id": "ZjE",
          "modifiedAt": 1706623773,
          "title": "Work",
          "items": [ { "item": "Y2FyZC0x" } ]
        }
      ],
      "items": [
        {
          "id": "Y2FyZC0x",
          "creationAt": 1706613834,
          "modifiedAt": 1706623773,
          "title": "My Card",
          "favorite": false,
          "credentials": [
            {
              "type": "credit-card",
              "number": { "fieldType": "concealed-string", "value": "4111" },
              "fullName": { "fieldType": "string", "value": "John Doe" },
              "cardType": { "fieldType": "string", "value": "Visa" },
              "verificationNumber": { "fieldType": "concealed-string", "value": "123" },
              "expiryDate": { "fieldType": "year-month", "value": "2025-12" }
            }
          ]
        },
        {
          "id": "bm90ZS0x",
          "creationAt": 1706613834,
          "modifiedAt": 1706623773,
          "title": "My Note",
          "favorite": false,
          "credentials": [
            { "type": "note", "content": { "fieldType": "string", "value": "hi" } }
          ]
        },
        {
          "id": "c3NoLTE",
          "creationAt": 1706613834,
          "modifiedAt": 1706623773,
          "title": "My Key",
          "favorite": false,
          "credentials": [
            { "type": "ssh-key", "keyType": "ssh-ed25519", "privateKey": "AQIDBAUG" }
          ]
        }
      ]
    }
  ]
}
"""
