package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Conformance coverage for CXF v1.0 §3.3 (cxf-v1.0-ps-errata-20260309): every
 * credential type the specification defines appears in one comprehensive
 * vector with its full member set — the twelve modeled kinds import, the five
 * unmodeled kinds (api-key, file, generated-password, item-reference, wifi)
 * are counted skips that never poison their document.
 */
class CxfConformanceCredentialTypesTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    private fun parseAllTypes(): CxfImportPlan =
        service.parseSuccessPlan(payload = ALL_TYPES_IMPORT_JSON, now = now)

    private fun CxfImportPlan.requestTitled(title: String): CreateRequest =
        items.single { it.request.title == title }.request

    @Test
    fun `every modeled credential type imports and every item maps to one request`() {
        val plan = parseAllTypes()
        val byType = plan.items.groupBy { it.request.type }
        // passkey + basic-auth + totp items.
        assertEquals(3, byType[DSecret.Type.Login]?.size)
        assertEquals(1, byType[DSecret.Type.Card]?.size)
        // person-name + address + passport + drivers-license +
        // identity-document, one item each.
        assertEquals(5, byType[DSecret.Type.Identity]?.size)
        // The note item and the standalone custom-fields item.
        assertEquals(2, byType[DSecret.Type.SecureNote]?.size)
        assertEquals(1, byType[DSecret.Type.SshKey]?.size)
        assertEquals(12, plan.items.size)
    }

    @Test
    fun `the five unmodeled spec types are counted as unknown credentials`() {
        val plan = parseAllTypes()
        assertEquals(5, plan.skips[CxfImportSkipReason.UnknownCredential])
        // Each unmodeled credential was its item's only credential, so the
        // credential reason already explains the emptied item.
        assertEquals(0, plan.skips[CxfImportSkipReason.Item])
        assertEquals(0, plan.skips[CxfImportSkipReason.Passkey])
        assertEquals(0, plan.skips[CxfImportSkipReason.Otp])
        assertEquals(0, plan.skips[CxfImportSkipReason.SshKey])
        assertEquals(0, plan.skips[CxfImportSkipReason.DuplicateCredential])
    }

    @Test
    fun `passkey imports every member and forces the counter to zero`() {
        val request = parseAllTypes().requestTitled("Passkey")
        val passkey = request.fido2Credentials.single()
        // A 16-byte credential id decodes into Keyguard's UUID form.
        assertEquals("00010203-0405-0607-0809-0a0b0c0d0e0f", passkey.credentialId)
        assertEquals("example.com", passkey.rpId)
        assertEquals("alice", passkey.userName)
        assertEquals("Alice Example", passkey.userDisplayName)
        assertEquals("AAECAwQFBg", passkey.userHandle)
        // The PKCS#8 DER re-encodes into Keyguard's padded standard base64.
        assertEquals(CXF_TEST_PASSKEY_KEY_STANDARD, passkey.keyValue)
        // CXF v1.0 §3.3.12: importers MUST set the signature counter to zero.
        assertEquals(0, passkey.counter)
        assertEquals(Instant.fromEpochSeconds(1706613834L), passkey.creationDate)
        // Without a scope, the login uris derive from the passkey rp id.
        assertEquals(listOf(DSecret.Uri(uri = "https://example.com")), request.uris.toList())
        assertEquals("alice", request.login.username)
    }

    @Test
    fun `totp imports the full member set with the base32 secret verbatim`() {
        val request = parseAllTypes().requestTitled("Totp")
        assertEquals(
            "otpauth://totp/Example:alice%40example.com" +
                "?secret=JBSWY3DPEHPK3PXP" +
                "&issuer=Example" +
                "&algorithm=SHA256" +
                "&digits=8" +
                "&period=60",
            request.login.totp,
        )
    }

    @Test
    fun `credit-card imports the full member set`() {
        val request = parseAllTypes().requestTitled("Credit Card")
        assertEquals("4111111111111111", request.card.number)
        assertEquals("Alice Example", request.card.cardholderName)
        assertEquals("Visa", request.card.brand)
        assertEquals("123", request.card.code)
        assertEquals("2027", request.card.expYear)
        assertEquals("5", request.card.expMonth)
        // `validFrom` is NOT written into `CreateRequest.Card`: `AddCipher`
        // builds `BitwardenCipher.Card` without `fromMonth`/`fromYear`, so a
        // value put there is dropped at the vault write. It travels as a
        // labelled field, beside the pin, which has no slot either.
        assertNull(request.card.fromYear)
        assertNull(request.card.fromMonth)
        assertEquals(
            listOf(
                "PIN" to "0000",
                "Valid from" to "2024-01",
            ),
            request.fields.map { it.name to it.value },
        )
    }

    @Test
    fun `person-name imports its full member set`() {
        val request = parseAllTypes().requestTitled("Person Name")
        val identity = request.identity
        assertEquals("Dr", identity.title)
        assertEquals("Alice", identity.firstName)
        assertEquals("Betty", identity.middleName)
        // The surname prefix and second surname join into the last name.
        assertEquals("van Example Smith", identity.lastName)
        // Professional credentials land in the company slot.
        assertEquals("PhD", identity.company)
        assertFieldNames(
            request,
            "Informal name",
            "Generation",
        )
    }

    @Test
    fun `address imports its full member set`() {
        val request = parseAllTypes().requestTitled("Address")
        val identity = request.identity
        assertEquals("1 Main St", identity.address1)
        assertEquals("Apt 2", identity.address2)
        assertEquals("Springfield", identity.city)
        assertEquals("OR", identity.state)
        assertEquals("97477", identity.postalCode)
        assertEquals("US", identity.country)
        assertEquals("555-0100", identity.phone)
    }

    @Test
    fun `passport imports its full member set with overflow fields`() {
        val request = parseAllTypes().requestTitled("Passport")
        val identity = request.identity
        assertEquals("Pat", identity.firstName)
        assertEquals("Example", identity.lastName)
        assertEquals("P123456", identity.passportNumber)
        assertEquals("078-05-1120", identity.ssn)
        assertEquals("US", identity.country)
        assertFieldNames(
            request,
            "Nationality",
            "Birth date",
            "Birth place",
            "Sex",
            "Passport issue date",
            "Passport expiry date",
            "Passport issuing authority",
            "Passport type",
        )
    }

    @Test
    fun `drivers-license imports its full member set with overflow fields`() {
        val request = parseAllTypes().requestTitled("Drivers License")
        val identity = request.identity
        assertEquals("Dana", identity.firstName)
        assertEquals("Example", identity.lastName)
        assertEquals("DL123456", identity.licenseNumber)
        assertEquals("OR", identity.state)
        assertEquals("US", identity.country)
        assertFieldNames(
            request,
            "Birth date",
            "License issue date",
            "License expiry date",
            "License issuing authority",
            "License class",
        )
    }

    @Test
    fun `identity-document imports its full member set with overflow fields`() {
        val request = parseAllTypes().requestTitled("Identity Document")
        val identity = request.identity
        assertEquals("Ida", identity.firstName)
        assertEquals("Example", identity.lastName)
        assertEquals("078-05-1120", identity.ssn)
        assertEquals("ID-1", identity.passportNumber)
        assertEquals("US", identity.country)
        assertFieldNames(
            request,
            "Nationality",
            "Birth date",
            "Birth place",
            "Sex",
            "Document issue date",
            "Document expiry date",
            "Document issuing authority",
        )
    }

    @Test
    fun `ssh-key imports its metadata members as fields`() {
        val request = parseAllTypes().requestTitled("Ssh Key")
        assertEquals(fakeSshKeyPair().privateKey.ssh, request.sshKey.privateKey)
        assertEquals(fakeSshKeyPair().publicKey.ssh, request.sshKey.publicKey)
        assertEquals(
            listOf(
                "Key comment" to "work laptop",
                "Creation date" to "2024-01-02",
                "Expiry date" to "2030-01-02",
                "Key generation source" to "https://example.com/generator",
            ),
            request.fields.map { it.name to it.value },
        )
    }

    @Test
    fun `custom-fields import as a standalone secure note`() {
        val request = parseAllTypes().requestTitled("Custom Fields")
        val field = request.fields.single()
        assertEquals("API base", field.name)
        assertEquals("https://api.example.com", field.value)
        assertEquals(DSecret.Field.Type.Text, field.type)
    }

    @Test
    fun `generated-password with a raw string password does not poison its item`() {
        // CXF v1.0 §3.3.8 types `password` as a raw string, not an
        // EditableField — the shape must fail only its own (unmodeled)
        // credential while the basic-auth sibling still imports.
        val payload = documentWithItems(
            """
            {
              "id": "aXRlbTAx",
              "title": "Mixed",
              "credentials": [
                {"type": "generated-password", "password": "gen-s3cr3t"},
                {
                  "type": "basic-auth",
                  "username": {"fieldType": "string", "value": "alice"}
                }
              ]
            }
            """,
        )
        val plan = service.parseSuccessPlan(payload = payload, now = now)
        assertEquals(1, plan.skips[CxfImportSkipReason.UnknownCredential])
        assertEquals(0, plan.skips[CxfImportSkipReason.Item])
        assertEquals("alice", plan.items.single().request.login.username)
    }

    @Test
    fun `a note without its required content member is a counted skip`() {
        val payload = documentWithItems(
            """
            {
              "id": "aXRlbTAx",
              "title": "Broken note",
              "credentials": [{"type": "note"}]
            }
            """,
        )
        val plan = service.parseSuccessPlan(payload = payload, now = now)
        // §2.1.1: an unusable value in a required member drops the enclosing
        // structure — the credential, and with it the now-empty item. Both are
        // gone, but the loss is reported once: the credential reason explains it.
        assertEquals(1, plan.skips[CxfImportSkipReason.UnknownCredential])
        assertEquals(0, plan.skips[CxfImportSkipReason.Item])
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `a passkey without its required rpId member is a counted skip`() {
        val payload = documentWithItems(
            """
            {
              "id": "aXRlbTAx",
              "title": "Broken passkey",
              "credentials": [
                {
                  "type": "passkey",
                  "credentialId": "AAECAwQFBg",
                  "username": "alice",
                  "userDisplayName": "Alice",
                  "userHandle": "AAECAwQFBg",
                  "key": "$CXF_TEST_PASSKEY_KEY_URL"
                }
              ]
            }
            """,
        )
        val plan = service.parseSuccessPlan(payload = payload, now = now)
        assertEquals(1, plan.skips[CxfImportSkipReason.UnknownCredential])
        assertEquals(0, plan.skips[CxfImportSkipReason.Item])
        assertEquals(0, plan.skips[CxfImportSkipReason.Passkey])
    }

    private fun assertFieldNames(
        request: CreateRequest,
        vararg names: String,
    ) {
        assertEquals(names.toList(), request.fields.map { it.name })
    }
}

/**
 * A hand-authored CXF v1.0 document carrying one item per credential type the
 * specification defines — all seventeen of them, each with its full member
 * set (per cxf-v1.0-ps-errata-20260309 §3.3) and entirely fictional
 * example.com data. The five kinds Keyguard does not model (api-key, file,
 * generated-password, item-reference, wifi) pin the lenient per-credential
 * decode.
 */
private const val ALL_TYPES_IMPORT_JSON = """
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
      "fullName": "Alice Example",
      "collections": [],
      "items": [
        {
          "id": "aXRlbTAx",
          "creationAt": 1706613834,
          "modifiedAt": 1706623773,
          "title": "Passkey",
          "credentials": [
            {
              "type": "passkey",
              "credentialId": "AAECAwQFBgcICQoLDA0ODw",
              "rpId": "example.com",
              "username": "alice",
              "userDisplayName": "Alice Example",
              "userHandle": "AAECAwQFBg",
              "key": "$CXF_TEST_PASSKEY_KEY_URL",
              "fido2Extensions": {"payments": true}
            }
          ]
        },
        {
          "id": "aXRlbTAy",
          "title": "Basic Auth",
          "credentials": [
            {
              "type": "basic-auth",
              "username": {
                "fieldType": "string",
                "value": "bob",
                "id": "ZmllbGQtMQ",
                "label": "Login"
              },
              "password": {"fieldType": "concealed-string", "value": "s3cr3t"}
            }
          ]
        },
        {
          "id": "aXRlbTAz",
          "title": "Totp",
          "credentials": [
            {
              "type": "totp",
              "secret": "JBSWY3DPEHPK3PXP",
              "period": 60,
              "digits": 8,
              "algorithm": "sha256",
              "username": "alice@example.com",
              "issuer": "Example"
            }
          ]
        },
        {
          "id": "aXRlbTA0",
          "title": "Credit Card",
          "credentials": [
            {
              "type": "credit-card",
              "number": {"fieldType": "concealed-string", "value": "4111111111111111"},
              "fullName": {"fieldType": "string", "value": "Alice Example"},
              "cardType": {"fieldType": "string", "value": "Visa"},
              "verificationNumber": {"fieldType": "concealed-string", "value": "123"},
              "pin": {"fieldType": "concealed-string", "value": "0000"},
              "expiryDate": {"fieldType": "year-month", "value": "2027-05"},
              "validFrom": {"fieldType": "year-month", "value": "2024-01"}
            }
          ]
        },
        {
          "id": "aXRlbTA1",
          "title": "Note",
          "credentials": [
            {
              "type": "note",
              "content": {"fieldType": "string", "value": "standalone note"}
            }
          ]
        },
        {
          "id": "aXRlbTA2",
          "title": "Ssh Key",
          "credentials": [
            {
              "type": "ssh-key",
              "keyType": "ssh-ed25519",
              "privateKey": "AAECAwQFBg",
              "keyComment": "work laptop",
              "creationDate": {"fieldType": "date", "value": "2024-01-02"},
              "expiryDate": {"fieldType": "date", "value": "2030-01-02"},
              "keyGenerationSource": {"fieldType": "string", "value": "https://example.com/generator"}
            }
          ]
        },
        {
          "id": "aXRlbTA3",
          "title": "Custom Fields",
          "credentials": [
            {
              "type": "custom-fields",
              "id": "YmFnLTE",
              "label": "Extras",
              "fields": [
                {"fieldType": "string", "value": "https://api.example.com", "label": "API base"}
              ]
            }
          ]
        },
        {
          "id": "aXRlbTA4",
          "title": "Person Name",
          "credentials": [
            {
              "type": "person-name",
              "title": {"fieldType": "string", "value": "Dr"},
              "given": {"fieldType": "string", "value": "Alice"},
              "givenInformal": {"fieldType": "string", "value": "Ali"},
              "given2": {"fieldType": "string", "value": "Betty"},
              "surnamePrefix": {"fieldType": "string", "value": "van"},
              "surname": {"fieldType": "string", "value": "Example"},
              "surname2": {"fieldType": "string", "value": "Smith"},
              "credentials": {"fieldType": "string", "value": "PhD"},
              "generation": {"fieldType": "string", "value": "III"}
            }
          ]
        },
        {
          "id": "aXRlbTA5",
          "title": "Address",
          "credentials": [
            {
              "type": "address",
              "streetAddress": {"fieldType": "string", "value": "1 Main St\nApt 2"},
              "postalCode": {"fieldType": "string", "value": "97477"},
              "city": {"fieldType": "string", "value": "Springfield"},
              "territory": {"fieldType": "subdivision-code", "value": "OR"},
              "country": {"fieldType": "country-code", "value": "US"},
              "tel": {"fieldType": "string", "value": "555-0100"}
            }
          ]
        },
        {
          "id": "aXRlbTEw",
          "title": "Passport",
          "credentials": [
            {
              "type": "passport",
              "issuingCountry": {"fieldType": "country-code", "value": "US"},
              "passportType": {"fieldType": "string", "value": "P"},
              "passportNumber": {"fieldType": "concealed-string", "value": "P123456"},
              "nationalIdentificationNumber": {"fieldType": "concealed-string", "value": "078-05-1120"},
              "nationality": {"fieldType": "string", "value": "American"},
              "fullName": {"fieldType": "string", "value": "Pat Example"},
              "birthDate": {"fieldType": "date", "value": "1990-01-02"},
              "birthPlace": {"fieldType": "string", "value": "Springfield"},
              "sex": {"fieldType": "string", "value": "X"},
              "issueDate": {"fieldType": "date", "value": "2020-01-02"},
              "expiryDate": {"fieldType": "date", "value": "2030-01-02"},
              "issuingAuthority": {"fieldType": "string", "value": "Department of State"}
            }
          ]
        },
        {
          "id": "aXRlbTEx",
          "title": "Drivers License",
          "credentials": [
            {
              "type": "drivers-license",
              "fullName": {"fieldType": "string", "value": "Dana Example"},
              "birthDate": {"fieldType": "date", "value": "1990-01-02"},
              "issueDate": {"fieldType": "date", "value": "2020-01-02"},
              "expiryDate": {"fieldType": "date", "value": "2030-01-02"},
              "issuingAuthority": {"fieldType": "string", "value": "DMV"},
              "territory": {"fieldType": "subdivision-code", "value": "OR"},
              "country": {"fieldType": "country-code", "value": "US"},
              "licenseNumber": {"fieldType": "concealed-string", "value": "DL123456"},
              "licenseClass": {"fieldType": "string", "value": "C"}
            }
          ]
        },
        {
          "id": "aXRlbTEy",
          "title": "Identity Document",
          "credentials": [
            {
              "type": "identity-document",
              "issuingCountry": {"fieldType": "country-code", "value": "US"},
              "documentNumber": {"fieldType": "concealed-string", "value": "ID-1"},
              "identificationNumber": {"fieldType": "concealed-string", "value": "078-05-1120"},
              "nationality": {"fieldType": "string", "value": "American"},
              "fullName": {"fieldType": "string", "value": "Ida Example"},
              "birthDate": {"fieldType": "date", "value": "1990-01-02"},
              "birthPlace": {"fieldType": "string", "value": "Springfield"},
              "sex": {"fieldType": "string", "value": "X"},
              "issueDate": {"fieldType": "date", "value": "2020-01-02"},
              "expiryDate": {"fieldType": "date", "value": "2030-01-02"},
              "issuingAuthority": {"fieldType": "string", "value": "Registry Office"}
            }
          ]
        },
        {
          "id": "aXRlbTEz",
          "title": "Api Key",
          "credentials": [
            {
              "type": "api-key",
              "key": {"fieldType": "concealed-string", "value": "xyz"},
              "username": {"fieldType": "string", "value": "alice"},
              "keyType": {"fieldType": "string", "value": "bearer"},
              "url": {"fieldType": "string", "value": "https://api.example.com"},
              "validFrom": {"fieldType": "date", "value": "2024-01-02"},
              "expiryDate": {"fieldType": "date", "value": "2030-01-02"}
            }
          ]
        },
        {
          "id": "aXRlbTE0",
          "title": "File",
          "credentials": [
            {
              "type": "file",
              "id": "ZmlsZS0x",
              "name": "notes.txt",
              "decryptedSize": 1024,
              "integrityHash": "AAECAwQFBg"
            }
          ]
        },
        {
          "id": "aXRlbTE1",
          "title": "Generated Password",
          "credentials": [
            {"type": "generated-password", "password": "gen-s3cr3t"}
          ]
        },
        {
          "id": "aXRlbTE2",
          "title": "Item Reference",
          "credentials": [
            {
              "type": "item-reference",
              "reference": {"item": "aXRlbTAx"}
            }
          ]
        },
        {
          "id": "aXRlbTE3",
          "title": "Wifi",
          "credentials": [
            {
              "type": "wifi",
              "ssid": {"fieldType": "string", "value": "guest"},
              "networkSecurityType": {"fieldType": "wifi-network-security-type", "value": "wpa2-personal"},
              "passphrase": {"fieldType": "concealed-string", "value": "hunter2"},
              "hidden": {"fieldType": "boolean", "value": "false"}
            }
          ]
        }
      ]
    }
  ]
}
"""
