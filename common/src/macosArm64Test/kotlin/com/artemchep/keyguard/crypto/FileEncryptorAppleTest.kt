package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import kotlinx.io.Buffer
import platform.CoreCrypto.kCCEncrypt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileEncryptorAppleTest {
    private val cryptoGenerator = CryptoGeneratorApple()
    private val encryptor = FileEncryptorApple(
        cryptoGenerator = cryptoGenerator,
    )

    @Test
    fun byteArrayEncodeWritesFramedCiphertextThatDecodesBackToTheSource() {
        val data = ByteArray(100_000) { index ->
            (index % 251).toByte()
        }
        val key = ByteArray(64) { index ->
            index.toByte()
        }

        val encryptedBytes = encryptor.encode(
            data = data,
            key = key,
        )

        assertEquals(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte, encryptedBytes.first())
        assertTrue(encryptedBytes.size > FileEncryptionFormat.HEADER_LENGTH)
        assertContentEquals(data, encryptor.decode(encryptedBytes, key))
    }

    @Test
    fun byteArrayEncodeAndDecodeSupportsEmptyPayload() {
        val data = ByteArray(0)
        val key = ByteArray(64) { index ->
            index.toByte()
        }

        val encryptedBytes = encryptor.encode(
            data = data,
            key = key,
        )

        assertEquals(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte, encryptedBytes.first())
        assertTrue(encryptedBytes.size > FileEncryptionFormat.HEADER_LENGTH)
        assertContentEquals(data, encryptor.decode(encryptedBytes, key))
    }

    @Test
    fun byteArrayDecodeRejectsTamperedMac() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        tamperedBytes[1 + 16] = (tamperedBytes[1 + 16].toInt() xor 1).toByte()

        assertFailsWith<IllegalStateException> {
            encryptor.decode(tamperedBytes, key)
        }
    }

    @Test
    fun byteArrayDecodeRejectsTamperedCiphertext() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        tamperedBytes[tamperedBytes.lastIndex] = (tamperedBytes.last().toInt() xor 1).toByte()

        assertFailsWith<IllegalStateException> {
            encryptor.decode(tamperedBytes, key)
        }
    }

    @Test
    fun byteArrayDecodeRejectsWrongKey() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val wrongKey = ByteArray(64) { index ->
            (index + 1).toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        assertFailsWith<IllegalStateException> {
            encryptor.decode(encryptedBytes, wrongKey)
        }
    }

    @Test
    fun byteArrayDecodeRejectsTruncatedAuthenticatedFrame() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        assertFailsWith<IllegalStateException> {
            encryptor.decode(encryptedBytes.copyOf(FileEncryptionFormat.HEADER_LENGTH - 1), key)
        }
    }

    @Test
    fun byteArrayDecodeSupportsAesCbc128HmacSha256Frame() {
        val data = "plain text".encodeToByteArray()
        val key = ByteArray(32) { index ->
            index.toByte()
        }
        val encryptedBytes = createAesCbc128HmacSha256Frame(
            data = data,
            key = key,
        )

        assertContentEquals(data, encryptor.decode(encryptedBytes, key))
    }

    @Test
    fun decodeRejectsUnsupportedLegacyType() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = byteArrayOf(CipherEncryptor.Type.AesCbc256_B64.byte) + ByteArray(16)

        assertFailsWith<IllegalArgumentException> {
            encryptor.decode(encryptedBytes, key)
        }
    }

    @Test
    fun streamingEncodeIsUnsupported() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }

        assertFailsWith<UnsupportedOperationException> {
            encryptor.encode(
                input = Buffer(),
                output = com.artemchep.keyguard.platform.LocalPath("/tmp/never-written.bin"),
                key = key,
            )
        }
    }

    private fun createAesCbc128HmacSha256Frame(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val keys = FileEncryptionFormat.requireAesCbc128HmacSha256Keys(key)
        val iv = ByteArray(FileEncryptionFormat.IV_LENGTH) { index ->
            (index + 1).toByte()
        }
        val cipherText = aesCbcPkcs7(
            data = data,
            iv = iv,
            key = keys.encKey,
            operation = kCCEncrypt,
        )
        val mac = cryptoGenerator.hmacSha256(keys.macKey, iv + cipherText)
        return byteArrayOf(CipherEncryptor.Type.AesCbc128_HmacSha256_B64.byte) + iv + mac + cipherText
    }
}
