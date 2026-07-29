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
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicWriteReceipt
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.lastModifiedMillis
import com.artemchep.keyguard.util.io.spool.buildSnapshot
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class EncryptedFilePendingUploadServiceJvm internal constructor(
    private val dirProvider: PendingUploadDirProvider,
    private val fileService: FileService,
    private val fileEncryptionCodec: FileEncryptionCodec,
    private val stagingSpoolFactory: StagingSpoolFactory =
        DefaultStagingSpoolFactory(),
    private val temporarySweeper: (LocalPath, Duration) -> SweepReport =
        ::sweepTemporaryArtifacts,
    private val deleteArtifact: (java.nio.file.Path) -> Unit = { path ->
        Files.deleteIfExists(path)
        Unit
    },
) : EncryptedFilePendingUploadService {
    constructor(
        directDI: DirectDI,
    ) : this(
        dirProvider = directDI.instance(),
        fileService = directDI.instance(),
        fileEncryptionCodec = directDI.instance(),
        stagingSpoolFactory = directDI.instance(),
    )

    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = withContext(Dispatchers.IO) {
        val dir = dirProvider.get(
            accountId = accountId,
            namespace = namespace,
        )
        val finalDestination = dir.destination(
            AtomicPathComponent.parse("$fileId$PENDING_UPLOAD_SUFFIX"),
        )
        val finalPath = finalDestination.path
        val finalFile = File(finalPath.value)

        // The encoder publishes its output atomically from an owner-only
        // temporary sibling, so a failure here leaves any previously staged
        // file at this path untouched rather than truncated.
        val atomicResult = fileService
            .readFromFile(sourceUri)
            .use { source ->
                fileEncryptionCodec.encryptToPath(
                    input = source,
                    output = finalDestination,
                    key = fileKey,
                    synchronization = SynchronizationPolicy.Prefer(
                        preferred = SyncLevel.FileAndNamespaceSynchronized,
                        minimum = SyncLevel.FileSynchronized,
                    ),
                )
            }

        enforcePendingUploadPostPublication(
            markerPath = uploadedMarkerFile(finalFile.path).toPath(),
            receipt = atomicResult.receipt,
        )
        if (canDiscardManagedSourceAfterStaging(atomicResult.receipt)) {
            runCatching {
                fileService.deleteManagedSourceFile(sourceUri)
            }
        }

        PendingUploadFile(
            path = finalPath.value,
            plainSize = atomicResult.value.plainSize,
            encryptedSize = atomicResult.value.encryptedSize,
        )
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ): Unit = withContext(Dispatchers.IO) {
        artifactFiles(pendingUpload.path).forEach { artifact ->
            fileService.delete(artifact.toURI().toString())
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
        File(pendingUpload.path)
            .inputStream()
            .asSource()
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
                            source.readByteArray()
                        }
                    }
            }
    }

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ): Unit = withContext(Dispatchers.IO) {
        val markerFile = uploadedMarkerFile(pendingUpload)
        markerFile.parentFile?.mkdirs()
        markerFile.writeText("")
    }

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = withContext(Dispatchers.IO) {
        uploadedMarkerFile(pendingUpload).exists()
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
        val nativeSweep = temporarySweeper(
            dir.path,
            (Clock.System.now() - olderThan).coerceAtLeast(Duration.ZERO),
        )
        nativeSweep.requireCompletePendingUploadSweep()
        val normalizedReferencedPaths = referencedPaths
            .mapTo(mutableSetOf(), ::normalizePath)

        File(dir.path.value)
            .listFiles()
            .orEmpty()
            .asSequence()
            // Match on the name before touching the filesystem, so unrelated
            // files in the directory never cost a stat.
            .mapNotNull { file ->
                file
                    .takeUnless { candidate ->
                        isReservedTemporaryArtifactName(candidate.name)
                    }
                    ?.let(::pendingUploadBasePathOrNull)
                    ?.let { basePath -> basePath to file }
            }
            .filter { (_, file) ->
                Files.isRegularFile(
                    file.toPath(),
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
            .groupBy(
                keySelector = { (basePath, _) -> basePath },
                valueTransform = { (_, file) -> file },
            )
            .filterKeys { basePath -> basePath !in normalizedReferencedPaths }
            .values
            .filter { artifacts ->
                artifacts.all { artifact -> artifact.isStaleAt(olderThan) }
            }
            .flatten()
            .forEach { artifact ->
                deleteArtifact(artifact.toPath())
            }
    }
}

/**
 * Treats an artifact whose timestamp cannot be read as not stale, so an
 * unreadable file is kept rather than deleted.
 */
private fun File.isStaleAt(
    olderThan: Instant,
): Boolean {
    val lastModifiedMillis = toLocalPath().lastModifiedMillis()
        ?: return false
    return lastModifiedMillis <= olderThan.toEpochMilliseconds()
}

private fun uploadedMarkerFile(
    pendingUpload: PendingUploadFile,
) = uploadedMarkerFile(pendingUpload.path)

private fun uploadedMarkerFile(
    path: String,
) = File("$path$MARKER_EXTENSION")

/**
 * Every file that makes up one staged upload. The orphan sweep recognizes
 * the same group by matching these suffixes.
 */
private fun artifactFiles(
    basePath: String,
) = listOf(
    File(basePath),
    uploadedMarkerFile(basePath),
)

private fun pendingUploadBasePathOrNull(
    file: File,
): String? {
    val basePath = when {
        file.name.endsWith(PENDING_UPLOAD_MARKER_SUFFIX) ->
            file.path.removeSuffix(MARKER_EXTENSION)

        file.name.endsWith(PENDING_UPLOAD_SUFFIX) ->
            file.path

        else -> return null
    }
    return normalizePath(basePath)
}

private fun normalizePath(
    path: String,
): String = File(path)
    .toPath()
    .toAbsolutePath()
    .normalize()
    .toString()

internal fun enforcePendingUploadPostPublication(
    markerPath: java.nio.file.Path,
    receipt: AtomicWriteReceipt,
) {
    var markerFailure: java.io.IOException? = null
    try {
        Files.deleteIfExists(markerPath)
    } catch (error: java.io.IOException) {
        markerFailure = error
    }

    var cleanupFailure: AtomicCleanupIncompleteException? = null
    try {
        receipt.requireCleanupComplete()
    } catch (error: AtomicCleanupIncompleteException) {
        cleanupFailure = error
    }

    val primaryFailure = markerFailure
    if (primaryFailure != null) {
        cleanupFailure?.let(primaryFailure::addSuppressed)
        throw primaryFailure
    }
    if (cleanupFailure != null) {
        throw cleanupFailure
    }
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
