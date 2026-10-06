package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.crypto.encryptToPath
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.artifact.SweepReport
import com.artemchep.keyguard.util.io.artifact.SweepStatus
import com.artemchep.keyguard.util.io.artifact.isReservedTemporaryArtifactName
import com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts
import com.artemchep.keyguard.util.io.atomic.AchievedSyncLevel
import com.artemchep.keyguard.util.io.atomic.AtomicCleanupIncompleteException
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicWriteReceipt
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.lastModifiedMillis
import com.artemchep.keyguard.util.io.source
import com.artemchep.keyguard.util.io.spool.buildSnapshot
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readTo

/** Shared staging, publication, and cleanup policy for encrypted pending uploads. */
internal class EncryptedFilePendingUploadServiceImpl(
    private val dirProvider: PendingUploadDirProvider,
    private val fileService: FileService,
    private val fileEncryptionCodec: FileEncryptionCodec,
    private val fileSystem: PendingUploadFileSystem,
    private val stagingSpoolFactory: StagingSpoolFactory = DefaultStagingSpoolFactory(),
    private val temporarySweeper: (LocalPath, Duration) -> SweepReport = ::sweepTemporaryArtifacts,
    private val deleteArtifact: (LocalPath) -> Unit = fileSystem::deleteIfExists,
) : EncryptedFilePendingUploadService {
    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = withContext(Dispatchers.IO) {
        val destination = stagingDestination(accountId, namespace, fileId)
        val staged = fileService
            .readFromFile(sourceUri)
            .use { source -> stageInto(destination, source, fileKey) }
        if (canDiscardManagedSourceAfterStaging(staged.receipt)) {
            runCatching { fileService.deleteManagedSourceFile(sourceUri) }
        }
        staged.file
    }

    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        source: Source,
        fileKey: ByteArray,
    ): PendingUploadFile = withContext(Dispatchers.IO) {
        val destination = stagingDestination(accountId, namespace, fileId)
        stageInto(destination, source, fileKey).file
    }

    /**
     * Resolves the staged file's destination. Runs before any source is
     * opened so invalid path components fail without touching the source.
     */
    private suspend fun stagingDestination(
        accountId: String,
        namespace: String,
        fileId: String,
    ): AtomicFileDestination {
        val dir = dirProvider.get(
            accountId = accountId,
            namespace = namespace,
        )
        return dir.destination(
            AtomicPathComponent.parse("$fileId$PENDING_UPLOAD_SUFFIX"),
        )
    }

    /** Encrypts the borrowed [source] into [destination]; the caller closes the source. */
    private fun stageInto(
        destination: AtomicFileDestination,
        source: Source,
        fileKey: ByteArray,
    ): StagedUpload {
        // The encoder publishes its output atomically from an owner-only
        // temporary sibling, so a failure here leaves any previously staged
        // file at this path untouched rather than truncated.
        val atomicResult = fileEncryptionCodec.encryptToPath(
            input = source,
            output = destination,
            key = fileKey,
            synchronization = SynchronizationPolicy.Prefer(
                preferred = SyncLevel.FileAndNamespaceSynchronized,
                minimum = SyncLevel.FileSynchronized,
            ),
        )
        val finalPath = destination.path
        enforcePendingUploadPostPublication(atomicResult.receipt) {
            fileSystem.deleteIfExists(uploadedMarkerPath(finalPath))
        }
        return StagedUpload(
            file = PendingUploadFile(
                path = finalPath.value,
                plainSize = atomicResult.value.plainSize,
                encryptedSize = atomicResult.value.encryptedSize,
            ),
            receipt = atomicResult.receipt,
        )
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ): Unit = withContext(Dispatchers.IO) {
        artifactPaths(LocalPath(pendingUpload.path)).forEach { artifact ->
            fileService.delete(artifact.toFileUriString())
        }
    }

    override suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        val expectedPlainSize = pendingUpload.plainSize
        require(expectedPlainSize in 0L..KEEPASS_FILE_UPLOAD_MAX_BYTES) {
            "KeePass attachment file must be 500 MB or smaller."
        }
        LocalPath(pendingUpload.path)
            .source()
            .buffered()
            .use { encryptedInput ->
                createPendingUploadPlaintextSpool(
                    stagingSpoolFactory = stagingSpoolFactory,
                    maximumBytes = expectedPlainSize,
                )
                    .buildSnapshot { plaintextOutput ->
                        fileEncryptionCodec.decrypt(
                            input = encryptedInput,
                            output = plaintextOutput,
                            key = fileKey,
                        )
                    }
                    .use { plaintext ->
                        if (plaintext.size != expectedPlainSize) {
                            throw IOException(
                                "Pending upload plaintext size does not match its metadata",
                            )
                        }
                        plaintext.openSource().use { source ->
                            ByteArray(expectedPlainSize.toInt()).also { source.readTo(it) }
                        }
                    }
            }
    }

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ): Unit = withContext(Dispatchers.IO) {
        val markerPath = uploadedMarkerPath(LocalPath(pendingUpload.path))
            .toKotlinxIoPath()
        markerPath.parent?.let(SystemFileSystem::createDirectories)
        // The marker carries no content: its existence is the whole signal.
        SystemFileSystem.sink(markerPath).close()
    }

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = withContext(Dispatchers.IO) {
        val markerPath = uploadedMarkerPath(LocalPath(pendingUpload.path))
        SystemFileSystem.exists(markerPath.toKotlinxIoPath())
    }

    override suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ): Unit = withContext(Dispatchers.IO) {
        val dir = dirProvider.get(
            accountId = accountId,
            namespace = namespace,
        )
        temporarySweeper(
            dir.path,
            (Clock.System.now() - olderThan).coerceAtLeast(Duration.ZERO),
        ).requireCompletePendingUploadSweep()

        val normalizedReferencedPaths = referencedPaths
            .mapTo(mutableSetOf(), fileSystem::normalizePath)
        fileSystem.list(dir.path)
            .asSequence()
            // Match on the name before touching the filesystem, so unrelated
            // and referenced files never cost a stat.
            .filterNot { path -> isReservedTemporaryArtifactName(path.toKotlinxIoPath().name) }
            .mapNotNull { path ->
                pendingUploadBasePathOrNull(path)
                    ?.let(fileSystem::normalizePath)
                    ?.takeUnless { basePath -> basePath in normalizedReferencedPaths }
                    ?.let { basePath -> basePath to path }
            }
            .filter { (_, path) ->
                fileSystem.isRegularFile(path)
            }
            .groupBy(
                keySelector = { (basePath, _) -> basePath },
                valueTransform = { (_, path) -> path },
            )
            .values
            .filter { artifacts ->
                artifacts.all { artifact -> artifact.isStaleAt(olderThan) }
            }
            .flatten()
            .forEach(deleteArtifact)
    }
}

/**
 * Treats an artifact whose timestamp cannot be read as not stale, so an
 * unreadable file is kept rather than deleted.
 */
private fun LocalPath.isStaleAt(
    olderThan: Instant,
): Boolean {
    val lastModifiedMillis = lastModifiedMillis()
        ?: return false
    return lastModifiedMillis <= olderThan.toEpochMilliseconds()
}

private fun uploadedMarkerPath(
    path: LocalPath,
) = LocalPath("${path.value}$MARKER_EXTENSION")

/**
 * Every file that makes up one staged upload. The orphan sweep recognizes
 * the same group by matching these suffixes.
 */
private fun artifactPaths(
    basePath: LocalPath,
) = listOf(
    basePath,
    uploadedMarkerPath(basePath),
)

private fun pendingUploadBasePathOrNull(
    path: LocalPath,
): String? {
    val name = path.toKotlinxIoPath().name
    return when {
        name.endsWith(PENDING_UPLOAD_MARKER_SUFFIX) -> path.value.removeSuffix(MARKER_EXTENSION)
        name.endsWith(PENDING_UPLOAD_SUFFIX) -> path.value
        else -> null
    }
}

internal fun enforcePendingUploadPostPublication(
    receipt: AtomicWriteReceipt,
    deleteMarker: () -> Unit,
) {
    // A stale marker would make freshly staged bytes look already uploaded.
    // Clear it even if publication reported an incomplete temporary-file cleanup.
    try {
        deleteMarker()
    } catch (markerFailure: IOException) {
        try {
            receipt.requireCleanupComplete()
        } catch (cleanupFailure: AtomicCleanupIncompleteException) {
            markerFailure.addSuppressed(cleanupFailure)
        }
        throw markerFailure
    }
    receipt.requireCleanupComplete()
}

internal class PendingUploadSweepIncompleteException(
    val report: SweepReport,
) : IOException(
    "Pending-upload temporary sweep was not complete: " +
        "status=${report.status}, skippedBusy=${report.skippedBusy}, " +
        "skippedChanged=${report.skippedChanged}",
)

private fun SweepReport.requireCompletePendingUploadSweep() {
    if (
        status != SweepStatus.Complete ||
        skippedBusy > 0uL ||
        skippedChanged > 0uL
    ) {
        throw PendingUploadSweepIncompleteException(this)
    }
}

internal fun canDiscardManagedSourceAfterStaging(
    receipt: AtomicWriteReceipt,
): Boolean =
    receipt.achievedSyncLevel == AchievedSyncLevel.FileAndNamespaceSynchronized &&
        !receipt.cleanupIncomplete

private fun createPendingUploadPlaintextSpool(
    stagingSpoolFactory: StagingSpoolFactory,
    maximumBytes: Long,
) = stagingSpoolFactory.create(
    purpose = StagingPurpose.PendingUploadPlaintext,
    limits = SpoolLimits(
        memoryBytes = minOf(PENDING_UPLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES, maximumBytes),
        maximumBytes = maximumBytes,
    ),
    limitExceeded = { limit ->
        IOException("Pending upload plaintext exceeds the supported staging limit of $limit bytes")
    },
)

private const val MARKER_EXTENSION = ".uploaded"
private const val PENDING_UPLOAD_SUFFIX = ".bin"
private const val PENDING_UPLOAD_MARKER_SUFFIX = "$PENDING_UPLOAD_SUFFIX$MARKER_EXTENSION"
private const val PENDING_UPLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES = 2L * 1024L * 1024L

/** Result of staging one file, keeping the publish receipt for the caller's cleanup decisions. */
private class StagedUpload(
    val file: PendingUploadFile,
    val receipt: AtomicWriteReceipt,
)
