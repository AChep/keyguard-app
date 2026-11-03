package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherCryptoPasskeyTest {
    @Test
    fun `PRF secrets cross the transport as a custom field after the user fields`() {
        val cipher = cipher(passkey("cred-1", SECRET), passkey("cred-2", prfSecret = null))
            .copy(fields = listOf(userField))
        val encrypted = cipher.transform(identityEncrypt, identityEncrypt)
        assertEquals(listOf(null, null), encrypted.login?.fido2Credentials?.map { it.prfSecret })
        assertEquals(
            listOf(
                userField,
                BitwardenCipher.Field(
                    name = FIELD_NAME,
                    value = """{"version":1,"credentials":[{"credentialId":"cred-1","prfSecret":"$SECRET"}]}""",
                    type = BitwardenCipher.Field.Type.Hidden,
                ),
            ),
            encrypted.fields,
        )

        val decrypted = encrypted.transform(identityDecrypt, identityDecrypt)
        assertEquals(cipher.login, decrypted.login)
        assertEquals(listOf(userField), decrypted.fields)
    }

    @Test
    fun `a PRF secret without user verification crosses the transport too`() {
        val cipher = cipher(passkey("cred-1", SECRET, prfSecretWithoutUv = SECRET_WITHOUT_UV))
        val encrypted = cipher.transform(identityEncrypt, identityEncrypt)
        assertEquals(listOf(null), encrypted.login?.fido2Credentials?.map { it.prfSecretWithoutUv })
        assertEquals(
            listOf(
                "{\"version\":1,\"credentials\":[{\"credentialId\":\"cred-1\",\"prfSecret\":\"$SECRET\"," +
                    "\"prfSecretWithoutUv\":\"$SECRET_WITHOUT_UV\"}]}",
            ),
            encrypted.fields.map { it.value },
        )

        val decrypted = encrypted.transform(identityDecrypt, identityDecrypt)
        assertEquals(cipher.login, decrypted.login)
    }

    @Test
    fun `passkeys without a PRF secret write no field`() {
        val encrypted = cipher(passkey("cred-1", prfSecret = null)).transform(identityEncrypt, identityEncrypt)
        assertTrue(encrypted.fields.isEmpty())
    }

    @Test
    fun `new secrets replace the old field`() {
        val stale = BitwardenCipher.Field(
            name = FIELD_NAME,
            value = "stale",
            type = BitwardenCipher.Field.Type.Hidden,
        )
        val encrypted = cipher(passkey("cred-1", SECRET)).copy(fields = listOf(stale))
            .transform(identityEncrypt, identityEncrypt)
        assertEquals(listOf(FIELD_NAME), encrypted.fields.map { it.name })
        assertTrue(encrypted.fields.none { it.value == "stale" })
    }

    // A version without the passkey codec keeps the field as a plain custom field.
    @Test
    fun `a field that was never decoded keeps the secrets of other passkeys`() {
        val kept = hidden("""{"version":1,"credentials":[{"credentialId":"cred-1","prfSecret":"$SECRET"}]}""")
        val cipher = cipher(passkey("cred-1", prfSecret = null), passkey("cred-2", OTHER_SECRET))
            .copy(fields = listOf(kept, userField))
        val encrypted = cipher.transform(identityEncrypt, identityEncrypt)
        assertEquals(
            listOf(
                userField,
                hidden(
                    "{\"version\":1,\"credentials\":[{\"credentialId\":\"cred-1\",\"prfSecret\":\"$SECRET\"}," +
                        "{\"credentialId\":\"cred-2\",\"prfSecret\":\"$OTHER_SECRET\"}]}",
                ),
            ),
            encrypted.fields,
        )

        val decrypted = encrypted.transform(identityDecrypt, identityDecrypt)
        assertEquals(listOf(SECRET, OTHER_SECRET), decrypted.login?.fido2Credentials?.map { it.prfSecret })
        assertEquals(listOf(userField), decrypted.fields)
    }

    @Test
    fun `a field with no secrets to write stays as it is`() {
        // The entry names no passkey of the login.
        val kept = hidden("""{"version":1,"credentials":[{"credentialId":"cred-9","prfSecret":"$SECRET"}]}""")
        val encrypted = cipher(passkey("cred-1", prfSecret = null)).copy(fields = listOf(kept))
            .transform(identityEncrypt, identityEncrypt)
        assertEquals(listOf(kept), encrypted.fields)
    }

    @Test
    fun `an unknown document stays a custom field`() {
        val future = BitwardenCipher.Field(
            name = FIELD_NAME,
            value = """{"version":2}""",
            type = BitwardenCipher.Field.Type.Hidden,
        )
        val decrypted = cipher(passkey("cred-1", prfSecret = null)).copy(fields = listOf(future))
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(listOf(future), decrypted.fields)

        val encrypted = decrypted.transform(identityEncrypt, identityEncrypt)
        assertEquals(listOf(future), encrypted.fields)
    }

    @Test
    fun `other cipher types keep fields under the passkey name`() {
        val field = BitwardenCipher.Field(
            name = FIELD_NAME,
            value = """{"version":1,"credentials":[]}""",
            type = BitwardenCipher.Field.Type.Hidden,
        )
        val note = cipher().copy(
            type = BitwardenCipher.Type.SecureNote,
            login = null,
            secureNote = BitwardenCipher.SecureNote(),
            fields = listOf(field),
        )
        val decrypted = note
            .transform(identityEncrypt, identityEncrypt)
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(listOf(field), decrypted.fields)
    }

    private val userField = BitwardenCipher.Field(
        name = "Email",
        value = "alice@example.com",
        type = BitwardenCipher.Field.Type.Text,
    )

    private fun hidden(value: String) = BitwardenCipher.Field(
        name = FIELD_NAME,
        value = value,
        type = BitwardenCipher.Field.Type.Hidden,
    )

    private fun cipher(vararg passkeys: BitwardenCipher.Login.Fido2Credentials) = BitwardenCipher(
        accountId = "account", cipherId = "login", revisionDate = Instant.fromEpochSeconds(0),
        service = BitwardenService(), name = "Login", notes = null, favorite = false,
        reprompt = BitwardenCipher.RepromptType.None, type = BitwardenCipher.Type.Login,
        login = BitwardenCipher.Login(
            uris = emptyList(),
            fido2Credentials = passkeys.toList(),
        ),
    )

    private fun passkey(
        credentialId: String,
        prfSecret: String?,
        prfSecretWithoutUv: String? = null,
    ) = BitwardenCipher.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "AQID",
        rpId = "example.com",
        rpName = null,
        counter = "0",
        discoverable = "true",
        creationDate = Instant.fromEpochSeconds(0),
        prfSecret = prfSecret,
        prfSecretWithoutUv = prfSecretWithoutUv,
    )

    private companion object {
        // Spelled out independently of the production constant, so that
        // a change of the field name breaks the tests.
        const val FIELD_NAME = "keyguard.passkeys"

        const val SECRET = "Q34GXnI6mLLwjznYuvfFPss8NjxeUQS9qvXVyi4CgVQ"
        const val SECRET_WITHOUT_UV = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val OTHER_SECRET = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"
    }
}
