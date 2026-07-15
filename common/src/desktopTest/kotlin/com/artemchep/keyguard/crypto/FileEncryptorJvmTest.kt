package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class FileEncryptorJvmTest {
    private val encryptor = FileEncryptorJvm(
        cryptoGenerator = CryptoGeneratorJvm(),
    )

    @Test
    fun `streaming encode closes native hmac after source failure`() {
        val root = createTempDirectory("file-encryptor-jvm-failure")
        val output = root.resolve("encrypted.bin")
        val key = ByteArray(64) { index -> index.toByte() }

        repeat(1_025) {
            val source = object : RawSource {
                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                    throw IOException("test source failure")
                }

                override fun close() = Unit
            }.buffered()

            assertFailsWith<IOException> {
                encryptor.encode(
                    input = source,
                    output = output.toLocalPath(),
                    key = key,
                )
            }
        }
    }

    @Test
    fun `streaming decode authenticates before returning a stream`() {
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
            encryptor.decode(ByteArrayInputStream(tamperedBytes), key)
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
            encryptor.decode(ByteArrayInputStream(encryptedBytes), wrongKey)
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
            encryptor.decode(
                ByteArrayInputStream(encryptedBytes.copyOf(FileEncryptionFormat.HEADER_LENGTH - 1)),
                key,
            )
        }
    }

    @Test
    fun `streaming decode authenticates before returning a partially consumed stream`() {
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
        input.close()
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
    fun `streaming decode preserves existing destination on authentication failure`() {
        val root = createTempDirectory("file-decrypt-auth-failure")
        val output = root.resolve("output.bin")
        val original = "existing output".encodeToByteArray()
        val key = ByteArray(64) { index -> index.toByte() }
        val encrypted = encryptor.encode("plain text".encodeToByteArray(), key)
        encrypted[FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH] =
            (encrypted[FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH].toInt() xor 1).toByte()
        output.writeBytes(original)

        try {
            assertFails {
                encryptor.decode(
                    input = Buffer().apply { write(encrypted) },
                    output = output.toLocalPath(),
                    key = key,
                )
            }

            assertContentEquals(original, output.readBytes())
            assertEquals(listOf("output.bin"), root.toFile().list()?.sorted())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `streaming decode preserves existing destination on padding failure`() {
        val root = createTempDirectory("file-decrypt-padding-failure")
        val output = root.resolve("output.bin")
        val original = "existing output".encodeToByteArray()
        val key = ByteArray(64) { index -> index.toByte() }
        val encrypted = encryptor.encode(ByteArray(0), key)
        val ivOffset = FileEncryptionFormat.TYPE_LENGTH
        val ivEnd = ivOffset + FileEncryptionFormat.IV_LENGTH
        encrypted[ivEnd - 1] = (encrypted[ivEnd - 1].toInt() xor 1).toByte()
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        val iv = encrypted.copyOfRange(ivOffset, ivEnd)
        val ciphertext = encrypted.copyOfRange(FileEncryptionFormat.HEADER_LENGTH, encrypted.size)
        val mac = NativeFileCrypto.hmacSha256(keys.macKey, iv, ciphertext)
        mac.copyInto(encrypted, destinationOffset = ivEnd)
        output.writeBytes(original)

        try {
            assertFails {
                encryptor.decode(
                    input = Buffer().apply { write(encrypted) },
                    output = output.toLocalPath(),
                    key = key,
                )
            }

            assertContentEquals(original, output.readBytes())
            assertEquals(listOf("output.bin"), root.toFile().list()?.sorted())
        } finally {
            with(NativeFileCrypto) { keys.clear() }
            iv.fill(0)
            ciphertext.fill(0)
            mac.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `streaming encode preserves existing destination on source failure`() {
        val root = createTempDirectory("file-encrypt-source-failure")
        val output = root.resolve("output.bin")
        val original = "existing output".encodeToByteArray()
        val key = ByteArray(64) { index -> index.toByte() }
        output.writeBytes(original)
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
                throw IOException("test source failure")

            override fun close() = Unit
        }.buffered()

        try {
            assertFailsWith<IOException> {
                encryptor.encode(
                    input = source,
                    output = output.toLocalPath(),
                    key = key,
                )
            }

            assertContentEquals(original, output.readBytes())
            assertEquals(listOf("output.bin"), root.toFile().list()?.sorted())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `streaming encode removes partial output after cancellation`() {
        val root = createTempDirectory("file-encrypt-cancelled")
        val output = root.resolve("output.bin")
        val original = "existing output".encodeToByteArray()
        val key = ByteArray(64) { index -> index.toByte() }
        output.writeBytes(original)
        var emitted = false
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                if (emitted) throw CancellationException("cancelled")
                emitted = true
                val bytes = ByteArray(1024) { index -> index.toByte() }
                sink.write(bytes)
                return bytes.size.toLong()
            }

            override fun close() = Unit
        }.buffered()

        try {
            assertFailsWith<CancellationException> {
                encryptor.encode(
                    input = source,
                    output = output.toLocalPath(),
                    key = key,
                )
            }

            assertContentEquals(original, output.readBytes())
            assertEquals(listOf("output.bin"), root.toFile().list()?.sorted())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `streaming file operations cross the bounded ciphertext spill threshold`() {
        val root = createTempDirectory("file-encrypt-spill")
        val encrypted = root.resolve("encrypted.bin")
        val decrypted = root.resolve("decrypted.bin")
        val plaintext = ByteArray(MAX_IN_MEMORY_FILE_CIPHERTEXT_BYTES.toInt() + 257) { index ->
            (index * 31 + 7).toByte()
        }
        val key = ByteArray(64) { index -> index.toByte() }

        try {
            encryptor.encode(
                input = Buffer().apply { write(plaintext) },
                output = encrypted.toLocalPath(),
                key = key,
            )
            encrypted.toFile().inputStream().use { input ->
                input.asSource().buffered().use { source ->
                    encryptor.decode(
                        input = source,
                        output = decrypted.toLocalPath(),
                        key = key,
                    )
                }
            }

            assertContentEquals(plaintext, decrypted.readBytes())
            assertEquals(
                listOf("decrypted.bin", "encrypted.bin"),
                root.toFile().list()?.sorted(),
            )
        } finally {
            plaintext.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `streaming encode atomically replaces destination with owner-only output`() {
        val root = createTempDirectory("file-encrypt-atomic-output")
        val output = root.resolve("output.bin")
        val plaintext = ByteArray(100_003) { index -> (index * 17).toByte() }
        val key = ByteArray(64) { index -> index.toByte() }
        output.writeBytes("existing output".encodeToByteArray())

        try {
            encryptor.encode(
                input = Buffer().apply { write(plaintext) },
                output = output.toLocalPath(),
                key = key,
            )

            assertContentEquals(plaintext, encryptor.decode(output.readBytes(), key))
            assertEquals(listOf("output.bin"), root.toFile().list()?.sorted())
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                assertEquals(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
                    Files.getPosixFilePermissions(output),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
