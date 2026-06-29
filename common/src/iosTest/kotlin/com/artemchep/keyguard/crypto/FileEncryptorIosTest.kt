package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.resolve
import com.artemchep.keyguard.platform.toKotlinxIoPath
import com.artemchep.keyguard.util.foundation.crypto.aesCbcPkcs7Encrypt
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class FileEncryptorIosTest {
    private val cryptoGenerator = CryptoGeneratorIos()
    private val encryptor = FileEncryptorIos(
        cryptoGenerator = cryptoGenerator,
    )

    @Test
    fun `streaming decode writes large payload`() {
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

        assertContentEquals(data, decode(encryptedBytes, key))
    }

    @Test
    fun `streaming decode supports empty payload`() {
        val data = ByteArray(0)
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = data,
            key = key,
        )

        assertContentEquals(data, decode(encryptedBytes, key))
    }

    @Test
    fun `streaming decode supports aes cbc 128 hmac sha256 frame`() {
        val data = "plain text".encodeToByteArray()
        val key = ByteArray(32) { index ->
            index.toByte()
        }
        val encryptedBytes = createAuthenticatedFrame(
            data = data,
            key = key,
            type = CipherEncryptor.Type.AesCbc128_HmacSha256_B64,
        )

        assertContentEquals(data, decode(encryptedBytes, key))
    }

    @Test
    fun `streaming decode rejects wrong key`() {
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

        assertFails {
            decode(encryptedBytes, wrongKey)
        }
    }

    @Test
    fun `streaming decode rejects tampered mac`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        val macOffset = FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH
        tamperedBytes[macOffset] = (tamperedBytes[macOffset].toInt() xor 1).toByte()

        assertFails {
            decode(tamperedBytes, key)
        }
    }

    @Test
    fun `streaming decode rejects tampered ciphertext`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        tamperedBytes[tamperedBytes.lastIndex] = (tamperedBytes.last().toInt() xor 1).toByte()

        assertFails {
            decode(tamperedBytes, key)
        }
    }

    @Test
    fun `streaming decode rejects truncated frame`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        assertFails {
            decode(encryptedBytes.copyOf(FileEncryptionFormat.HEADER_LENGTH - 1), key)
        }
    }

    private fun decode(
        encryptedBytes: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val output = tempFile()
        try {
            val source = Buffer().apply {
                write(encryptedBytes)
            }
            encryptor.decode(
                input = source,
                output = output,
                key = key,
            )
            return output.readBytes()
        } finally {
            output.deleteIfExists()
        }
    }

    private fun createAuthenticatedFrame(
        data: ByteArray,
        key: ByteArray,
        type: CipherEncryptor.Type,
    ): ByteArray {
        val keys = when (type) {
            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 ->
                FileEncryptionFormat.requireAesCbc128HmacSha256Keys(key)

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 ->
                FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)

            else -> error("Unsupported test type: $type")
        }
        val iv = ByteArray(FileEncryptionFormat.IV_LENGTH) { index ->
            (index + 1).toByte()
        }
        val cipherText = aesCbcPkcs7Encrypt(
            key = keys.encKey,
            iv = iv,
            data = data,
        )
        val mac = cryptoGenerator.hmacSha256(keys.macKey, iv + cipherText)
        return byteArrayOf(type.byte) + iv + mac + cipherText
    }
}

private fun tempFile(): LocalPath {
    val dir = LocalPath(NSTemporaryDirectory())
        .resolve("keyguard-ios-tests")
    SystemFileSystem.createDirectories(dir.toKotlinxIoPath())
    return dir.resolve("${NSUUID().UUIDString}.bin")
}

private fun LocalPath.readBytes(): ByteArray =
    SystemFileSystem.source(toKotlinxIoPath())
        .buffered()
        .use { source ->
            source.readByteArray()
        }

private fun LocalPath.deleteIfExists() {
    val path = toKotlinxIoPath()
    if (SystemFileSystem.exists(path)) {
        SystemFileSystem.delete(path)
    }
}
