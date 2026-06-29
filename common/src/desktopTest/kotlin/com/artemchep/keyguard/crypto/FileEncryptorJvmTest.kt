package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.crypto.util.createAesCbc
import com.artemchep.keyguard.crypto.util.encode
import com.artemchep.keyguard.platform.toLocalPath
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileEncryptorJvmTest {
    private val encryptor = FileEncryptorJvm(
        cryptoGenerator = CryptoGeneratorJvm(),
    )
    private val cryptoGenerator = CryptoGeneratorJvm()
    private val fileService = FileServiceImpl()

    @Test
    fun `byte array encode writes framed ciphertext that decodes back to the source`() {
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
        assertContentEquals(data, encryptor.decode(ByteArrayInputStream(encryptedBytes), key).readBytes())
    }

    @Test
    fun `byte array encode and decode supports empty payload`() {
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
        assertContentEquals(data, encryptor.decode(ByteArrayInputStream(encryptedBytes), key).readBytes())
    }

    @Test
    fun `streaming encode writes framed ciphertext that decodes back to the source`() {
        val root = createTempDirectory("file-encryptor-jvm")
        val input = root.resolve("plain.bin")
        val output = root.resolve("encrypted.bin")
        val data = ByteArray(100_000) { index ->
            (index % 251).toByte()
        }
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        input.writeBytes(data)

        val result = fileService.readFromFile(input.toUri().toString()).use { source ->
            encryptor.encode(
                input = source,
                output = output.toLocalPath(),
                key = key,
            )
        }
        val encryptedBytes = output.readBytes()

        assertEquals(data.size.toLong(), result.plainSize)
        assertEquals(encryptedBytes.size.toLong(), result.encryptedSize)
        assertEquals(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte, encryptedBytes.first())
        assertTrue(encryptedBytes.size > 1 + 16 + 32)
        assertContentEquals(data, encryptor.decode(encryptedBytes, key))
    }

    @Test
    fun `streaming encode supports empty source`() {
        val root = createTempDirectory("file-encryptor-jvm")
        val input = root.resolve("plain.bin")
        val output = root.resolve("encrypted.bin")
        val data = ByteArray(0)
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        input.writeBytes(data)

        val result = fileService.readFromFile(input.toUri().toString()).use { source ->
            encryptor.encode(
                input = source,
                output = output.toLocalPath(),
                key = key,
            )
        }
        val encryptedBytes = output.readBytes()

        assertEquals(0L, result.plainSize)
        assertEquals(encryptedBytes.size.toLong(), result.encryptedSize)
        assertEquals(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte, encryptedBytes.first())
        assertTrue(encryptedBytes.size > FileEncryptionFormat.HEADER_LENGTH)
        assertContentEquals(data, encryptor.decode(encryptedBytes, key))
    }

    @Test
    fun `byte array decode rejects tampered mac`() {
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
    fun `byte array decode rejects tampered ciphertext`() {
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
    fun `byte array decode rejects wrong key`() {
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
    fun `byte array decode rejects truncated authenticated frame`() {
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
    fun `byte array decode supports aes cbc 128 hmac sha256 frame`() {
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
    fun `key helpers reject non exact aes key sizes`() {
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
    fun `streaming decode validates mac at end of stream`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = ByteArray(100_000) { index ->
                (index % 251).toByte()
            },
            key = key,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        tamperedBytes[tamperedBytes.lastIndex] = (tamperedBytes.last().toInt() xor 1).toByte()

        assertFailsWith<IOException> {
            encryptor
                .decode(ByteArrayInputStream(tamperedBytes), key)
                .readBytes()
        }
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

        assertFailsWith<IOException> {
            encryptor
                .decode(ByteArrayInputStream(encryptedBytes), wrongKey)
                .readBytes()
        }
    }

    @Test
    fun `streaming decode rejects truncated authenticated frame`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        assertFailsWith<IOException> {
            encryptor
                .decode(ByteArrayInputStream(encryptedBytes.copyOf(FileEncryptionFormat.HEADER_LENGTH - 1)), key)
                .readBytes()
        }
    }

    @Test
    fun `streaming decode close before consuming full input validates partial mac`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = encryptor.encode(
            data = ByteArray(100_000) { index ->
                (index % 251).toByte()
            },
            key = key,
        )
        val input = encryptor.decode(ByteArrayInputStream(encryptedBytes), key)

        input.read(ByteArray(64))

        assertFailsWith<IOException> {
            input.close()
        }
    }

    @Test
    fun `streaming decode handles fragmented header`() {
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

        val input = object : ByteArrayInputStream(encryptedBytes) {
            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = super.read(b, off, minOf(len, 7))
        }

        assertContentEquals(data, encryptor.decode(input, key).readBytes())
    }

    @Test
    fun `decode rejects unsupported legacy type`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = byteArrayOf(CipherEncryptor.Type.AesCbc256_B64.byte) + ByteArray(16)

        assertFailsWith<IllegalArgumentException> {
            encryptor.decode(encryptedBytes, key)
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
        val cipherText = createAesCbc(
            iv = iv,
            key = keys.encKey,
            forEncryption = true,
        ).encode(data)
        val mac = cryptoGenerator.hmacSha256(keys.macKey, iv + cipherText)
        return byteArrayOf(CipherEncryptor.Type.AesCbc128_HmacSha256_B64.byte) + iv + mac + cipherText
    }
}
