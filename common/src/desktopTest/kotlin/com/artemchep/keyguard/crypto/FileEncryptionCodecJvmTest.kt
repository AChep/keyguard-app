package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.decryptToPath
import com.artemchep.keyguard.common.service.crypto.encryptToPath
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.buffered
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

@Suppress("FunctionNaming", "MagicNumber")
class FileEncryptionCodecJvmTest {
    private val codec = FileEncryptionCodecJvm(
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
                codec.encryptToPath(
                    input = source,
                    output = output.atomicDestination(),
                    key = key,
                    synchronization = SynchronizationPolicy.Required(
                        SyncLevel.FileSynchronized,
                    ),
                )
            }
        }
    }

    @Test
    fun `streaming decode preserves existing destination on authentication failure`() {
        val root = createTempDirectory("file-decrypt-auth-failure")
        val output = root.resolve("output.bin")
        val original = "existing output".encodeToByteArray()
        val key = ByteArray(64) { index -> index.toByte() }
        val encrypted = codec.encrypt("plain text".encodeToByteArray(), key)
        encrypted[FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH] =
            (encrypted[FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH].toInt() xor 1).toByte()
        output.writeBytes(original)

        try {
            assertFails {
                codec.decryptToPath(
                    input = Buffer().apply { write(encrypted) },
                    output = output.atomicDestination(),
                    key = key,
                    synchronization = SynchronizationPolicy.Required(
                        SyncLevel.FileSynchronized,
                    ),
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
        val encrypted = codec.encrypt(ByteArray(0), key)
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
                codec.decryptToPath(
                    input = Buffer().apply { write(encrypted) },
                    output = output.atomicDestination(),
                    key = key,
                    synchronization = SynchronizationPolicy.Required(
                        SyncLevel.FileSynchronized,
                    ),
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
                codec.encryptToPath(
                    input = source,
                    output = output.atomicDestination(),
                    key = key,
                    synchronization = SynchronizationPolicy.Required(
                        SyncLevel.FileSynchronized,
                    ),
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
                codec.encryptToPath(
                    input = source,
                    output = output.atomicDestination(),
                    key = key,
                    synchronization = SynchronizationPolicy.Required(
                        SyncLevel.FileSynchronized,
                    ),
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
            codec.encryptToPath(
                input = Buffer().apply { write(plaintext) },
                output = encrypted.atomicDestination(),
                key = key,
                synchronization = SynchronizationPolicy.Required(
                    SyncLevel.FileSynchronized,
                ),
            )
            encrypted.toFile().inputStream().use { input ->
                input.asSource().buffered().use { source ->
                    codec.decryptToPath(
                        input = source,
                        output = decrypted.atomicDestination(),
                        key = key,
                        synchronization = SynchronizationPolicy.Required(
                            SyncLevel.FileSynchronized,
                        ),
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
            codec.encryptToPath(
                input = Buffer().apply { write(plaintext) },
                output = output.atomicDestination(),
                key = key,
                synchronization = SynchronizationPolicy.Required(
                    SyncLevel.FileSynchronized,
                ),
            )

            assertContentEquals(plaintext, codec.decrypt(output.readBytes(), key))
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

private fun java.nio.file.Path.atomicDestination(): AtomicFileDestination {
    val parent = requireNotNull(parent)
    return AtomicFileDestination(
        root = parent.toLocalPath(),
        relativePath = AtomicRelativePath.fromComponents(
            AtomicPathComponent.parse(fileName.toString()),
        ),
    )
}
