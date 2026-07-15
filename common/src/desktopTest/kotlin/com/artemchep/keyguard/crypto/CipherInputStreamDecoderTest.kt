package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CipherInputStreamDecoderTest {
    private val cryptoGenerator = CryptoGeneratorJvm()

    @Test
    fun `decodes aes cbc 256 hmac sha256 frame`() {
        val data = ByteArray(100_000) { index ->
            (index % 251).toByte()
        }
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = createAuthenticatedFrame(
            data = data,
            key = key,
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )

        assertContentEquals(data, decode(encryptedBytes, key))
    }

    @Test
    fun `decodes aes cbc 128 hmac sha256 frame`() {
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
    fun `decodes frame when header is split across reads`() {
        val data = ByteArray(100_000) { index ->
            (index % 251).toByte()
        }
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = createAuthenticatedFrame(
            data = data,
            key = key,
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )

        val input = object : ByteArrayInputStream(encryptedBytes) {
            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = super.read(b, off, minOf(len, 7))
        }

        assertContentEquals(data, decode(input, key))
    }

    @Test
    fun `rejects tampered mac`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = createAuthenticatedFrame(
            data = "plain text".encodeToByteArray(),
            key = key,
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        val macOffset = FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH
        tamperedBytes[macOffset] = (tamperedBytes[macOffset].toInt() xor 1).toByte()

        assertFailsWith<IOException> {
            decode(tamperedBytes, key)
        }
    }

    @Test
    fun `rejects tampered ciphertext`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = createAuthenticatedFrame(
            data = "plain text".encodeToByteArray(),
            key = key,
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        tamperedBytes[tamperedBytes.lastIndex] = (tamperedBytes.last().toInt() xor 1).toByte()

        assertFailsWith<IOException> {
            decode(tamperedBytes, key)
        }
    }

    @Test
    fun `rejects truncated header`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = createAuthenticatedFrame(
            data = "plain text".encodeToByteArray(),
            key = key,
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )

        assertFailsWith<IOException> {
            decode(encryptedBytes.copyOf(FileEncryptionFormat.HEADER_LENGTH - 1), key)
        }
    }

    @Test
    fun `rejects close before consuming full frame`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = createAuthenticatedFrame(
            data = ByteArray(100_000) { index ->
                (index % 251).toByte()
            },
            key = key,
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )
        val input = CipherInputStream2(
            ByteArrayInputStream(encryptedBytes),
            CipherInputStreamDecoder(key = key),
        )

        input.read(ByteArray(64))

        assertFailsWith<IOException> {
            input.close()
        }
    }

    @Test
    fun `close preserves input failure when cipher finalization also fails`() {
        val inputFailure = IOException("input close failure")
        val finalizationFailure = IllegalStateException("cipher finalization failure")
        val input = CipherInputStream2(
            object : ByteArrayInputStream(ByteArray(0)) {
                override fun close() {
                    throw inputFailure
                }
            },
            object : CipherInputStream2.Decoder {
                override fun processBytes(
                    `in`: ByteArray,
                    inOff: Int,
                    len: Int,
                    out: ByteArray,
                    outOff: Int,
                ): Int = 0

                override fun doFinal(out: ByteArray, outOff: Int): Int =
                    throw finalizationFailure

                override fun getOutputSize(length: Int): Int = 0

                override fun getUpdateOutputSize(length: Int): Int = 0
            },
        )

        val failure = assertFailsWith<IOException> {
            input.close()
        }

        assertSame(finalizationFailure, failure.cause)
        assertEquals(1, failure.suppressed.size)
        assertSame(inputFailure, failure.suppressed.single())
    }

    @Test
    fun `rejects unsupported frame type`() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = byteArrayOf(CipherEncryptor.Type.AesCbc256_B64.byte) +
            ByteArray(FileEncryptionFormat.HEADER_LENGTH - FileEncryptionFormat.TYPE_LENGTH)

        assertFailsWith<IOException> {
            decode(encryptedBytes, key)
        }
    }

    @Test
    fun `zero length read returns immediately without consuming input`() {
        var reads = 0
        val key = ByteArray(64) { index -> index.toByte() }
        val plaintext = "plain text".encodeToByteArray()
        val encryptedBytes = createAuthenticatedFrame(
            data = plaintext,
            key = key,
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )
        val encryptedInput = object : ByteArrayInputStream(encryptedBytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads += 1
                return super.read(b, off, len)
            }
        }
        val input = CipherInputStream2(
            encryptedInput,
            CipherInputStreamDecoder(key),
        )

        assertEquals(0, input.read(ByteArray(1), 0, 0))
        assertEquals(0, reads)
        assertContentEquals(plaintext, input.readBytes())
        input.close()
    }

    @Test
    fun `repeated zero progress input is rejected`() {
        val input = CipherInputStream2(
            object : InputStream() {
                override fun read(): Int = 0

                override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            },
            CipherInputStreamDecoder(ByteArray(64)),
        )

        val failure = assertFailsWith<IOException> {
            input.read()
        }

        assertEquals("Input stream made no progress while reading", failure.message)
    }

    private fun decode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = decode(ByteArrayInputStream(data), key)

    private fun decode(
        input: InputStream,
        key: ByteArray,
    ): ByteArray = CipherInputStream2(
        input,
        CipherInputStreamDecoder(key = key),
    ).readBytes()

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
        val cipherText = bouncyCastleAesCbcPkcs7(
            key = keys.encKey,
            iv = iv,
            data = data,
            encrypt = true,
        )
        val mac = cryptoGenerator.hmacSha256(keys.macKey, iv + cipherText)
        return byteArrayOf(type.byte) + iv + mac + cipherText
    }
}
