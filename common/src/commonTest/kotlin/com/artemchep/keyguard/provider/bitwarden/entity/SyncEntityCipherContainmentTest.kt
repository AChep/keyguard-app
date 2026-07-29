package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One unreadable cipher must cost that cipher, not the response.
 *
 * `SyncEntity.ciphers` is decoded in a single pass, so before
 * [CipherEntityListSerializer] a member the decoder refused anywhere inside any
 * cipher emptied the entire vault. The case that shipped was a passkey: several
 * of `LoginFido2CredentialsEntity`'s members are required while the server's own
 * model guarantees none of them, so a server that omits one used to take every
 * other cipher down with it — including the ones holding no passkey at all.
 *
 * These cases are deliberately written against `SyncEntity` rather than the
 * serializer, because the property binding is half of the fix: registering the
 * serializer contextually on one `Json` would silently not apply on the other
 * platform's instance.
 */
class SyncEntityCipherContainmentTest {
    @Test
    fun `a cipher with an unreadable passkey is dropped and its siblings survive`() {
        val sync = json.decodeFromString<SyncEntity>(
            syncJson(middleCipherCredential = CREDENTIAL_MISSING_KEY_VALUE),
        )

        // The bad cipher is gone; the two that never had a passkey are intact.
        assertEquals(listOf("cipher-1", "cipher-3"), sync.ciphers.orEmpty().map { it.id })
    }

    @Test
    fun `an unparsable creation date costs one cipher rather than the vault`() {
        // Deliberately not fixed at the serializer: `creationDate` is strict, and a
        // server spelling it with seven fractional digits and no offset is a real
        // shape. Containment is what makes that survivable, so it is pinned here.
        val sync = json.decodeFromString<SyncEntity>(
            syncJson(middleCipherCredential = CREDENTIAL_BAD_CREATION_DATE),
        )

        assertEquals(listOf("cipher-1", "cipher-3"), sync.ciphers.orEmpty().map { it.id })
    }

    @Test
    fun `a well-formed passkey still decodes, so the containment is not hiding a broken model`() {
        // The counterweight: without this, dropping every cipher unconditionally
        // would also pass the two cases above.
        val sync = json.decodeFromString<SyncEntity>(
            syncJson(middleCipherCredential = CREDENTIAL_VALID),
        )

        assertEquals(
            listOf("cipher-1", "cipher-2", "cipher-3"),
            sync.ciphers.orEmpty().map { it.id },
        )
    }

    @Test
    fun `an absent key profile falls back to the only profile Keyguard supports`() {
        val entity = json.decodeFromString<LoginFido2CredentialsEntity>(CREDENTIAL_NO_KEY_PROFILE)

        assertEquals("public-key", entity.keyType)
        assertEquals("ECDSA", entity.keyAlgorithm)
        assertEquals("P-256", entity.keyCurve)
    }

    @Test
    fun `a stated key profile is not overwritten by the default`() {
        // Guards the defaults against masking a genuinely different profile, which
        // would otherwise be indistinguishable from absence.
        val entity = json.decodeFromString<LoginFido2CredentialsEntity>(CREDENTIAL_OTHER_KEY_PROFILE)

        assertEquals("RSA", entity.keyAlgorithm)
        assertEquals("P-384", entity.keyCurve)
    }

    private fun syncJson(middleCipherCredential: String) = """
    {
      "profile": $PROFILE,
      "ciphers": [
        {"id": "cipher-1", "type": 1, "revisionDate": "2024-01-01T00:00:00Z",
         "login": {"username": "alice"}},
        {"id": "cipher-2", "type": 1, "revisionDate": "2024-01-01T00:00:00Z",
         "login": {"username": "bob", "fido2Credentials": [$middleCipherCredential]}},
        {"id": "cipher-3", "type": 1, "revisionDate": "2024-01-01T00:00:00Z",
         "login": {"username": "carol"}}
      ]
    }
    """.trimIndent()

    /**
     * The flags of the application-wide instances bound in `GlobalModuleJvm` and
     * `IosAppModule`, which are what actually decode a sync response.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private companion object {
        /** Only the members `ProfileEntity` requires; nothing here is under test. */
        private const val PROFILE = """
        {"id":"profile-1","culture":"en-US","email":"alice@example.com",
         "emailVerified":true,"key":"key","privateKey":"private-key","object":"profile",
         "premium":false,"securityStamp":"stamp","twoFactorEnabled":false}
        """

        /**
         * Every optional member is spelled out on purpose: these cases pin the
         * containment, not the nullability of `rpName`/`userHandle`, and stating
         * them keeps this file decodable against either shape of the entity.
         */
        private const val CREDENTIAL_VALID = """
        {"credentialId":"Y3JlZC1pZA","keyType":"public-key","keyAlgorithm":"ECDSA",
         "keyCurve":"P-256","keyValue":"a2V5","rpId":"example.com","rpName":"Example",
         "userHandle":"AAECAwQFBg","counter":"0","discoverable":"true",
         "creationDate":"2024-01-01T00:00:00Z"}
        """

        /** `keyValue` is required and a passkey really is unusable without it. */
        private const val CREDENTIAL_MISSING_KEY_VALUE = """
        {"credentialId":"Y3JlZC1pZA","keyType":"public-key","keyAlgorithm":"ECDSA",
         "keyCurve":"P-256","rpId":"example.com","rpName":"Example",
         "userHandle":"AAECAwQFBg","counter":"0","discoverable":"true",
         "creationDate":"2024-01-01T00:00:00Z"}
        """

        private const val CREDENTIAL_BAD_CREATION_DATE = """
        {"credentialId":"Y3JlZC1pZA","keyType":"public-key","keyAlgorithm":"ECDSA",
         "keyCurve":"P-256","keyValue":"a2V5","rpId":"example.com","rpName":"Example",
         "userHandle":"AAECAwQFBg","counter":"0","discoverable":"true",
         "creationDate":"2024-01-01T00:00:00.1234567"}
        """

        private const val CREDENTIAL_NO_KEY_PROFILE = """
        {"credentialId":"Y3JlZC1pZA","keyValue":"a2V5","rpId":"example.com",
         "rpName":"Example","userHandle":"AAECAwQFBg","counter":"0",
         "discoverable":"true","creationDate":"2024-01-01T00:00:00Z"}
        """

        private const val CREDENTIAL_OTHER_KEY_PROFILE = """
        {"credentialId":"Y3JlZC1pZA","keyType":"public-key","keyAlgorithm":"RSA",
         "keyCurve":"P-384","keyValue":"a2V5","rpId":"example.com","rpName":"Example",
         "userHandle":"AAECAwQFBg","counter":"0","discoverable":"true",
         "creationDate":"2024-01-01T00:00:00Z"}
        """
    }
}
