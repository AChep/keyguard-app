package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherCryptoGpgChunkTest {
    @Test
    fun `short and oversized keys round trip through the transport boundary`() {
        val keys = listOf(
            BitwardenCipher.GpgKey(
                privateKeyArmored = "short private key",
                publicKeyArmored = "short public key",
                fingerprint = GPG_FINGERPRINT,
            ),
            BitwardenCipher.GpgKey(
                privateKeyArmored = "p".repeat(GPG_CHUNK_BYTES + 1),
                publicKeyArmored = "u".repeat(GPG_CHUNK_BYTES * 2 + 1),
                fingerprint = GPG_FINGERPRINT,
            ),
        )

        keys.forEach { gpgKey ->
            val encrypted = gpgChunkCipher(gpgKey = gpgKey).encryptGpgForTest()
            val decrypted = encrypted.decryptGpgForTest()

            assertNull(encrypted.gpgKey)
            assertEquals(gpgKey, decrypted.gpgKey)
            assertEquals(BitwardenCipher.Type.GpgKey, decrypted.type)
            assertTrue(decrypted.fields.isEmpty())
        }
    }

    @Test
    fun `maximum chunks stay below the encrypted Bitwarden field limit`() {
        val privateKey = "a".repeat(GPG_CHUNK_BYTES + 1)
        val chunks = gpgChunkCipher(
            gpgKey = BitwardenCipher.GpgKey(privateKeyArmored = privateKey),
        )
            .encryptGpgForTest()
            .fields
            .filter { it.name?.startsWith(privatePartPrefix()) == true }
            .map { it.value!! }
        val encryptor = NativeCipherEncryptor(
            cryptoGenerator = NativeCryptoGenerator(),
            base64Service = Base64ServiceImpl(),
        )
        val key = SymmetricCryptoKey2(ByteArray(64) { index -> index.toByte() })

        chunks.forEach { chunk ->
            val encrypted = encryptor.encode2(
                cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
                plainText = chunk.encodeToByteArray(),
                symmetricCryptoKey = key,
            )
            assertTrue(
                encrypted.length < BITWARDEN_ENCRYPTED_FIELD_LIMIT,
                "Encrypted ${chunk.encodeToByteArray().size}-byte chunk was ${encrypted.length} characters.",
            )
        }
    }

    @Test
    fun `malformed chunks preserve the whole gpg aggregate without promotion`() {
        val encrypted = gpgChunkCipher(
            gpgKey = BitwardenCipher.GpgKey(
                privateKeyArmored = "p".repeat(GPG_CHUNK_BYTES + 1),
                publicKeyArmored = "public key",
                fingerprint = GPG_FINGERPRINT,
            ),
        ).encryptGpgForTest()
        val malformedFields = encrypted.fields.map { field ->
            if (field.name == privatePartName(1)) {
                field.copy(value = "modified")
            } else {
                field
            }
        }

        val decrypted = encrypted
            .copy(
                type = BitwardenCipher.Type.SecureNote,
                fields = malformedFields,
            )
            .decryptGpgForTest()

        assertEquals(BitwardenCipher.Type.SecureNote, decrypted.type)
        assertNull(decrypted.gpgKey)
        assertEquals(malformedFields, decrypted.fields)
    }

    @Test
    fun `decoded transport key replaces an existing key atomically`() {
        val existing = BitwardenCipher.GpgKey(
            privateKeyArmored = "existing private key",
            fingerprint = "existing fingerprint",
        )
        val fields = listOf(
            gpgField(
                name = GpgAgentFields.PUBLIC_KEY_ARMORED,
                value = "wire public key",
                type = BitwardenCipher.Field.Type.Text,
            ),
            gpgField(
                name = GpgAgentFields.FINGERPRINT,
                value = GPG_FINGERPRINT,
                type = BitwardenCipher.Field.Type.Text,
            ),
        )

        val decrypted = gpgChunkCipher(
            gpgKey = existing,
            fields = fields,
            type = BitwardenCipher.Type.SecureNote,
        ).decryptGpgForTest()

        assertEquals(
            BitwardenCipher.GpgKey(
                publicKeyArmored = "wire public key",
                fingerprint = GPG_FINGERPRINT,
            ),
            decrypted.gpgKey,
        )
        assertEquals(BitwardenCipher.Type.GpgKey, decrypted.type)
        assertTrue(decrypted.fields.isEmpty())
    }

    @Test
    fun `rejected transport key preserves an existing key`() {
        val existing = BitwardenCipher.GpgKey(
            privateKeyArmored = "existing private key",
            fingerprint = "existing fingerprint",
        )
        val fields = listOf(
            gpgField(
                name = privatePartName(1),
                value = "incomplete chunk",
                type = BitwardenCipher.Field.Type.Hidden,
            ),
        )

        val decrypted = gpgChunkCipher(
            gpgKey = existing,
            fields = fields,
            type = BitwardenCipher.Type.SecureNote,
        ).decryptGpgForTest()

        assertEquals(existing, decrypted.gpgKey)
        assertEquals(BitwardenCipher.Type.SecureNote, decrypted.type)
        assertEquals(fields, decrypted.fields)
    }

    @Test
    fun `legacy oversized values migrate lazily and unrelated fields survive`() {
        val privateKey = "a".repeat(GPG_CHUNK_BYTES + 1)
        val userField = gpgField(
            name = "User field",
            value = "keep me",
            type = BitwardenCipher.Field.Type.Text,
        )
        val legacy = gpgChunkCipher(
            type = BitwardenCipher.Type.SecureNote,
            fields = listOf(
                gpgField(
                    name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                    value = privateKey,
                    type = BitwardenCipher.Field.Type.Hidden,
                ),
                userField,
            ),
        ).decryptGpgForTest()

        assertEquals(BitwardenCipher.Type.GpgKey, legacy.type)
        assertEquals(privateKey, legacy.gpgKey?.privateKeyArmored)
        assertEquals(listOf(userField), legacy.fields)

        val migrated = legacy.encryptGpgForTest()
        assertTrue(migrated.fields.any { it.name == privatePartName(1) })
        assertTrue(migrated.fields.any { it.name == privateHashName() })
        assertTrue(migrated.fields.any { it.name == userField.name && it.value == userField.value })
        assertTrue(
            migrated.fields.none { field ->
                field.name == GpgAgentFields.PRIVATE_KEY_ARMORED
            },
        )
    }

    @Test
    fun `unsupported cipher types leave chunk fields untouched`() {
        val chunkFields = gpgChunkCipher(
            gpgKey = BitwardenCipher.GpgKey(
                publicKeyArmored = "p".repeat(GPG_CHUNK_BYTES + 1),
            ),
        ).encryptGpgForTest().fields

        val decrypted = gpgChunkCipher(
            type = BitwardenCipher.Type.Login,
            fields = chunkFields,
        ).decryptGpgForTest()

        assertEquals(BitwardenCipher.Type.Login, decrypted.type)
        assertNull(decrypted.gpgKey)
        assertEquals(chunkFields, decrypted.fields)
    }
}

private fun gpgChunkCipher(
    gpgKey: BitwardenCipher.GpgKey? = null,
    fields: List<BitwardenCipher.Field> = emptyList(),
    type: BitwardenCipher.Type = BitwardenCipher.Type.GpgKey,
) = BitwardenCipher(
    accountId = "account-1",
    cipherId = "cipher-1",
    revisionDate = GPG_TEST_INSTANT,
    createdDate = GPG_TEST_INSTANT,
    service = BitwardenService(),
    name = "GPG key",
    notes = null,
    favorite = false,
    fields = fields,
    gpgKey = gpgKey,
    reprompt = BitwardenCipher.RepromptType.None,
    type = type,
    secureNote = BitwardenCipher.SecureNote(),
)

private fun BitwardenCipher.encryptGpgForTest() = transform(
    itemCrypto = identityEncrypt,
    globalCrypto = identityEncrypt,
)

private fun BitwardenCipher.decryptGpgForTest() = transform(
    itemCrypto = identityDecrypt,
    globalCrypto = identityDecrypt,
)

private fun privatePartPrefix() = gpgChunkPartPrefix(GpgAgentFields.PRIVATE_KEY_ARMORED)

private fun privatePartName(index: Int) = gpgChunkPartName(GpgAgentFields.PRIVATE_KEY_ARMORED, index)

private fun privateHashName() = gpgChunkHashName(GpgAgentFields.PRIVATE_KEY_ARMORED)

private const val BITWARDEN_ENCRYPTED_FIELD_LIMIT = 5_000

private val GPG_TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
