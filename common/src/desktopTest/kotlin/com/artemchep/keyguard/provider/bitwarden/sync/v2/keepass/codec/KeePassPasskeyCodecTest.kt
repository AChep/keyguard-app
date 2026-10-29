package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase64Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class KeePassPasskeyCodecTest {
    private val codec = KeePassPasskeyCodec(testBase64Service)

    @Test
    fun `encode writes the PRF secret as a concealed KeePassXC field`() {
        val writes = codec.encode(listOf(passkey()), sourceData = null).writes

        val value = assertNotNull(writes.firstOrNull { it.key == PRF_FIELD_KEY }?.value)
        assertEquals(SECRET, value.content)
        assertTrue(value is EntryValue.Encrypted)
    }

    @Test
    fun `encode writes a standard base64 secret as base64url`() {
        val writes = codec.encode(listOf(passkey(prfSecret = "+/+/")), sourceData = null).writes

        assertEquals("-_-_", writes.firstOrNull { it.key == PRF_FIELD_KEY }?.value?.content)
    }

    @Test
    fun `encode skips a passkey without a PRF secret`() {
        val writes = codec.encode(listOf(passkey(prfSecret = null)), sourceData = null).writes

        assertNull(writes.firstOrNull { it.key == PRF_FIELD_KEY })
    }

    @Test
    fun `encode-decode preserves the PRF secret`() {
        val writes = codec.encode(listOf(passkey()), sourceData = null).writes
        val entry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            fields = EntryFields.of(*writes.map { it.key to it.value }.toTypedArray()),
        )
        val scope = DecodeToCipherScope(entry)

        val decoded = codec.decode(scope, entry, CREATION_DATE)
        assertEquals(listOf(SECRET), decoded.credentials.map { it.prfSecret })
        assertTrue(scope.getAvailableFields().isEmpty())
    }

    @Test
    fun `decode reads the PRF secret of a KeePassXC passkey`() {
        val entry = keePassXcEntry(prfSecret = SECRET)

        val decoded = codec.decode(DecodeToCipherScope(entry), entry, CREATION_DATE)
        assertEquals(listOf(SECRET), decoded.credentials.map { it.prfSecret })
    }

    @Test
    fun `decode keeps a malformed PRF secret as a custom field`() {
        val entry = keePassXcEntry(prfSecret = "not base64url!")
        val scope = DecodeToCipherScope(entry)

        val decoded = codec.decode(scope, entry, CREATION_DATE)
        assertEquals(listOf(null), decoded.credentials.map { it.prfSecret })
        assertTrue(PRF_FIELD_KEY in scope.getAvailableFields().keys)
    }

    // KeePassXC uses the field as is, and the site knows the ID by its bytes.
    @Test
    fun `a UUID credential ID crosses the KPEX fields as the base64url of its bytes`() {
        val writes = codec.encode(listOf(passkey(credentialId = UUID_CREDENTIAL_ID)), sourceData = null).writes
        assertEquals(
            UUID_CREDENTIAL_ID_BASE64URL,
            writes.firstOrNull { it.key == CREDENTIAL_ID_FIELD_KEY }?.value?.content,
        )

        // Keyguard keeps the UUID form, and reads the one that older versions wrote.
        listOf(UUID_CREDENTIAL_ID_BASE64URL, UUID_CREDENTIAL_ID).forEach { credentialId ->
            val entry = keePassXcEntry(credentialId = credentialId)
            val decoded = codec.decode(DecodeToCipherScope(entry), entry, CREATION_DATE)
            assertEquals(listOf(UUID_CREDENTIAL_ID), decoded.credentials.map { it.credentialId }, credentialId)
        }
    }

    @Test
    fun `the blob fallback preserves the PRF secret`() = runTest {
        val cipherCodec = createTestCipherCodec()
        val local = testBitwardenCipher(cipherId = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12").copy(
            type = BitwardenCipher.Type.Login,
            secureNote = null,
            login = BitwardenCipher.Login(
                uris = emptyList(),
                fido2Credentials = listOf(
                    passkey(),
                    passkey(credentialId = "BAUG", prfSecret = "c2Vjb25k"),
                ),
            ),
        )

        val encoded = cipherCodec.encode(
            local = local,
            remote = null,
            existingBinaries = emptyMap(),
        )
        val decoded = cipherCodec.decode(
            accountId = local.accountId,
            folderId = null,
            cipherId = local.cipherId,
            remote = encoded.entry,
            local = null,
            revisionDate = CREATION_DATE,
            binaries = encoded.binaryAdditions,
        )

        assertEquals(
            listOf(SECRET, "c2Vjb25k"),
            decoded.login?.fido2Credentials?.map { it.prfSecret },
        )
        assertTrue(decoded.fields.isEmpty())
    }

    @Test
    fun `a PRF secret without user verification takes the blob`() = runTest {
        val cipherCodec = createTestCipherCodec()
        val passkey = passkey(prfSecretWithoutUv = SECRET_WITHOUT_UV)
        val local = testBitwardenCipher(cipherId = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12").copy(
            type = BitwardenCipher.Type.Login,
            secureNote = null,
            login = BitwardenCipher.Login(
                uris = emptyList(),
                fido2Credentials = listOf(passkey),
            ),
        )
        // KPEX has no field for it, so the KPEX fields would lose it.
        assertTrue(codec.encode(listOf(passkey), sourceData = null).writes.isEmpty())

        val encoded = cipherCodec.encode(
            local = local,
            remote = null,
            existingBinaries = emptyMap(),
        )
        val decoded = cipherCodec.decode(
            accountId = local.accountId,
            folderId = null,
            cipherId = local.cipherId,
            remote = encoded.entry,
            local = null,
            revisionDate = CREATION_DATE,
            binaries = encoded.binaryAdditions,
        )

        val credential = assertNotNull(decoded.login?.fido2Credentials?.singleOrNull())
        assertEquals(SECRET, credential.prfSecret)
        assertEquals(SECRET_WITHOUT_UV, credential.prfSecretWithoutUv)
    }

    private fun keePassXcEntry(
        prfSecret: String = SECRET,
        credentialId: String = "AQID",
    ) = Entry(
        uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        fields = EntryFields.of(
            "KPEX_PASSKEY_USERNAME" to EntryValue.Plain("alice"),
            CREDENTIAL_ID_FIELD_KEY to EntryValue.Encrypted(EncryptedValue.fromString(credentialId)),
            "KPEX_PASSKEY_PRIVATE_KEY_PEM" to EntryValue.Encrypted(
                EncryptedValue.fromString("-----BEGIN PRIVATE KEY-----\nAQID\n-----END PRIVATE KEY-----"),
            ),
            "KPEX_PASSKEY_RELYING_PARTY" to EntryValue.Plain("example.com"),
            "KPEX_PASSKEY_USER_HANDLE" to EntryValue.Encrypted(EncryptedValue.fromString("dXNlcg")),
            PRF_FIELD_KEY to EntryValue.Encrypted(EncryptedValue.fromString(prfSecret)),
        ),
    )

    private fun passkey(
        credentialId: String = "AQID",
        prfSecret: String? = SECRET,
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
        userHandle = "dXNlcg",
        userName = "alice",
        userDisplayName = "alice",
        discoverable = "true",
        creationDate = CREATION_DATE,
        prfSecret = prfSecret,
        prfSecretWithoutUv = prfSecretWithoutUv,
    )

    private companion object {
        // Spelled out independently of the production constant: KeePassXC reads it.
        const val PRF_FIELD_KEY = "KPEX_PASSKEY_PRF"
        const val CREDENTIAL_ID_FIELD_KEY = "KPEX_PASSKEY_CREDENTIAL_ID"

        const val UUID_CREDENTIAL_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val UUID_CREDENTIAL_ID_BASE64URL = "Ej5FZ-ibEtOkVkJmFBdAAA"

        const val SECRET = "Q34GXnI6mLLwjznYuvfFPss8NjxeUQS9qvXVyi4CgVQ"
        const val SECRET_WITHOUT_UV = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

        val CREATION_DATE = Instant.parse("2024-01-01T00:00:00Z")
    }
}
