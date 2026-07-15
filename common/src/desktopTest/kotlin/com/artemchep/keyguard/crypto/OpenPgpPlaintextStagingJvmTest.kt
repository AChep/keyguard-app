package com.artemchep.keyguard.crypto

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenPgpPlaintextStagingJvmTest {
    @Test
    fun provisionalPlaintextIsDiscardedWhenFinalizationFails() {
        val output = Buffer()
        val provisional = "unauthenticated plaintext".encodeToByteArray()

        val failure = assertFailsWith<AuthenticationFailure> {
            withStagedOpenPgpPlaintext(output) { staging ->
                staging.write(provisional)
                throw AuthenticationFailure()
            }
        }

        assertEquals("authentication failed", failure.message)
        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun plaintextIsPublishedOnlyAfterSuccessfulFinalization() {
        val output = Buffer()
        val plaintext = "authenticated plaintext".encodeToByteArray()

        val result = withStagedOpenPgpPlaintext(output) { staging ->
            staging.write(plaintext)
            "finished"
        }

        assertEquals("finished", result)
        assertContentEquals(plaintext, output.readByteArray())
    }

    @Test
    fun plaintextSpillsToEncryptedStorageAfterMemoryLimit() {
        val output = Buffer()
        val plaintext = ByteArray(2 * 64 * 1024 + 31) { index ->
            (index % 251).toByte()
        }

        withStagedOpenPgpPlaintext(
            output = output,
            memoryLimitBytes = 4L,
        ) { staging ->
            staging.write(plaintext)
        }

        assertContentEquals(plaintext, output.readByteArray())
    }

    @Test
    fun spilledBytesDoNotContainPlaintext() {
        val storage = RecordingPrivateTemporaryStorage()
        val plaintext = "highly recognizable OpenPGP plaintext".encodeToByteArray()

        withStagedOpenPgpPlaintextUsing(
            output = Buffer(),
            maxPlaintextBytes = 1024L,
            memoryLimitBytes = 0L,
            spillFactory = { EncryptedTemporarySpillStorage.create(storage) },
        ) { staging ->
            staging.write(plaintext)
        }

        assertFalse(storage.bytes().containsSubsequence(plaintext))
    }

    @Test
    fun corruptedEncryptedSpillIsRejectedBeforePublication() {
        val storage = RecordingPrivateTemporaryStorage(tamperOnFirstRewind = true)
        val output = Buffer()

        assertFailsWith<kotlinx.io.IOException> {
            withStagedOpenPgpPlaintextUsing(
                output = output,
                maxPlaintextBytes = 1024L,
                memoryLimitBytes = 0L,
                spillFactory = { EncryptedTemporarySpillStorage.create(storage) },
            ) { staging ->
                staging.write("authenticated plaintext".encodeToByteArray())
            }
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun oversizedPlaintextIsDiscarded() {
        val output = Buffer()

        assertFailsWith<kotlinx.io.IOException> {
            withStagedOpenPgpPlaintext(
                output = output,
                maxPlaintextBytes = 4L,
            ) { staging ->
                staging.write(byteArrayOf(1, 2, 3, 4, 5))
            }
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun posixStagingFileIsUnlinkedWhileInUse() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return

        val directory = Files.createTempDirectory("keyguard-openpgp-test-").toFile()
        try {
            createPrivateTemporaryStorageJvm(directory).use { staging ->
                assertTrue(directory.list().orEmpty().isEmpty())
            }
            assertTrue(directory.list().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun privateTemporaryStorageEnforcesWriteSealReadLifecycle() {
        val directory = Files.createTempDirectory("keyguard-openpgp-lifecycle-test-").toFile()
        try {
            createPrivateTemporaryStorageJvm(directory).use { storage ->
                val sink = storage.sink()
                assertFailsWith<IllegalStateException> { storage.sink() }
                assertFailsWith<IllegalStateException> { storage.source() }
                assertFailsWith<IllegalStateException> { storage.rewind() }

                val plaintext = byteArrayOf(1, 2, 3)
                sink.write(Buffer().apply { write(plaintext) }, plaintext.size.toLong())
                sink.close()
                storage.sealForReading()

                assertFailsWith<IllegalStateException> { storage.sealForReading() }
                assertFailsWith<IllegalStateException> { storage.sink() }
                assertFailsWith<IllegalStateException> {
                    sink.write(Buffer().apply { writeByte(4) }, 1L)
                }

                storage.rewind()
                val actual = storage.source().buffered().use { source ->
                    source.readByteArray()
                }
                assertContentEquals(plaintext, actual)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun stagingFileUsesOwnerOnlyPermissions() {
        val file = createPrivateTemporaryFile()
        try {
            assertTrue(file.name.startsWith("keyguard-private-"))
            assertFalse(file.name.contains("openpgp", ignoreCase = true))
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                assertEquals(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
                    Files.getPosixFilePermissions(file.toPath()),
                )
            } else {
                check(file.canRead())
                check(file.canWrite())
                check(!file.canExecute())
            }
        } finally {
            file.delete()
        }
    }

    private class AuthenticationFailure : RuntimeException("authentication failed")

    private class RecordingPrivateTemporaryStorage(
        private val tamperOnFirstRewind: Boolean = false,
    ) : PrivateTemporaryStorage {
        private val chunks = mutableListOf<ByteArray>()
        private var rewinds = 0
        private var sinkClosed = false
        private var sealed = false

        override fun sink(): RawSink = object : RawSink {
            override fun write(source: Buffer, byteCount: Long) {
                check(!sinkClosed)
                var remaining = byteCount
                while (remaining > 0L) {
                    val chunk = ByteArray(minOf(remaining, 64 * 1024L).toInt())
                    val read = source.readAtMostTo(chunk)
                    check(read == chunk.size)
                    chunks += chunk
                    remaining -= read
                }
            }

            override fun flush() = Unit

            override fun close() {
                sinkClosed = true
            }
        }

        override fun sealForReading() {
            check(!sealed)
            sinkClosed = true
            sealed = true
        }

        override fun source(): RawSource {
            check(sealed)
            return Buffer().apply {
                chunks.forEach { chunk -> write(chunk) }
            }
        }

        override fun rewind() {
            check(sealed)
            if (tamperOnFirstRewind && rewinds++ == 0 && chunks.isNotEmpty()) {
                chunks[0][0] = (chunks[0][0].toInt() xor 1).toByte()
            }
        }

        fun bytes(): ByteArray = chunks
            .fold(Buffer()) { buffer, chunk -> buffer.apply { write(chunk) } }
            .readByteArray()

        override fun close() {
            sinkClosed = true
        }
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    if (candidate.size > size) return false
    return (0..size - candidate.size).any { offset ->
        candidate.indices.all { index -> this[offset + index] == candidate[index] }
    }
}
