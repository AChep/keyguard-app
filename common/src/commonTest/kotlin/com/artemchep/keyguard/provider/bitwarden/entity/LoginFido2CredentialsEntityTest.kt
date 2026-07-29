package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A passkey without a user handle, or without an rp name, must not fail the sync.
 *
 * Both are optional on the wire — a non-discoverable credential carries no
 * handle. In kotlinx.serialization nullability alone only permits an explicit
 * `null`: a member with no default stays REQUIRED and an omitted key raises.
 * Since `SyncEntity.ciphers` is one list decoded in a single shot, that takes
 * down every cipher in the response rather than the one passkey.
 */
class LoginFido2CredentialsEntityTest {
    @Test
    fun `an absent user handle decodes to null`() {
        val entity = json.decodeFromString<LoginFido2CredentialsEntity>(
            credentialJson(userHandle = null),
        )
        assertNull(entity.userHandle)
    }

    @Test
    fun `a present user handle survives`() {
        // The counterweight to every `assertNull` here: the key really binds, so
        // a wrong `@SerialName` (silently ignored, then defaulted to null) cannot
        // pass as "absence handled".
        val entity = json.decodeFromString<LoginFido2CredentialsEntity>(
            credentialJson(userHandle = "\"AAECAwQFBg\""),
        )
        assertEquals("AAECAwQFBg", entity.userHandle)
    }

    @Test
    fun `a camel cased credential decodes`() {
        // The entity accepts both the PascalCase and camelCase spelling of every
        // member; server builds differ on which they answer with. Most members
        // here are required, so dropping the camelCase alternates makes this
        // decode raise rather than merely lose the value.
        val entity = json.decodeFromString<LoginFido2CredentialsEntity>(
            """
            {
              "credentialId": "Y3JlZC1pZA",
              "keyType": "public-key",
              "keyAlgorithm": "ECDSA",
              "keyCurve": "P-256",
              "keyValue": "a2V5",
              "rpId": "example.com",
              "rpName": "Example",
              "counter": "0",
              "userHandle": "AAECAwQFBg",
              "discoverable": "true",
              "creationDate": "2024-01-01T00:00:00Z"
            }
            """.trimIndent(),
        )
        assertEquals("Y3JlZC1pZA", entity.credentialId)
        assertEquals("Example", entity.rpName)
        assertEquals("AAECAwQFBg", entity.userHandle)
    }

    @Test
    fun `one handle-less passkey does not fail the ciphers list`() {
        // The ciphers list is decoded as a single unit, so one credential the
        // decoder refuses stops the whole vault from syncing.
        val ciphers = json.decodeFromString<List<CipherEntity>>(
            """
            [
              {
                "id": "cipher-1",
                "type": 1,
                "revisionDate": "2024-01-01T00:00:00Z",
                "login": {
                  "username": "alice",
                  "fido2Credentials": [
                    ${credentialJson(userHandle = "null")}
                  ]
                }
              },
              {
                "id": "cipher-2",
                "type": 1,
                "revisionDate": "2024-01-01T00:00:00Z",
                "login": { "username": "bob" }
              }
            ]
            """.trimIndent(),
        )
        assertEquals(2, ciphers.size, "the good cipher must survive too")
        val credential = ciphers.first().login?.fido2Credentials?.single()
        assertTrue(credential != null)
        assertNull(credential.userHandle)
    }

    @Test
    fun `an absent rp name decodes to null`() {
        // The server drops null members when it echoes the login blob back, so
        // the key really is absent for any passkey stored without an rp name —
        // which is what the CXF and KDBX importers create.
        val entity = json.decodeFromString<LoginFido2CredentialsEntity>(
            credentialJson(userHandle = "null", rpName = null),
        )
        assertNull(entity.rpName)
    }

    @Test
    fun `a cipher whose data blob omits every null member still decodes`() {
        // The same credential shape is also reachable through the `data` blob,
        // which `CipherDecoder` prefers over the legacy top-level members
        // whenever it is present.
        val ciphers = json.decodeFromString<List<CipherEntity>>(
            """
            [
              {
                "id": "cipher-1",
                "type": 1,
                "revisionDate": "2024-01-01T00:00:00Z",
                "login": {
                  "username": "alice",
                  "fido2Credentials": [
                    ${credentialJson(userHandle = null, rpName = null)}
                  ]
                }
              }
            ]
            """.trimIndent(),
        )
        val credential = ciphers.single().login?.fido2Credentials?.single()
        assertTrue(credential != null)
        assertNull(credential.rpName)
        assertNull(credential.userHandle)
    }

    private fun credentialJson(
        userHandle: String?,
        rpName: String? = "\"Example\"",
    ): String {
        val userHandleMember = userHandle
            ?.let { "\"UserHandle\": $it," }
            .orEmpty()
        val rpNameMember = rpName
            ?.let { "\"RpName\": $it," }
            .orEmpty()
        return """
        {
          "CredentialId": "Y3JlZC1pZA",
          "KeyType": "public-key",
          "KeyAlgorithm": "ECDSA",
          "KeyCurve": "P-256",
          "KeyValue": "a2V5",
          "RpId": "example.com",
          $rpNameMember
          "Counter": "0",
          $userHandleMember
          "Discoverable": "true",
          "CreationDate": "2024-01-01T00:00:00Z"
        }
        """.trimIndent()
    }

    /**
     * The decoding flags of the application-wide instance bound in
     * `GlobalModuleJvm`, which is what actually decodes the sync response.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
}
