package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Instant

class CipherKeyMaterialTest {
    @Test
    fun `new cipher without attachments gets generated key`() {
        val key = null.keyBase64OrGenerate(
            cryptoGenerator = CipherKeyTestCryptoGenerator,
            base64Service = CipherKeyTestBase64Service,
        )

        assertEquals("generated-cipher-key", key)
    }

    @Test
    fun `editing old no-key cipher gets generated key`() {
        val key = cipher(keyBase64 = null)
            .keyBase64OrGenerate(
                cryptoGenerator = CipherKeyTestCryptoGenerator,
                base64Service = CipherKeyTestBase64Service,
            )

        assertEquals("generated-cipher-key", key)
    }

    @Test
    fun `editing keyed cipher preserves key`() {
        val key = cipher(keyBase64 = "existing-cipher-key")
            .keyBase64OrGenerate(
                cryptoGenerator = CipherKeyTestCryptoGenerator,
                base64Service = CipherKeyTestBase64Service,
            )

        assertEquals("existing-cipher-key", key)
    }

    @Test
    fun `sync upload upgrades no-key local cipher`() {
        val upgraded = cipher(keyBase64 = null)
            .withCipherKeyBase64(
                cryptoGenerator = CipherKeyTestCryptoGenerator,
                base64Service = CipherKeyTestBase64Service,
            )

        assertEquals("generated-cipher-key", upgraded.keyBase64)
    }

    @Test
    fun `sync upload preserves keyed local cipher instance`() {
        val cipher = cipher(keyBase64 = "existing-cipher-key")
        val upgraded = cipher.withCipherKeyBase64(
            cryptoGenerator = CipherKeyTestCryptoGenerator,
            base64Service = CipherKeyTestBase64Service,
        )

        assertSame(cipher, upgraded)
    }
}

private fun cipher(
    keyBase64: String?,
) = BitwardenCipher(
    accountId = "account-1",
    cipherId = "cipher-1",
    revisionDate = CIPHER_KEY_TEST_INSTANT,
    createdDate = CIPHER_KEY_TEST_INSTANT,
    service = BitwardenService(),
    keyBase64 = keyBase64,
    name = "Cipher",
    notes = null,
    favorite = false,
    reprompt = BitwardenCipher.RepromptType.None,
    type = BitwardenCipher.Type.SecureNote,
    secureNote = BitwardenCipher.SecureNote(),
)

private object CipherKeyTestBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private object CipherKeyTestCryptoGenerator : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = byteArrayOf()

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = byteArrayOf()

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = byteArrayOf()

    override fun seed(length: Int): ByteArray = "generated-cipher-key".toByteArray()

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = byteArrayOf()

    override fun hashSha1(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashSha256(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashMd5(data: ByteArray): ByteArray = byteArrayOf()

    override fun uuid(): String = "uuid"

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = range.first
}

private val CIPHER_KEY_TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
