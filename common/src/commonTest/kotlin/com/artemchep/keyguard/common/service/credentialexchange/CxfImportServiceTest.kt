package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CxfImportServiceTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    private fun parseGolden(): CxfImportPlan {
        val result = service.parse(
            payload = GOLDEN_IMPORT_JSON,
            now = now,
        )
        return assertIs<CxfImportResult.Success>(result).plan
    }

    @Test
    fun `golden vector parses the exporter metadata`() {
        val plan = parseGolden()
        assertEquals("com.example.exporter", plan.exporterRpId)
        assertEquals("Example Exporter", plan.exporterDisplayName)
        assertEquals(1, plan.sourceAccountCount)
    }

    @Test
    fun `golden vector builds the folder tree`() {
        val plan = parseGolden()
        assertEquals(
            listOf(
                Triple("account-0/0", null, "Work"),
                Triple("account-0/1", "account-0/0", "Dev"),
                Triple("account-0/2", null, "Duplicates"),
            ),
            plan.folders.map { Triple(it.key, it.parentKey, it.title) },
        )
    }

    @Test
    fun `golden vector assigns items to their first linking collection`() {
        val plan = parseGolden()
        val login = plan.items.single { it.request.type == DSecret.Type.Login }
        assertEquals("account-0/0", login.folderKey)
        val card = plan.items.single { it.request.type == DSecret.Type.Card }
        assertEquals("account-0/1", card.folderKey)
        // The identity item is only linked with a mismatching account id.
        val identity = plan.items.single { it.request.type == DSecret.Type.Identity }
        assertNull(identity.folderKey)
    }

    @Test
    fun `golden vector merges the login credentials into one request`() {
        val plan = parseGolden()
        val login = plan.items.single { it.request.type == DSecret.Type.Login }.request
        assertEquals("Example Login", login.title)
        assertEquals(true, login.favorite)
        assertEquals(false, login.reprompt)
        assertEquals(listOf("work"), login.tags.toList())
        assertEquals("alice@example.com", login.login.username)
        assertEquals("s3cr3t", login.login.password)
        assertEquals(
            "otpauth://totp/alice%40example.com?secret=JBSWY3DPEHPK3PXP",
            login.login.totp,
        )
        assertEquals("login note", login.note)
        val passkey = login.fido2Credentials.single()
        assertEquals("AAECAwQFBg", passkey.credentialId)
        assertEquals(CXF_TEST_PASSKEY_KEY_STANDARD, passkey.keyValue)
        assertEquals(Instant.fromEpochSeconds(GOLDEN_CREATED_AT), passkey.creationDate)
        assertEquals(
            listOf(
                DSecret.Uri(uri = "https://example.com"),
                DSecret.Uri(uri = "example.com"),
                DSecret.Uri(
                    uri = "androidapp://com.example.app",
                    signatures = listOf(
                        DSecret.Uri.Signature(
                            certFingerprintSha256 = "00:01:02:03:04:05:06:07:" +
                                "08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:" +
                                "18:19:1A:1B:1C:1D:1E:1F",
                        ),
                    ),
                ),
            ),
            login.uris.toList(),
        )
    }

    @Test
    fun `golden vector splits the card expiry and keeps the pin`() {
        val plan = parseGolden()
        val card = plan.items.single { it.request.type == DSecret.Type.Card }.request
        assertEquals("Example Card", card.title)
        assertEquals("4111111111111111", card.card.number)
        assertEquals("Alice Example", card.card.cardholderName)
        assertEquals("2027", card.card.expYear)
        assertEquals("5", card.card.expMonth)
        assertEquals("PIN", card.fields.single().name)
    }

    @Test
    fun `golden vector re-merges the identity credentials`() {
        val plan = parseGolden()
        val request = plan.items.single { it.request.type == DSecret.Type.Identity }.request
        val identity = request.identity
        assertEquals("Alice", identity.firstName)
        assertEquals("Example", identity.lastName)
        assertEquals("1 Main St", identity.address1)
        assertEquals("Apt 2", identity.address2)
        assertEquals("US", identity.country)
        assertEquals("P123456", identity.passportNumber)
        assertEquals("078-05-1120", identity.ssn)
        assertEquals("DL123456", identity.licenseNumber)
        // Consumed from the custom-fields credential.
        assertEquals("alice@example.com", identity.email)
        assertEquals("acme", identity.username)
        // The identity-document identification number lost the ssn slot to
        // the passport and is preserved as an extra field.
        assertTrue(
            request.fields.any {
                it.name == "Identification number" && it.value == "ID-9"
            },
        )
    }

    @Test
    fun `golden vector turns a standalone note into a secure note`() {
        val plan = parseGolden()
        val request = plan.items.single { it.request.type == DSecret.Type.SecureNote }.request
        assertEquals("Example Note", request.title)
        assertEquals("standalone", request.note)
    }

    @Test
    fun `golden vector counts the skips`() {
        val plan = parseGolden()
        // A wifi credential inside the login item and an api-key credential
        // forming an otherwise empty item. The api-key item is not counted
        // again: its only credential is already counted.
        assertEquals(2, plan.skips[CxfImportSkipReason.UnknownCredential])
        assertEquals(0, plan.skips[CxfImportSkipReason.Item])
        assertEquals(0, plan.skips[CxfImportSkipReason.Passkey])
        // login + card + identity + ssh + note.
        assertEquals(5, plan.items.size)
    }

    @Test
    fun `an item emptied by a counted credential yields one warning row, like the exporter does`() {
        // What the user actually sees is rows, not a total: the api-key item and
        // the credential that emptied it must not show up as two separate rows.
        val plan = parseGolden()
        assertEquals(1, plan.skips.counted.size)
        assertEquals(CxfImportSkipReason.UnknownCredential, plan.skips.counted.single().first)
    }

    @Test
    fun `a malformed item shell survives as a counted skip`() {
        val payload = """
            {
              "version": {"major": 1, "minor": 0},
              "accounts": [
                {
                  "id": "YWNjLTE",
                  "username": "u",
                  "email": "e",
                  "collections": [],
                  "items": [
                    {"credentials": []},
                    "not an object"
                  ]
                }
              ]
            }
        """.trimIndent()
        val plan = assertIs<CxfImportResult.Success>(
            service.parse(payload = payload, now = now),
        ).plan
        assertEquals(2, plan.skips[CxfImportSkipReason.Item])
        assertTrue(plan.items.isEmpty())
    }
}

private const val GOLDEN_CREATED_AT = 1706613834L

/**
 * A hand-authored, spec-shaped CXF v1.0 document with entirely fictional
 * example.com data. It deliberately carries members this implementation does
 * not model (`extensions`, a `wifi` and an `api-key` credential, an unknown
 * top-level member) to pin the lenient-decode behavior.
 */
internal const val GOLDEN_IMPORT_JSON = """
{
  "version": {"major": 1, "minor": 0},
  "exporterRpId": "com.example.exporter",
  "exporterDisplayName": "Example Exporter",
  "timestamp": 1706623773,
  "unknownTopLevelMember": {"ignored": true},
  "accounts": [
    {
      "id": "YWNjLTE",
      "username": "Alice Example",
      "email": "alice@example.com",
      "extensions": [{"name": "shared"}],
      "collections": [
        {
          "id": "Zm9sZGVyLTE",
          "title": "Work",
          "items": [{"item": "aXRlbS0x"}],
          "subCollections": [
            {
              "id": "Zm9sZGVyLTI",
              "title": "Dev",
              "items": [
                {"item": "aXRlbS0y"},
                {"item": "bWlzc2luZw"},
                {"item": "aXRlbS0z", "account": "b3RoZXI"}
              ]
            }
          ]
        },
        {
          "id": "Zm9sZGVyLTM",
          "title": "Duplicates",
          "items": [{"item": "aXRlbS0x"}]
        }
      ],
      "items": [
        {
          "id": "aXRlbS0x",
          "creationAt": 1706613834,
          "modifiedAt": 1706623773,
          "title": "Example Login",
          "favorite": true,
          "tags": ["work"],
          "scope": {
            "urls": ["https://example.com", "example.com"],
            "androidApps": [
              {
                "bundleId": "com.example.app",
                "certificate": {"fingerprint": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "hashAlg": "sha256"}
              }
            ]
          },
          "credentials": [
            {
              "type": "basic-auth",
              "username": {"fieldType": "string", "value": "alice@example.com"},
              "password": {"fieldType": "concealed-string", "value": "s3cr3t"}
            },
            {
              "type": "passkey",
              "credentialId": "AAECAwQFBg",
              "rpId": "example.com",
              "username": "alice",
              "userDisplayName": "Alice",
              "userHandle": "AAECAwQFBg",
              "key": "$CXF_TEST_PASSKEY_KEY_URL"
            },
            {
              "type": "totp",
              "secret": "JBSWY3DPEHPK3PXP",
              "period": 30,
              "digits": 6,
              "algorithm": "sha1",
              "username": "alice@example.com"
            },
            {
              "type": "note",
              "content": {"fieldType": "string", "value": "login note"}
            },
            {
              "type": "wifi",
              "ssid": {"fieldType": "string", "value": "guest"}
            }
          ]
        },
        {
          "id": "aXRlbS0y",
          "title": "Example Card",
          "credentials": [
            {
              "type": "credit-card",
              "number": {"fieldType": "concealed-string", "value": "4111111111111111"},
              "fullName": {"fieldType": "string", "value": "Alice Example"},
              "expiryDate": {"fieldType": "year-month", "value": "2027-05"},
              "pin": {"fieldType": "concealed-string", "value": "0000"}
            }
          ]
        },
        {
          "id": "aXRlbS0z",
          "title": "Example Identity",
          "credentials": [
            {
              "type": "person-name",
              "given": {"fieldType": "string", "value": "Alice"},
              "surname": {"fieldType": "string", "value": "Example"}
            },
            {
              "type": "address",
              "streetAddress": {"fieldType": "string", "value": "1 Main St\nApt 2"},
              "city": {"fieldType": "string", "value": "Springfield"},
              "territory": {"fieldType": "subdivision-code", "value": "OR"},
              "postalCode": {"fieldType": "string", "value": "97477"},
              "country": {"fieldType": "country-code", "value": "US"},
              "tel": {"fieldType": "string", "value": "555-0100"}
            },
            {
              "type": "passport",
              "passportNumber": {"fieldType": "concealed-string", "value": "P123456"},
              "nationalIdentificationNumber": {"fieldType": "concealed-string", "value": "078-05-1120"}
            },
            {
              "type": "drivers-license",
              "licenseNumber": {"fieldType": "concealed-string", "value": "DL123456"}
            },
            {
              "type": "identity-document",
              "identificationNumber": {"fieldType": "concealed-string", "value": "ID-9"}
            },
            {
              "type": "custom-fields",
              "fields": [
                {"fieldType": "email", "value": "alice@example.com", "label": "Email"},
                {"fieldType": "string", "value": "acme", "label": "Username"}
              ]
            }
          ]
        },
        {
          "id": "aXRlbS00",
          "title": "Example SSH",
          "credentials": [
            {
              "type": "ssh-key",
              "keyType": "ssh-ed25519",
              "privateKey": "AAECAwQFBg",
              "keyComment": "work laptop"
            }
          ]
        },
        {
          "id": "aXRlbS01",
          "title": "Example Note",
          "credentials": [
            {
              "type": "note",
              "content": {"fieldType": "string", "value": "standalone"}
            }
          ]
        },
        {
          "id": "aXRlbS02",
          "title": "Unsupported",
          "credentials": [
            {
              "type": "api-key",
              "key": {"fieldType": "concealed-string", "value": "xyz"}
            }
          ]
        }
      ]
    }
  ]
}
"""
