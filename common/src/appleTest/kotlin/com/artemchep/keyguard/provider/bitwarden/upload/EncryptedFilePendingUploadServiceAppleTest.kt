package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.copy.FileServiceApple
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.crypto.NativeFileEncryptionCodec
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.readBytes
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Exercises the Apple pending-upload service against the real native file-encryption
 * codec and the real filesystem — the staged bytes must actually round-trip, because a
 * silently-wrong staging path corrupts attachment uploads rather than failing loudly.
 */
class EncryptedFilePendingUploadServiceAppleTest {
    private val root = createTempDirectory()

    private val service = EncryptedFilePendingUploadServiceApple(
        dirProvider = object : PendingUploadDirProvider {
            override suspend fun get(
                accountId: String,
                namespace: String,
            ): PendingUploadDirectory = PendingUploadDirectory(
                root = root,
                relativePath = AtomicRelativePath.fromComponents(
                    AtomicPathComponent.parse(namespace),
                    AtomicPathComponent.parse(accountId),
                ),
            )
        },
        fileService = FileServiceApple(),
        fileEncryptionCodec = NativeFileEncryptionCodec(
            cryptoGenerator = NativeCryptoGenerator(),
        ),
    )

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(root.value, null)
    }

    @Test
    fun stagedFileRoundTripsThroughReadPlaintext() = runTest {
        val plaintext = ByteArray(128 * 1024 + 7) { index -> (index % 251).toByte() }
        val sourceUri = root.writeSourceFile("source.bin", plaintext)

        val pendingUpload = service.stage(
            accountId = ACCOUNT_ID,
            namespace = NAMESPACE,
            fileId = "attachment-1",
            sourceUri = sourceUri,
            fileKey = FILE_KEY,
        )

        assertEquals(plaintext.size.toLong(), pendingUpload.plainSize)
        // The staged file holds ciphertext, not the source bytes.
        val staged = LocalPath(pendingUpload.path).readBytes()
        assertEquals(pendingUpload.encryptedSize, staged.size.toLong())
        assertFalse(staged.contentEquals(plaintext))

        assertContentEquals(
            plaintext,
            service.readPlaintext(
                pendingUpload = pendingUpload,
                fileKey = FILE_KEY,
            ),
        )
    }

    @Test
    fun uploadedMarkerTogglesIsUploaded() = runTest {
        val pendingUpload = stageSample()

        assertFalse(service.isUploaded(pendingUpload))
        service.markUploaded(pendingUpload)
        assertTrue(service.isUploaded(pendingUpload))
    }

    @Test
    fun stageClearsStaleUploadedMarker() = runTest {
        val first = stageSample(fileId = "attachment-1")
        service.markUploaded(first)
        assertTrue(service.isUploaded(first))

        // Re-staging the same file id publishes fresh bytes; a marker left over from the
        // previous staging would make sync skip uploading them.
        val second = stageSample(fileId = "attachment-1", name = "second.bin")

        assertEquals(first.path, second.path)
        assertFalse(service.isUploaded(second))
    }

    @Test
    fun deleteRemovesBothTheStagedFileAndItsMarker() = runTest {
        val pendingUpload = stageSample()
        service.markUploaded(pendingUpload)

        service.delete(pendingUpload)

        assertFalse(exists(pendingUpload.path))
        assertFalse(exists("${pendingUpload.path}.uploaded"))
    }

    @Test
    fun sweepOrphansKeepsReferencedFilesAndRemovesUnreferencedOnes() = runTest {
        val referenced = stageSample(fileId = "kept", name = "kept-source.bin")
        val orphan = stageSample(fileId = "orphan", name = "orphan-source.bin")
        service.markUploaded(orphan)

        service.sweepOrphans(
            accountId = ACCOUNT_ID,
            namespace = NAMESPACE,
            referencedPaths = setOf(referenced.path),
            // Everything staged above is older than "now", so only the reference set
            // decides what survives.
            olderThan = Clock.System.now(),
        )

        assertTrue(exists(referenced.path))
        assertFalse(exists(orphan.path))
        assertFalse(exists("${orphan.path}.uploaded"))
    }

    @Test
    fun sweepOrphansKeepsFilesThatAreNewerThanTheCutoff() = runTest {
        val recent = stageSample(fileId = "recent")

        service.sweepOrphans(
            accountId = ACCOUNT_ID,
            namespace = NAMESPACE,
            referencedPaths = emptySet(),
            // A cutoff in the past makes every artifact "not yet stale", so an
            // unreferenced but freshly staged upload must survive.
            olderThan = Clock.System.now() - 1.hours,
        )

        assertTrue(exists(recent.path))
    }

    @Test
    fun stageRejectsPathEscapesInIdentifiers() = runTest {
        val sourceUri = root.writeSourceFile("source.bin", "plain".encodeToByteArray())

        listOf(
            Triple("account/escape", NAMESPACE, "attachment-1"),
            Triple(ACCOUNT_ID, "name/space", "attachment-1"),
            Triple(ACCOUNT_ID, NAMESPACE, "attachment/1"),
        ).forEach { (accountId, namespace, fileId) ->
            assertFailsWith<IllegalArgumentException> {
                service.stage(
                    accountId = accountId,
                    namespace = namespace,
                    fileId = fileId,
                    sourceUri = sourceUri,
                    fileKey = FILE_KEY,
                )
            }
        }
    }

    private suspend fun stageSample(
        fileId: String = "attachment-1",
        name: String = "source.bin",
    ): PendingUploadFile = service.stage(
        accountId = ACCOUNT_ID,
        namespace = NAMESPACE,
        fileId = fileId,
        sourceUri = root.writeSourceFile(name, "plain-sample".encodeToByteArray()),
        fileKey = FILE_KEY,
    )

    private companion object {
        const val ACCOUNT_ID = "account-1"
        const val NAMESPACE = "attachment_uploads"
        val FILE_KEY = ByteArray(64) { index -> (index + 1).toByte() }
    }
}

private fun createTempDirectory(): LocalPath {
    val path = LocalPath(
        NSTemporaryDirectory() + "pending-upload-apple-" + NSUUID().UUIDString,
    )
    SystemFileSystem.createDirectories(path.toKotlinxIoPath())
    return path
}

private fun LocalPath.writeSourceFile(
    name: String,
    data: ByteArray,
): String {
    val file = resolve(name)
    SystemFileSystem.sink(file.toKotlinxIoPath()).buffered().use { sink ->
        sink.write(data)
    }
    return file.toFileUriString()
}

private fun exists(path: String): Boolean =
    SystemFileSystem.exists(Path(path))
