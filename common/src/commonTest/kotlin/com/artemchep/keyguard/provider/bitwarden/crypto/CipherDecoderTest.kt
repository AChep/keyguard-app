package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FieldEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FieldTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LinkedIdTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LoginEntity
import com.artemchep.keyguard.provider.bitwarden.entity.PasswordHistoryEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherDecoderTest {
    @Test
    fun `nested object data response maps pascal-case canonical payload`() {
        val entity = cipherEntityFromJson(
            type = CipherTypeEntity.Login,
            extraJson = """,
                "Data": {
                  "Name": "Data Cipher",
                  "Notes": "Data Notes",
                  "Fields": [
                    {
                      "Type": 0,
                      "Name": "Security question",
                      "Value": "First school",
                      "LinkedId": 101
                    }
                  ],
                  "PasswordHistory": [
                    {
                      "Password": "old-password",
                      "LastUsedDate": "2023-12-31T00:00:00Z"
                    }
                  ],
                  "Uris": [
                    {
                      "Uri": "https://example.com",
                      "UriChecksum": "uri-checksum",
                      "Match": 0
                    }
                  ],
                  "Username": "login-user",
                  "Password": "login-password",
                  "PasswordRevisionDate": "2024-01-02T00:00:00Z",
                  "Totp": "totp-secret",
                  "Fido2Credentials": [
                    {
                      "CredentialId": "credential-id",
                      "KeyType": "public-key",
                      "KeyAlgorithm": "ECDSA",
                      "KeyCurve": "P-256",
                      "KeyValue": "key-value",
                      "RpId": "example.com",
                      "RpName": "Example",
                      "Counter": "1",
                      "UserHandle": "user-handle",
                      "UserName": "fido-user",
                      "UserDisplayName": "Fido User",
                      "Discoverable": "true",
                      "CreationDate": "2024-01-03T00:00:00Z"
                    }
                  ]
                }
            """,
        )

        val cipher = decode(entity)

        assertEquals("Data Cipher", cipher.name)
        assertEquals("Data Notes", cipher.notes)
        assertEquals(
            BitwardenCipher.Field(
                type = BitwardenCipher.Field.Type.Text,
                name = "Security question",
                value = "First school",
                linkedId = BitwardenCipher.Field.LinkedId.Login_Password,
            ),
            cipher.fields.single(),
        )
        assertEquals(
            BitwardenCipher.Login.PasswordHistory(
                password = "old-password",
                lastUsedDate = Instant.parse("2023-12-31T00:00:00Z"),
            ),
            cipher.passwordHistory.single(),
        )

        val login = assertNotNull(cipher.login)
        assertEquals("login-user", login.username)
        assertEquals("login-password", login.password)
        assertEquals(Instant.parse("2024-01-02T00:00:00Z"), login.passwordRevisionDate)
        assertEquals("totp-secret", login.totp)
        assertEquals(
            BitwardenCipher.Login.Uri(
                uri = "https://example.com",
                uriChecksumBase64 = "uri-checksum",
                match = BitwardenCipher.Login.Uri.MatchType.Domain,
            ),
            login.uris.single(),
        )
        assertEquals("credential-id", login.fido2Credentials.single().credentialId)
        assertEquals("example.com", login.fido2Credentials.single().rpId)
        assertEquals(Instant.parse("2024-01-03T00:00:00Z"), login.fido2Credentials.single().creationDate)
    }

    @Test
    fun `string data response maps camel-case canonical payload`() {
        val data = """
                {
                  "name": "String Data Cipher",
                  "notes": "String Data Notes",
                  "fields": [
                    {
                      "type": 1,
                      "name": "Secret question",
                      "value": "Second school"
                    }
                  ],
                  "passwordHistory": [
                    {
                      "password": "string-old-password",
                      "lastUsedDate": "2024-01-04T00:00:00Z"
                    }
                  ],
                  "uris": [
                    {
                      "uri": "https://string.example.com",
                      "uriChecksum": "string-uri-checksum",
                      "match": 1
                    }
                  ],
                  "username": "string-user",
                  "password": "string-password",
                  "passwordRevisionDate": "2024-01-05T00:00:00Z",
                  "totp": "string-totp-secret"
                }
            """.trimIndent()
        val entity = cipherEntityFromJson(
            type = CipherTypeEntity.Login,
            extraJson = """,
                "data": ${json.encodeToString(data)}
            """,
        )

        val cipher = decode(entity)

        assertEquals("String Data Cipher", cipher.name)
        assertEquals("String Data Notes", cipher.notes)
        assertEquals(
            BitwardenCipher.Field(
                type = BitwardenCipher.Field.Type.Hidden,
                name = "Secret question",
                value = "Second school",
                linkedId = null,
            ),
            cipher.fields.single(),
        )
        assertEquals(
            BitwardenCipher.Login.PasswordHistory(
                password = "string-old-password",
                lastUsedDate = Instant.parse("2024-01-04T00:00:00Z"),
            ),
            cipher.passwordHistory.single(),
        )

        val login = assertNotNull(cipher.login)
        assertEquals("string-user", login.username)
        assertEquals("string-password", login.password)
        assertEquals(Instant.parse("2024-01-05T00:00:00Z"), login.passwordRevisionDate)
        assertEquals("string-totp-secret", login.totp)
        assertEquals(
            BitwardenCipher.Login.Uri(
                uri = "https://string.example.com",
                uriChecksumBase64 = "string-uri-checksum",
                match = BitwardenCipher.Login.Uri.MatchType.Host,
            ),
            login.uris.single(),
        )
    }

    @Test
    fun `data payload takes precedence over legacy expanded fields`() {
        val entity = cipherEntityFromJson(
            type = CipherTypeEntity.Login,
            extraJson = """,
                "Name": "Legacy Cipher",
                "Notes": "Legacy Notes",
                "Fields": [
                  {
                    "Type": 0,
                    "Name": "Legacy field",
                    "Value": "Legacy value"
                  }
                ],
                "PasswordHistory": [
                  {
                    "Password": "legacy-password",
                    "LastUsedDate": "2024-01-01T00:00:00Z"
                  }
                ],
                "Login": {
                  "Username": "legacy-user",
                  "Password": "legacy-password"
                },
                "Data": {
                  "Name": "Data Cipher",
                  "Username": "data-user"
                }
            """,
        )

        val cipher = decode(entity)

        assertEquals("Data Cipher", cipher.name)
        assertNull(cipher.notes)
        assertTrue(cipher.fields.isEmpty())
        assertTrue(cipher.passwordHistory.isEmpty())
        assertEquals("data-user", assertNotNull(cipher.login).username)
        assertNull(assertNotNull(cipher.login).password)
    }

    @Test
    fun `legacy-only response still decodes expanded fields`() {
        val entity = cipherEntity(
            type = CipherTypeEntity.Login,
            name = "Legacy Cipher",
            notes = "Legacy Notes",
            fields = listOf(
                FieldEntity(
                    type = FieldTypeEntity.Linked,
                    name = "Legacy linked",
                    linkedId = LinkedIdTypeEntity.Login_Username,
                ),
            ),
            passwordHistory = listOf(
                PasswordHistoryEntity(
                    password = "legacy-password",
                    lastUsedDate = TEST_INSTANT,
                ),
            ),
            login = LoginEntity(
                username = "legacy-user",
                password = "legacy-password",
            ),
        )

        val cipher = decode(entity)

        assertEquals("Legacy Cipher", cipher.name)
        assertEquals("Legacy Notes", cipher.notes)
        assertEquals(BitwardenCipher.Field.Type.Linked, cipher.fields.single().type)
        assertEquals(BitwardenCipher.Field.LinkedId.Login_Username, cipher.fields.single().linkedId)
        assertEquals("legacy-password", cipher.passwordHistory.single().password)
        assertEquals("legacy-user", assertNotNull(cipher.login).username)
    }

    @Test
    fun `malformed data payload falls back to legacy expanded fields`() {
        val entity = cipherEntityFromJson(
            type = CipherTypeEntity.Login,
            extraJson = """,
                "Name": "Legacy Cipher",
                "Notes": "Legacy Notes",
                "Login": {
                  "Username": "legacy-user",
                  "Password": "legacy-password"
                },
                "Data": "{not-json"
            """,
        )

        val cipher = decode(entity)

        assertEquals("Legacy Cipher", cipher.name)
        assertEquals("Legacy Notes", cipher.notes)
        assertEquals("legacy-user", assertNotNull(cipher.login).username)
        assertEquals("legacy-password", assertNotNull(cipher.login).password)
    }

    @Test
    fun `identity data response maps server username field`() {
        val entity = cipherEntityFromJson(
            type = CipherTypeEntity.Identity,
            extraJson = """,
                "data": {
                  "Name": "Identity Cipher",
                  "FirstName": "Ada",
                  "LastName": "Lovelace",
                  "Username": "ada-user",
                  "PassportNumber": "P123"
                }
            """,
        )

        val cipher = decode(entity)

        assertEquals("Identity Cipher", cipher.name)
        val identity = assertNotNull(cipher.identity)
        assertEquals("Ada", identity.firstName)
        assertEquals("Lovelace", identity.lastName)
        assertEquals("ada-user", identity.username)
        assertEquals("P123", identity.passportNumber)
    }
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private fun decode(entity: CipherEntity): BitwardenCipher =
    BitwardenCipher.encrypted(
        accountId = "account-1",
        cipherId = "cipher-local-1",
        folderId = entity.folderId,
        entity = entity,
    )

private fun cipherEntity(
    type: CipherTypeEntity,
    name: String? = null,
    notes: String? = null,
    fields: List<FieldEntity>? = null,
    passwordHistory: List<PasswordHistoryEntity>? = null,
    login: LoginEntity? = null,
) = CipherEntity(
    id = "cipher-remote-1",
    revisionDate = TEST_INSTANT,
    type = type,
    name = name,
    notes = notes,
    fields = fields,
    passwordHistory = passwordHistory,
    login = login,
)

private fun cipherEntityFromJson(
    type: CipherTypeEntity,
    extraJson: String,
) = json.decodeFromString<CipherEntity>(
    """
    {
      "id": "cipher-remote-1",
      "revisionDate": "$TEST_INSTANT",
      "type": ${type.int}
      $extraJson
    }
    """.trimIndent(),
)

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}
