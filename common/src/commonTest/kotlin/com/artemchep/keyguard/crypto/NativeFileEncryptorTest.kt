package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toKotlinxIoPath
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeFileEncryptorTest {
    private val cryptoGenerator = NativeCryptoGenerator()
    private val encryptor = NativeFileEncryptor(
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
    fun keyHelpersRejectNonExactAesKeySizes() {
        listOf(31, 33).forEach { size ->
            assertFailsWith<IllegalStateException> {
                FileEncryptionFormat.requireAesCbc128HmacSha256Keys(ByteArray(size))
            }
        }
        listOf(63, 65).forEach { size ->
            assertFailsWith<IllegalStateException> {
                FileEncryptionFormat.requireAesCbc256HmacSha256Keys(ByteArray(size))
            }
        }
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
    fun streamingEncodeWritesFramedCiphertextThatDecodesBackToTheSource() {
        val data = ByteArray(100_000) { index -> (index % 251).toByte() }
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val output = LocalPath("/tmp/keyguard-file-encryptor-${cryptoGenerator.uuid()}.bin")

        try {
            val result = encryptor.encode(
                input = Buffer().apply { write(data) },
                output = output,
                key = key,
            )
            val encryptedBytes = SystemFileSystem.source(output.toKotlinxIoPath())
                .buffered()
                .use { source -> source.readByteArray() }

            assertEquals(data.size.toLong(), result.plainSize)
            assertEquals(encryptedBytes.size.toLong(), result.encryptedSize)
            assertContentEquals(data, encryptor.decode(encryptedBytes, key))
        } finally {
            val path = output.toKotlinxIoPath()
            if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        }
    }

    @Test
    fun streamingDecodeReadsFusedChunksAcross64KiBBoundary() {
        val data = ByteArray(100_003) { index -> (index * 31 + 7).toByte() }
        val key = ByteArray(64) { index -> index.toByte() }
        val encryptedBytes = encryptor.encode(data, key)
        val output = LocalPath("/tmp/keyguard-file-decryptor-${cryptoGenerator.uuid()}.bin")

        try {
            encryptor.decode(
                input = Buffer().apply { write(encryptedBytes) },
                output = output,
                key = key,
            )
            val decryptedBytes = SystemFileSystem.source(output.toKotlinxIoPath())
                .buffered()
                .use { source -> source.readByteArray() }

            assertContentEquals(data, decryptedBytes)
        } finally {
            val path = output.toKotlinxIoPath()
            if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        }
    }

    @Test
    fun streamingDecodeReadsExistingAesCbc128HmacSha256Frames() {
        val data = "existing encrypted file".encodeToByteArray()
        val key = ByteArray(32) { index -> index.toByte() }
        val encryptedBytes = createAesCbc128HmacSha256Frame(
            data = data,
            key = key,
        )
        val output = LocalPath("/tmp/keyguard-file-decryptor-${cryptoGenerator.uuid()}.bin")

        try {
            encryptor.decode(
                input = Buffer().apply { write(encryptedBytes) },
                output = output,
                key = key,
            )
            val decryptedBytes = SystemFileSystem.source(output.toKotlinxIoPath())
                .buffered()
                .use { source -> source.readByteArray() }

            assertContentEquals(data, decryptedBytes)
        } finally {
            val path = output.toKotlinxIoPath()
            if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
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
        val cipherText = NativeCryptoPrimitives.aesCbcPkcs7Encrypt(
            key = keys.encKey,
            iv = iv,
            data = data,
        )
        val mac = cryptoGenerator.hmacSha256(keys.macKey, iv + cipherText)
        return byteArrayOf(CipherEncryptor.Type.AesCbc128_HmacSha256_B64.byte) + iv + mac + cipherText
    }
}
