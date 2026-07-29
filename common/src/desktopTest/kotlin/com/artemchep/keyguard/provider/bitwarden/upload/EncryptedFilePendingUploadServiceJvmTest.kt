package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.artifact.SweepReport
import com.artemchep.keyguard.util.io.artifact.SweepStatus
import com.artemchep.keyguard.util.io.artifact.TemporaryArtifactRole
import com.artemchep.keyguard.util.io.artifact.temporaryArtifactName
import com.artemchep.keyguard.util.io.atomic.AchievedSyncLevel
import com.artemchep.keyguard.util.io.atomic.AtomicCleanupIncompleteException
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.AtomicWriteReceipt
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

@Suppress("FunctionNaming")
class EncryptedFilePendingUploadServiceJvmTest {
    @Test
    fun `managed source remains reconstructible after file-only staging`() {
        val policy = SynchronizationPolicy.Prefer(
            preferred = SyncLevel.FileAndNamespaceSynchronized,
            minimum = SyncLevel.FileSynchronized,
        )

        assertFalse(
            canDiscardManagedSourceAfterStaging(
                AtomicWriteReceipt(
                    requestedSynchronization = policy,
                    achievedSyncLevel = AchievedSyncLevel.FileSynchronized,
                ),
            ),
        )
        assertTrue(
            canDiscardManagedSourceAfterStaging(
                AtomicWriteReceipt(
                    requestedSynchronization = policy,
                    achievedSyncLevel = AchievedSyncLevel.FileAndNamespaceSynchronized,
                ),
            ),
        )
        assertFalse(
            canDiscardManagedSourceAfterStaging(
                AtomicWriteReceipt(
                    requestedSynchronization = policy,
                    achievedSyncLevel = AchievedSyncLevel.FileAndNamespaceSynchronized,
                    cleanupFailure = FileSystemFailure(
                        kind = FileSystemFailureKind.PermissionDenied,
                    ),
                ),
            ),
        )
        assertFalse(
            canDiscardManagedSourceAfterStaging(
                AtomicWriteReceipt(
                    requestedSynchronization = policy,
                    achievedSyncLevel = AchievedSyncLevel.FileAndNamespaceSynchronized,
                    cleanupIncomplete = true,
                ),
            ),
        )
    }

    @Test
    fun `stage deletes managed source file after successful staging`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val source = root.resolve("drop-source.bin").also {
            it.writeBytes("plain".encodeToByteArray())
        }
        val pendingRoot = root.resolve("pending")
        val fileService = ManagedSourceFileService()
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
            fileService = fileService,
        )

        val pendingUpload = service.stage(
            accountId = "account-1",
            namespace = "send_uploads",
            fileId = "send-1",
            sourceUri = source.toUri().toString(),
            fileKey = "key".encodeToByteArray(),
        )

        assertEquals(listOf(source.toUri().toString()), fileService.deletedManagedSources)
        assertFalse(source.exists())
        assertEquals(pendingRoot.resolve("send-1.bin").toString(), pendingUpload.path)
        assertContentEquals("plain".encodeToByteArray(), File(pendingUpload.path).readBytes())
        assertContentEquals(
            "plain".encodeToByteArray(),
            service.readPlaintext(
                pendingUpload = pendingUpload,
                fileKey = "key".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `stage rejects embedded hierarchy before source access or directory creation`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val fileService = ManagedSourceFileService()
        val dirProvider = object : PendingUploadDirProvider {
            override suspend fun get(
                accountId: String,
                namespace: String,
            ): PendingUploadDirectory = PendingUploadDirectory(
                root = root.toLocalPath(),
                relativePath = AtomicRelativePath.fromComponents(
                    AtomicPathComponent.parse(namespace),
                    AtomicPathComponent.parse(accountId),
                ),
            )
        }
        val service = pendingUploadService(
            dirProvider = dirProvider,
            fileService = fileService,
        )
        val invalidFields = listOf(
            Triple("account/escape", "send_uploads", "send-1"),
            Triple("account-1", "send/uploads", "send-1"),
            Triple("account-1", "send_uploads", "send/1"),
        )

        invalidFields.forEach { (accountId, namespace, fileId) ->
            assertFailsWith<IllegalArgumentException> {
                service.stage(
                    accountId = accountId,
                    namespace = namespace,
                    fileId = fileId,
                    sourceUri = root.resolve("missing.bin").toUri().toString(),
                    fileKey = "key".encodeToByteArray(),
                )
            }
        }

        assertEquals(0, fileService.readCalls)
        assertEquals(emptyList(), root.toFile().list()?.toList())
    }

    @Test
    fun `stage clears stale uploaded marker`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val source = root.resolve("drop-source.bin").also {
            it.writeBytes("plain".encodeToByteArray())
        }
        val pendingRoot = root.resolve("pending")
        val marker = pendingRoot.resolve("send-1.bin.uploaded")
        marker.toFile().parentFile.mkdirs()
        marker.writeBytes("uploaded".encodeToByteArray())
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
        )

        service.stage(
            accountId = "account-1",
            namespace = "send_uploads",
            fileId = "send-1",
            sourceUri = source.toUri().toString(),
            fileKey = "key".encodeToByteArray(),
        )

        assertFalse(marker.exists())
    }

    @Test
    fun `stage propagates marker deletion failure before deleting managed source`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val source = root.resolve("drop-source.bin").also {
            it.writeBytes("plain".encodeToByteArray())
        }
        val pendingRoot = root.resolve("pending")
        val marker = pendingRoot.resolve("send-1.bin.uploaded")
        marker.resolve("child").also { child ->
            child.toFile().parentFile.mkdirs()
            child.writeBytes("occupied".encodeToByteArray())
        }
        val fileService = ManagedSourceFileService()
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
            fileService = fileService,
        )

        assertFailsWith<IOException> {
            service.stage(
                accountId = "account-1",
                namespace = "send_uploads",
                fileId = "send-1",
                sourceUri = source.toUri().toString(),
                fileKey = "key".encodeToByteArray(),
            )
        }

        assertTrue(source.exists())
        assertTrue(marker.exists())
        assertEquals(emptyList(), fileService.deletedManagedSources)
    }

    @Test
    fun `post publication clears marker before surfacing cleanup failure`() {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val marker = root.resolve("send-1.bin.uploaded")
            .writeArtifact(Instant.DISTANT_PAST)
        val receipt = AtomicWriteReceipt(
            requestedSynchronization = SynchronizationPolicy.Required(
                SyncLevel.FileSynchronized,
            ),
            achievedSyncLevel = AchievedSyncLevel.FileSynchronized,
            cleanupFailure = FileSystemFailure(
                kind = FileSystemFailureKind.PermissionDenied,
            ),
        )

        assertFailsWith<AtomicCleanupIncompleteException> {
            enforcePendingUploadPostPublication(
                markerPath = marker,
                receipt = receipt,
            )
        }

        assertFalse(marker.exists())
    }
}

@Suppress("FunctionNaming")
class EncryptedFilePendingUploadReadAndSweepTest {
    @Test
    fun `read plaintext crosses the adaptive memory threshold`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val pendingRoot = root.resolve("pending")
        val stagedFile = pendingRoot.resolve("send-1.bin")
        val plaintext = ByteArray(2 * 1024 * 1024 + 257) { index ->
            (index * 31 + 7).toByte()
        }
        stagedFile.toFile().parentFile.mkdirs()
        stagedFile.writeBytes(plaintext)
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
        )

        try {
            assertContentEquals(
                plaintext,
                service.readPlaintext(
                    pendingUpload = PendingUploadFile(
                        path = stagedFile.toString(),
                        plainSize = plaintext.size.toLong(),
                        encryptedSize = plaintext.size.toLong(),
                    ),
                    fileKey = "key".encodeToByteArray(),
                ),
            )
        } finally {
            plaintext.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `read plaintext rejects invalid metadata before opening the file`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val service = pendingUploadService(
            dirProvider = singleDirProvider(root.resolve("pending")),
        )

        listOf(
            -1L,
            KEEPASS_FILE_UPLOAD_MAX_BYTES + 1L,
        ).forEach { invalidSize ->
            assertFailsWith<IllegalArgumentException> {
                service.readPlaintext(
                    pendingUpload = PendingUploadFile(
                        path = root.resolve("missing.bin").toString(),
                        plainSize = invalidSize,
                        encryptedSize = 0L,
                    ),
                    fileKey = "key".encodeToByteArray(),
                )
            }
        }
    }

    @Test
    fun `read plaintext rejects a payload shorter than its metadata`() = runTest {
        val payload = "abc".encodeToByteArray()
        assertPlaintextSizeMismatch(
            payload = payload,
            declaredSize = payload.size.toLong() + 1L,
        )
    }

    @Test
    fun `read plaintext rejects a payload longer than its metadata`() = runTest {
        val payload = "abcd".encodeToByteArray()
        assertPlaintextSizeMismatch(
            payload = payload,
            declaredSize = payload.lastIndex.toLong(),
        )
    }

    @Test
    fun `mark uploaded writes marker and delete removes it`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val pendingRoot = root.resolve("pending")
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
        )
        val stagedFile = pendingRoot.resolve("send-1.bin")
        stagedFile.toFile().parentFile.mkdirs()
        stagedFile.writeBytes("encrypted".encodeToByteArray())
        val pendingUpload = PendingUploadFile(
            path = stagedFile.toString(),
            plainSize = 123L,
            encryptedSize = 321L,
        )

        assertFalse(service.isUploaded(pendingUpload))

        service.markUploaded(pendingUpload)

        assertTrue(service.isUploaded(pendingUpload))
        assertTrue(File("${pendingUpload.path}.uploaded").exists())

        service.delete(pendingUpload)

        assertFalse(service.isUploaded(pendingUpload))
        assertFalse(stagedFile.exists())
    }

    @Test
    fun `sweep removes only stale unreferenced artifacts in the requested scope`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val dirProvider = scopedDirProvider(root)
        val cutoff = Instant.parse("2026-07-26T07:00:00Z")
        val staleAt = Instant.parse("2026-07-25T07:00:00Z")
        val recentAt = Instant.parse("2026-07-27T07:00:00Z")
        val artifacts = createPendingUploadSweepArtifacts(
            root = root,
            staleAt = staleAt,
            recentAt = recentAt,
        )
        val nativeSweepDirectories = mutableListOf<LocalPath>()
        val service = pendingUploadService(
            dirProvider = dirProvider,
            temporarySweeper = { directory, _ ->
                nativeSweepDirectories += directory
                Files.deleteIfExists(artifacts.staleStagingTemp)
                emptySweepReport(status = SweepStatus.Complete)
            },
        )

        repeat(2) {
            service.sweepOrphans(
                accountId = "account-1",
                namespace = PendingUploadTarget.SendFile.NAMESPACE,
                referencedPaths = setOf(artifacts.referenced.toString()),
                olderThan = cutoff,
            )
        }

        assertTrue(artifacts.referenced.exists())
        assertTrue(artifacts.referencedMarker.exists())
        assertFalse(artifacts.orphan.exists())
        assertFalse(artifacts.orphanMarker.exists())
        assertTrue(artifacts.recentGroupBase.exists())
        assertTrue(artifacts.recentGroupMarker.exists())
        assertTrue(artifacts.unknownFile.exists())
        assertTrue(artifacts.otherAccountOrphan.exists())
        assertTrue(artifacts.otherNamespaceOrphan.exists())
        assertEquals(
            listOf(artifacts.targetDir.toLocalPath(), artifacts.targetDir.toLocalPath()),
            nativeSweepDirectories,
        )
        assertFalse(artifacts.staleStagingTemp.exists())
        assertTrue(artifacts.recentStagingTemp.exists())
        assertTrue(artifacts.unknownReservedArtifact.exists())
    }

    @Test
    fun `sweep propagates native retry states before custom deletion`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val pendingRoot = root.resolve("pending")
        val orphan = pendingRoot.resolve("orphan.bin")
            .writeArtifact(Instant.parse("2020-01-01T00:00:00Z"))
        val reports = listOf(
            emptySweepReport(status = SweepStatus.Busy),
            emptySweepReport(
                status = SweepStatus.Incomplete,
                firstFailure = FileSystemFailure(
                    kind = FileSystemFailureKind.PermissionDenied,
                ),
            ),
            emptySweepReport(
                status = SweepStatus.Complete,
                candidateNames = 1uL,
                skippedBusy = 1uL,
            ),
            emptySweepReport(
                status = SweepStatus.Complete,
                candidateNames = 1uL,
                skippedChanged = 1uL,
            ),
        )

        reports.forEach { report ->
            val service = pendingUploadService(
                dirProvider = singleDirProvider(pendingRoot),
                temporarySweeper = { _, _: Duration -> report },
            )

            val error = assertFailsWith<PendingUploadSweepIncompleteException> {
                service.sweepOrphans(
                    accountId = "account-1",
                    namespace = "send_uploads",
                    referencedPaths = emptySet(),
                    olderThan = Instant.DISTANT_FUTURE,
                )
            }

            assertEquals(report, error.report)
            assertTrue(orphan.exists())
        }
    }

    @Test
    fun `sweep propagates custom artifact deletion failure`() = runTest {
        val root = createTempDirectory("pending-upload-service").toRealPath()
        val pendingRoot = root.resolve("pending")
        val orphan = pendingRoot.resolve("orphan.bin")
            .writeArtifact(Instant.parse("2020-01-01T00:00:00Z"))
        val expected = IOException("delete failed")
        val deleteAttempts = mutableListOf<Path>()
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
            temporarySweeper = { _, _ ->
                emptySweepReport(status = SweepStatus.Complete)
            },
            deleteArtifact = { path ->
                deleteAttempts.add(path)
                throw expected
            },
        )

        val actual = assertFailsWith<IOException> {
            service.sweepOrphans(
                accountId = "account-1",
                namespace = "send_uploads",
                referencedPaths = emptySet(),
                olderThan = Instant.parse("2030-01-01T00:00:00Z"),
            )
        }

        assertEquals(listOf(orphan), deleteAttempts)
        assertEquals(expected.message, actual.message)
        assertTrue(orphan.exists())
    }
}

private suspend fun assertPlaintextSizeMismatch(
    payload: ByteArray,
    declaredSize: Long,
) {
    val root = createTempDirectory("pending-upload-service").toRealPath()
    val stagedFile = root.resolve("pending.bin").also { path ->
        path.writeBytes(payload)
    }
    val service = pendingUploadService(
        dirProvider = singleDirProvider(root),
    )

    assertFailsWith<IOException> {
        service.readPlaintext(
            pendingUpload = PendingUploadFile(
                path = stagedFile.toString(),
                plainSize = declaredSize,
                encryptedSize = payload.size.toLong(),
            ),
            fileKey = "key".encodeToByteArray(),
        )
    }
}

private data class PendingUploadSweepArtifacts(
    val targetDir: Path,
    val referenced: Path,
    val referencedMarker: Path,
    val orphan: Path,
    val orphanMarker: Path,
    val recentGroupBase: Path,
    val recentGroupMarker: Path,
    val unknownFile: Path,
    val staleStagingTemp: Path,
    val recentStagingTemp: Path,
    val unknownReservedArtifact: Path,
    val otherAccountOrphan: Path,
    val otherNamespaceOrphan: Path,
)

private fun createPendingUploadSweepArtifacts(
    root: Path,
    staleAt: Instant,
    recentAt: Instant,
): PendingUploadSweepArtifacts {
    val targetDir = root.resolve("send_uploads").resolve("account-1")
    return PendingUploadSweepArtifacts(
        targetDir = targetDir,
        referenced = targetDir.resolve("referenced.bin").writeArtifact(staleAt),
        referencedMarker = targetDir.resolve("referenced.bin.uploaded").writeArtifact(staleAt),
        orphan = targetDir.resolve("orphan.bin").writeArtifact(staleAt),
        orphanMarker = targetDir.resolve("orphan.bin.uploaded").writeArtifact(staleAt),
        recentGroupBase = targetDir.resolve("recent-group.bin").writeArtifact(staleAt),
        recentGroupMarker = targetDir.resolve("recent-group.bin.uploaded").writeArtifact(recentAt),
        unknownFile = targetDir.resolve("keep-me.txt").writeArtifact(staleAt),
        // An interrupted staging write leaves an owner-only temporary behind.
        // It is collected on its own age, even though the upload it was being
        // staged for is still referenced.
        staleStagingTemp = targetDir
            .resolve(
                temporaryArtifactName(
                    TemporaryArtifactRole.New,
                    "123e4567-e89b-42d3-a456-426614174000",
                ),
            )
            .writeArtifact(staleAt),
        recentStagingTemp = targetDir
            .resolve(
                temporaryArtifactName(
                    TemporaryArtifactRole.New,
                    "123e4567-e89b-42d3-a456-426614174001",
                ),
            )
            .writeArtifact(recentAt),
        unknownReservedArtifact = targetDir
            .resolve(".kg-tmp-future-protocol")
            .writeArtifact(staleAt),
        otherAccountOrphan = root
            .resolve("send_uploads")
            .resolve("account-2")
            .resolve("orphan.bin")
            .writeArtifact(staleAt),
        otherNamespaceOrphan = root
            .resolve("cipher_attachment_uploads")
            .resolve("account-1")
            .resolve("orphan.bin")
            .writeArtifact(staleAt),
    )
}

private fun scopedDirProvider(
    root: Path,
) = object : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): PendingUploadDirectory = PendingUploadDirectory(
        root = root.toLocalPath(),
        relativePath = AtomicRelativePath.fromComponents(
            AtomicPathComponent.parse(namespace),
            AtomicPathComponent.parse(accountId),
        ),
    )
}

private class ManagedSourceFileService : FileService {
    val deletedManagedSources = mutableListOf<String>()
    var readCalls: Int = 0

    override fun exists(uri: String): Boolean = File(uri.toPath()).exists()

    override fun readFromFile(uri: String): Source {
        readCalls += 1
        return File(uri.toPath())
            .inputStream()
            .asSource()
            .buffered()
    }

    override fun writeToFile(uri: String): Sink = File(uri.toPath())
        .outputStream()
        .asSink()
        .buffered()

    override fun delete(uri: String): Boolean = File(uri.toPath()).delete()

    override fun deleteManagedSourceFile(uri: String): Boolean {
        deletedManagedSources += uri
        return delete(uri)
    }
}

private class CopyingFileEncryptionCodec : FileEncryptionCodec {
    override fun decrypt(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = input

    override fun decrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ) {
        output.write(input.readByteArray())
    }

    override fun encrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = data

    override fun encrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ): FileEncryptionCodec.EncryptionResult {
        val data = input.readByteArray()
        output.write(data)
        return FileEncryptionCodec.EncryptionResult(
            plainSize = data.size.toLong(),
            encryptedSize = data.size.toLong(),
        )
    }
}

private fun String.toPath(): String =
    java.net.URI(this).let(::File).path

private fun Path.writeArtifact(
    modifiedAt: Instant,
): Path = apply {
    parent.toFile().mkdirs()
    writeBytes("artifact".encodeToByteArray())
    Files.setLastModifiedTime(
        this,
        FileTime.fromMillis(modifiedAt.toEpochMilliseconds()),
    )
}

private fun pendingUploadService(
    dirProvider: PendingUploadDirProvider,
    fileService: FileService = ManagedSourceFileService(),
    temporarySweeper: (com.artemchep.keyguard.util.io.LocalPath, Duration) -> SweepReport =
        { directory, olderThan ->
            com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts(
                directory = directory,
                olderThan = olderThan,
            )
        },
    deleteArtifact: (Path) -> Unit = { path ->
        Files.deleteIfExists(path)
        Unit
    },
) = EncryptedFilePendingUploadServiceJvm(
    dirProvider = dirProvider,
    fileService = fileService,
    fileEncryptionCodec = CopyingFileEncryptionCodec(),
    temporarySweeper = temporarySweeper,
    deleteArtifact = deleteArtifact,
)

/** Staging directory provider that ignores the account and namespace. */
private fun singleDirProvider(
    dir: Path,
) = object : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): PendingUploadDirectory {
        val parent = requireNotNull(dir.parent)
        return PendingUploadDirectory(
            root = parent.toLocalPath(),
            relativePath = AtomicRelativePath.fromComponents(
                AtomicPathComponent.parse(dir.fileName.toString()),
            ),
        )
    }
}

private fun emptySweepReport(
    status: SweepStatus,
    candidateNames: ULong = 0uL,
    skippedBusy: ULong = 0uL,
    skippedChanged: ULong = 0uL,
    firstFailure: FileSystemFailure? = null,
): SweepReport {
    val inspectionFailed = if (status == SweepStatus.Incomplete) 1uL else 0uL
    val classifiedCandidates = maxOf(
        candidateNames,
        skippedBusy + skippedChanged + inspectionFailed,
    )
    return SweepReport(
        status = status,
        entriesSeen = classifiedCandidates,
        candidateNames = classifiedCandidates,
        removed = 0uL,
        skippedYoung = 0uL,
        skippedBusy = skippedBusy,
        skippedUnsafe = 0uL,
        skippedChanged = skippedChanged,
        inspectionFailed = inspectionFailed,
        removalFailed = 0uL,
        firstFailure = firstFailure,
    )
}
