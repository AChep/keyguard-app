package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.decryptToPath
import com.artemchep.keyguard.common.service.crypto.encryptToPath
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.foundation.crypto.aesCbcPkcs7Encrypt
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FileEncryptionCodecIosTest {
    private val cryptoGenerator = CryptoGeneratorApple()
    private val codec = FileEncryptionCodecApple(
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
        val encryptedBytes = codec.encrypt(
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
        val encryptedBytes = codec.encrypt(
            data = data,
            key = key,
        )

        assertContentEquals(data, decode(encryptedBytes, key))
    }

    @Test
    fun `streaming encode writes a native frame on iOS`() {
        val data = ByteArray(100_000) { index ->
            (index % 251).toByte()
        }
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val output = tempFile()

        try {
            val result = codec.encryptToPath(
                input = Buffer().apply { write(data) },
                output = output.atomicDestination(),
                key = key,
                synchronization = SynchronizationPolicy.Required(
                    SyncLevel.FileSynchronized,
                ),
            )
            val encryptedBytes = output.readBytes()

            assertEquals(data.size.toLong(), result.value.plainSize)
            assertEquals(encryptedBytes.size.toLong(), result.value.encryptedSize)
            assertEquals(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte, encryptedBytes.first())
            assertContentEquals(data, codec.decrypt(encryptedBytes, key))
        } finally {
            output.deleteIfExists()
        }
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
        val encryptedBytes = codec.encrypt(
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
        val encryptedBytes = codec.encrypt(
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
        val encryptedBytes = codec.encrypt(
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
        val encryptedBytes = codec.encrypt(
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
            codec.decryptToPath(
                input = source,
                output = output.atomicDestination(),
                key = key,
                synchronization = SynchronizationPolicy.Required(
                    SyncLevel.FileSynchronized,
                ),
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

private fun LocalPath.atomicDestination(): AtomicFileDestination =
    AtomicFileDestination(
        root = LocalPath(value.substringBeforeLast('/')),
        relativePath = AtomicRelativePath.fromComponents(
            AtomicPathComponent.parse(value.substringAfterLast('/')),
        ),
    )

private fun LocalPath.deleteIfExists() {
    val path = toKotlinxIoPath()
    if (SystemFileSystem.exists(path)) {
        SystemFileSystem.delete(path)
    }
}
