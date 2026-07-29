package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeFileEncryptionCodecTest {
    private val cryptoGenerator = NativeCryptoGenerator()
    private val codec = NativeFileEncryptionCodec(
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

        val encryptedBytes = codec.encrypt(
            data = data,
            key = key,
        )

        assertEquals(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte, encryptedBytes.first())
        assertTrue(encryptedBytes.size > FileEncryptionFormat.HEADER_LENGTH)
        assertContentEquals(data, codec.decrypt(encryptedBytes, key))
    }

    @Test
    fun byteArrayEncodeAndDecodeSupportsEmptyPayload() {
        val data = ByteArray(0)
        val key = ByteArray(64) { index ->
            index.toByte()
        }

        val encryptedBytes = codec.encrypt(
            data = data,
            key = key,
        )

        assertEquals(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte, encryptedBytes.first())
        assertTrue(encryptedBytes.size > FileEncryptionFormat.HEADER_LENGTH)
        assertContentEquals(data, codec.decrypt(encryptedBytes, key))
    }

    @Test
    fun byteArrayDecodeRejectsTamperedMac() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = codec.encrypt(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        tamperedBytes[1 + 16] = (tamperedBytes[1 + 16].toInt() xor 1).toByte()

        assertFailsWith<IllegalStateException> {
            codec.decrypt(tamperedBytes, key)
        }
    }

    @Test
    fun byteArrayDecodeRejectsTamperedCiphertext() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = codec.encrypt(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        val tamperedBytes = encryptedBytes.copyOf()
        tamperedBytes[tamperedBytes.lastIndex] = (tamperedBytes.last().toInt() xor 1).toByte()

        assertFailsWith<IllegalStateException> {
            codec.decrypt(tamperedBytes, key)
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
        val encryptedBytes = codec.encrypt(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        assertFailsWith<IllegalStateException> {
            codec.decrypt(encryptedBytes, wrongKey)
        }
    }

    @Test
    fun byteArrayDecodeRejectsTruncatedAuthenticatedFrame() {
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val encryptedBytes = codec.encrypt(
            data = "plain text".encodeToByteArray(),
            key = key,
        )

        assertFailsWith<IllegalStateException> {
            codec.decrypt(encryptedBytes.copyOf(FileEncryptionFormat.HEADER_LENGTH - 1), key)
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

        assertContentEquals(data, codec.decrypt(encryptedBytes, key))
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
            codec.decrypt(encryptedBytes, key)
        }
    }

    @Test
    fun streamingEncryptBorrowsInputAndOutput() {
        val data = ByteArray(100_000) { index -> (index % 251).toByte() }
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val trackingInput = FileEncryptionCodecTrackingRawSource(data)
        val input = trackingInput.buffered()
        val trackingOutput = FileEncryptionCodecTrackingRawSink()
        val output = trackingOutput.buffered()

        try {
            val result = codec.encrypt(
                input = input,
                output = output,
                key = key,
            )

            assertEquals(data.size.toLong(), result.plainSize)
            assertEquals(0, trackingInput.closeCount)
            assertEquals(0, trackingOutput.closeCount)
            assertEquals(0, trackingOutput.flushCount)

            output.flush()
            val encryptedBytes = trackingOutput.data.readByteArray()
            assertEquals(encryptedBytes.size.toLong(), result.encryptedSize)
            assertContentEquals(data, codec.decrypt(encryptedBytes, key))
        } finally {
            input.close()
            runCatching { output.close() }
        }
    }

    @Test
    fun streamingEncryptLeavesBorrowedResourcesOpenAfterOutputFailure() {
        val data = ByteArray(100_000) { index -> (index * 31 + 7).toByte() }
        val key = ByteArray(64) { index -> index.toByte() }
        val trackingInput = FileEncryptionCodecTrackingRawSource(data)
        val input = trackingInput.buffered()
        val trackingOutput = FileEncryptionCodecFailingRawSink()
        val output = trackingOutput.buffered()

        try {
            assertFailsWith<IOException> {
                codec.encrypt(
                    input = input,
                    output = output,
                    key = key,
                )
            }

            assertEquals(0, trackingInput.closeCount)
            assertEquals(0, trackingOutput.closeCount)
            assertEquals(0, trackingOutput.flushCount)
        } finally {
            input.close()
            runCatching { output.close() }
        }
    }

    @Test
    fun streamingDecryptBorrowsInputAndOutput() {
        val data = ByteArray(100_000) { index -> (index * 31 + 7).toByte() }
        val key = ByteArray(64) { index -> index.toByte() }
        val encryptedBytes = codec.encrypt(data, key)
        val trackingInput = FileEncryptionCodecTrackingRawSource(encryptedBytes)
        val input = trackingInput.buffered()
        val trackingOutput = FileEncryptionCodecTrackingRawSink()
        val output = trackingOutput.buffered()

        try {
            codec.decrypt(
                input = input,
                output = output,
                key = key,
            )

            assertEquals(0, trackingInput.closeCount)
            assertEquals(0, trackingOutput.closeCount)
            assertEquals(0, trackingOutput.flushCount)

            output.flush()
            assertContentEquals(data, trackingOutput.data.readByteArray())
        } finally {
            input.close()
            runCatching { output.close() }
        }
    }

    @Test
    fun streamingDecryptLeavesBorrowedResourcesOpenAfterAuthenticationFailure() {
        val key = ByteArray(64) { index -> index.toByte() }
        val encryptedBytes = codec.encrypt("plain text".encodeToByteArray(), key)
        encryptedBytes[FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH] =
            (
                encryptedBytes[FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH]
                    .toInt() xor 1
                )
                .toByte()
        val trackingInput = FileEncryptionCodecTrackingRawSource(encryptedBytes)
        val input = trackingInput.buffered()
        val trackingOutput = FileEncryptionCodecTrackingRawSink()
        val output = trackingOutput.buffered()

        try {
            assertFailsWith<IllegalStateException> {
                codec.decrypt(
                    input = input,
                    output = output,
                    key = key,
                )
            }

            assertEquals(0, trackingInput.closeCount)
            assertEquals(0, trackingOutput.closeCount)
            assertEquals(0, trackingOutput.flushCount)
        } finally {
            input.close()
            runCatching { output.close() }
        }
    }

    @Test
    fun streamingDecryptLeavesBorrowedResourcesOpenAfterOutputFailure() {
        val data = ByteArray(100_000) { index -> (index * 31 + 7).toByte() }
        val key = ByteArray(64) { index -> index.toByte() }
        val encryptedBytes = codec.encrypt(data, key)
        val trackingInput = FileEncryptionCodecTrackingRawSource(encryptedBytes)
        val input = trackingInput.buffered()
        val trackingOutput = FileEncryptionCodecFailingRawSink()
        val output = trackingOutput.buffered()

        try {
            assertFailsWith<IOException> {
                codec.decrypt(
                    input = input,
                    output = output,
                    key = key,
                )
            }

            assertEquals(0, trackingInput.closeCount)
            assertEquals(0, trackingOutput.closeCount)
            assertEquals(0, trackingOutput.flushCount)
        } finally {
            input.close()
            runCatching { output.close() }
        }
    }

    @Test
    fun streamingDecryptReadsFusedChunksAcross64KiBBoundary() {
        val data = ByteArray(100_003) { index -> (index * 31 + 7).toByte() }
        val key = ByteArray(64) { index -> index.toByte() }
        val encryptedBytes = codec.encrypt(data, key)
        val output = Buffer()

        codec.decrypt(
            input = Buffer().apply { write(encryptedBytes) },
            output = output,
            key = key,
        )

        assertContentEquals(data, output.readByteArray())
    }

    @Test
    fun streamingDecryptReadsExistingAesCbc128HmacSha256Frames() {
        val data = "existing encrypted file".encodeToByteArray()
        val key = ByteArray(32) { index -> index.toByte() }
        val encryptedBytes = createAesCbc128HmacSha256Frame(
            data = data,
            key = key,
        )
        val output = Buffer()

        codec.decrypt(
            input = Buffer().apply { write(encryptedBytes) },
            output = output,
            key = key,
        )

        assertContentEquals(data, output.readByteArray())
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

private class FileEncryptionCodecTrackingRawSource(
    data: ByteArray,
) : RawSource {
    private val buffer = Buffer().apply {
        write(data)
    }

    var closeCount = 0
        private set

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = buffer.readAtMostTo(sink, byteCount)

    override fun close() {
        closeCount += 1
    }
}

private open class FileEncryptionCodecTrackingRawSink : RawSink {
    val data = Buffer()

    var flushCount = 0
        private set

    var closeCount = 0
        private set

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        data.write(source, byteCount)
    }

    override fun flush() {
        flushCount += 1
    }

    override fun close() {
        closeCount += 1
    }
}

private class FileEncryptionCodecFailingRawSink : FileEncryptionCodecTrackingRawSink() {
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        throw IOException("test output failure")
    }
}
