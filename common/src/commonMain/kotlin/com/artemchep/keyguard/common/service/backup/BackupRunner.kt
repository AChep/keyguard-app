package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.export.ExportVaultDataService
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import kotlinx.coroutines.NonCancellable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Creates backup snapshots from exported vault data,
 * writes repository objects, and applies retention.
 */
class BackupRunner(
    private val exportVaultDataService: ExportVaultDataService,
    private val backupRepository: BackupRepository,
    private val backupObjectStoreFactory: BackupObjectStoreFactory,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val dateFormatter: DateFormatter,
    private val downloadTask: DownloadTask,
    private val downloadAttachmentMetadata: DownloadAttachmentMetadata,
    private val diagnostics: BackupDiagnostics = BackupDiagnostics.NoOp,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        exportVaultDataService = directDI.instance(),
        backupRepository = directDI.instance(),
        backupObjectStoreFactory = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        dateFormatter = directDI.instance(),
        downloadTask = directDI.instance(),
        downloadAttachmentMetadata = directDI.instance(),
        diagnostics = BackupDiagnostics(logRepository = directDI.instance<LogRepository>()),
    )

    private val mutex = Mutex()

    suspend fun run(
        config: BackupConfig,
        progress: BackupRunProgressContext? = null,
    ): BackupRunResult = mutex.withLock {
        diagnostics.backupRunStarted(
            includeAttachments = config.includeAttachments,
            retentionMaxSnapshots = config.retention.maxSnapshots,
        )
        try {
            progress?.report(BackupStep.Preparing)
            if (!config.canRun()) {
                val result = BackupRunResult(
                    snapshotId = null,
                    skipped = true,
                    reason = "backup_not_configured",
                )
                diagnostics.backupRunCompleted(result)
                return@withLock result
            }

            val password = config.password
                ?.takeIf { it.value.isNotEmpty() }

            val now = Clock.System.now()
            val store = backupObjectStoreFactory.open(config.store)

            val newlyWrittenBlobPaths = LinkedHashSet<String>()
            var indexWriteStarted = false
            var indexWriteCompleted = false
            try {
                progress?.report(BackupStep.OpeningRepository)
                val metadata = backupRepository.getOrCreateMetadata(
                    store = store,
                    password = password,
                    nowProvider = { now },
                    repoIdProvider = { cryptoGenerator.uuid() },
                )
                diagnostics.backupRepositoryReady(
                    formatVersion = metadata.formatVersion,
                    featureCount = metadata.features.size,
                )
                val indexState = readIndex(
                    store = store,
                    password = password,
                )
                val index = indexState.index
                diagnostics.backupIndexLoaded(
                    generation = index.generation,
                    attachmentCount = index.attachments.size,
                    blobCount = index.blobs.size,
                )

                progress?.report(BackupStep.ExportingVault)
                val data = exportVaultDataService.create(config.filter)
                val vaultJson = exportVaultDataService.exportJson(data)
                val vaultSize = vaultJson.encodeToByteArray().size.toLong()
                diagnostics.backupExportCreated(
                    cipherCount = data.ciphers.size,
                    folderCount = data.folders.size,
                    collectionCount = data.collections.size,
                    organizationCount = data.organizations.size,
                    vaultSize = vaultSize,
                )
                val attachmentResult = if (config.includeAttachments) {
                    backupAttachments(
                        store = store,
                        password = password,
                        ciphers = data.ciphers,
                        now = now,
                        index = index,
                        progress = progress,
                        newlyWrittenBlobPaths = newlyWrittenBlobPaths,
                    )
                } else {
                    BackupAttachmentResult(
                        index = index,
                        attachments = emptyList(),
                        newBlobCount = 0,
                        reusedBlobCount = 0,
                        skippedAttachmentCount = 0,
                    )
                }

                val snapshotId = createSnapshotId(now)
                val stats = BackupSnapshotStats(
                    cipherCount = data.ciphers.size,
                    attachmentCount = attachmentResult.attachments.size,
                    skippedAttachmentCount = attachmentResult.skippedAttachmentCount,
                    newBlobCount = attachmentResult.newBlobCount,
                    reusedBlobCount = attachmentResult.reusedBlobCount,
                )
                val manifest = BackupSnapshotManifest(
                    snapshotId = snapshotId,
                    createdAt = now,
                    options = BackupSnapshotOptions(
                        includeAttachments = config.includeAttachments,
                    ),
                    vault = BackupSnapshotVault(
                        size = vaultSize,
                    ),
                    attachments = attachmentResult.attachments,
                    stats = stats,
                )

                val snapshotEncryption = createObjectEncryption(password)
                val snapshotBlobIds = attachmentResult.attachments
                    .map { it.blobId }
                    .toSet()
                val snapshotIndex = BackupIndexSnapshot(
                    path = snapshotPath(snapshotId),
                    createdAt = now,
                    vaultSize = vaultSize,
                    blobIds = snapshotBlobIds,
                    encryption = snapshotEncryption,
                    stats = stats,
                )
                val updatedIndex = attachmentResult.index.copy(
                    indexId = cryptoGenerator.uuid(),
                    generation = index.generation + 1L,
                    parentIndexIds = indexState.parentIndexIds,
                    updatedAt = now,
                    snapshots = attachmentResult.index.snapshots + (snapshotId to snapshotIndex),
                )
                progress?.report(BackupStep.WritingSnapshot)
                backupRepository.writeSnapshot(
                    store = store,
                    objectPassword = snapshotEncryption.toPassword(),
                    snapshotId = snapshotId,
                    manifest = manifest,
                    vaultJson = vaultJson,
                )
                diagnostics.backupSnapshotWritten(
                    snapshotId = snapshotId,
                    vaultSize = vaultSize,
                    attachmentCount = attachmentResult.attachments.size,
                )
                progress?.report(BackupStep.WritingIndex)
                indexWriteStarted = true
                backupRepository.writeIndex(
                    store = store,
                    password = password,
                    index = updatedIndex,
                )
                indexWriteCompleted = true
                diagnostics.backupIndexWritten(
                    generation = updatedIndex.generation,
                    attachmentCount = updatedIndex.attachments.size,
                    blobCount = updatedIndex.blobs.size,
                )
                progress?.report(BackupStep.ApplyingRetention)
                applyRetention(
                    store = store,
                    password = password,
                    retention = config.retention,
                    index = updatedIndex,
                    now = now,
                )

                val result = BackupRunResult(
                    snapshotId = snapshotId,
                    skipped = false,
                    stats = stats,
                )
                diagnostics.backupRunCompleted(result)
                result
            } catch (e: Exception) {
                // We want to clean up the blobs even
                // on a regular cancellation exception.
                if (!indexWriteCompleted) {
                    withContext(NonCancellable) {
                        cleanupNewlyWrittenBlobs(
                            store = store,
                            password = password,
                            blobPaths = newlyWrittenBlobPaths,
                            indexWriteStarted = indexWriteStarted,
                        )
                    }
                }

                throw e
            } finally {
                store.close()
            }
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            diagnostics.backupRunFailed(e)
            throw e
        }
    }

    private suspend fun readIndex(
        store: BackupObjectStore,
        password: Password?,
    ): BackupIndexState {
        val indexes = backupRepository.readIndexes(
            store = store,
            password = password,
        )

        val validIndexes = indexes
            .filter { it.indexId.isNotBlank() }
        if (validIndexes.isEmpty()) {
            return BackupIndexState(
                index = BackupIndex(),
                parentIndexIds = emptySet(),
            )
        }

        val referencedParentIds = validIndexes
            .flatMap { it.parentIndexIds }
            .toSet()
        val heads = validIndexes
            .filter { it.indexId !in referencedParentIds }
            .ifEmpty {
                val latestGeneration = validIndexes.maxOf { it.generation }
                validIndexes.filter { it.generation == latestGeneration }
            }
        return BackupIndexState(
            index = mergeIndexHeads(heads),
            parentIndexIds = heads
                .map { it.indexId }
                .toSet(),
        )
    }

    private suspend fun backupAttachments(
        store: BackupObjectStore,
        password: Password?,
        ciphers: List<DSecret>,
        now: Instant,
        index: BackupIndex,
        progress: BackupRunProgressContext?,
        newlyWrittenBlobPaths: MutableSet<String>,
    ): BackupAttachmentResult {
        val indexAttachments = index.attachments.toMutableMap()
        val indexBlobs = index.blobs.toMutableMap()
        val manifestAttachments = mutableListOf<BackupSnapshotAttachment>()
        val candidates = mutableListOf<BackupAttachmentCandidate>()
        val plannedBlobsByFingerprint = mutableMapOf<String, PlannedBlob>()
        val validationDecisionsByBlobId = mutableMapOf<String, IndexedBlobDecision>()
        var newBlobCount = 0
        var reusedBlobCount = 0
        var skippedAttachmentCount = 0

        diagnostics.backupAttachmentsStarted(
            cipherCount = ciphers.size,
            indexedAttachmentCount = index.attachments.size,
            indexedBlobCount = index.blobs.size,
        )
        val totalAttachmentCount = ciphers.sumOf { it.attachments.size }
        var scannedAttachmentCount = 0
        suspend fun reportScanProgress() {
            progress?.report(
                step = BackupStep.ScanningAttachments,
                details = BackupRunProgressDetails(
                    itemsProcessed = scannedAttachmentCount,
                    itemsTotal = totalAttachmentCount,
                ),
            )
        }
        reportScanProgress()
        ciphers.forEach { cipher ->
            cipher.attachments.forEach { attachment ->
                scannedAttachmentCount += 1
                val remoteAttachment = attachment as? DSecret.Attachment.Remote
                if (remoteAttachment == null) {
                    skippedAttachmentCount += 1
                    diagnostics.backupAttachmentSkipped(
                        localCipherId = cipher.id,
                        attachmentId = attachment.id,
                        reason = "local_attachment",
                    )
                    reportScanProgress()
                    return@forEach
                }

                val fingerprint = BackupAttachmentFingerprint.remote(
                    cipher = cipher,
                    attachment = remoteAttachment,
                    cryptoGenerator = cryptoGenerator,
                )
                val indexedBlobId = indexAttachments[fingerprint]?.blobId
                val indexedBlob = indexedBlobId?.let(indexBlobs::get)
                val indexedBlobDecision = if (indexedBlobId != null && indexedBlob != null) {
                    resolveIndexedBlob(
                        store = store,
                        now = now,
                        blobId = indexedBlobId,
                        blob = indexedBlob,
                        validationDecisionsByBlobId = validationDecisionsByBlobId,
                    )
                } else {
                    null
                }
                val cached = indexedBlobDecision?.cached == true
                val plannedBlob = if (cached) {
                    val blob = requireNotNull(indexedBlob)
                    PlannedBlob(
                        blobId = requireNotNull(indexedBlobId),
                        blobPath = blob.path,
                        encryption = blob.encryption,
                        lastValidatedAt = indexedBlobDecision.lastValidatedAt,
                    )
                } else {
                    plannedBlobsByFingerprint.getOrPut(fingerprint) {
                        val blobId = cryptoGenerator.uuid()
                        PlannedBlob(
                            blobId = blobId,
                            blobPath = BackupAttachmentFingerprint.blobPath(blobId),
                            encryption = createObjectEncryption(password),
                            lastValidatedAt = null,
                        )
                    }
                }
                candidates += BackupAttachmentCandidate(
                    cipher = cipher,
                    attachment = remoteAttachment,
                    fingerprint = fingerprint,
                    blobId = plannedBlob.blobId,
                    blobPath = plannedBlob.blobPath,
                    encryption = plannedBlob.encryption,
                    lastValidatedAt = plannedBlob.lastValidatedAt,
                    cached = cached,
                )
                reportScanProgress()
            }
        }

        val candidatesToDownload = candidates.filter { !it.cached }
            .distinctBy { it.blobId }
        val totalDownloadBytes = candidatesToDownload
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.attachment.size }
        var processedAttachmentCount = 0
        var downloadedBytes = 0L
        suspend fun reportAttachmentProgress(
            currentDownloadedBytes: Long = 0L,
        ) {
            val totalDownloadedBytes = downloadedBytes + currentDownloadedBytes
            progress?.report(
                step = BackupStep.BackingUpAttachments,
                details = BackupRunProgressDetails(
                    itemsProcessed = processedAttachmentCount,
                    itemsTotal = candidates.size,
                    downloadedBytes = totalDownloadedBytes.takeIf {
                        totalDownloadBytes != null
                    },
                    totalBytes = totalDownloadBytes,
                ),
            )
        }
        reportAttachmentProgress()
        val writtenBlobIds = mutableSetOf<String>()
        candidates.forEach { candidate ->
            val cipher = candidate.cipher
            val remoteAttachment = candidate.attachment
            val blobAlreadyWritten = candidate.blobId in writtenBlobIds
            val encryptedSize = if (candidate.cached || blobAlreadyWritten) {
                reusedBlobCount += 1
                val size = indexBlobs[candidate.blobId]?.encryptedSize
                diagnostics.backupAttachmentBlobReused(
                    localCipherId = cipher.id,
                    remoteCipherId = remoteAttachment.remoteCipherId,
                    attachmentId = remoteAttachment.id,
                    plainSize = remoteAttachment.size,
                    encryptedSize = size,
                )
                size
            } else {
                newBlobCount += 1
                var currentDownloadedBytes = 0L
                backupRepository.writeBlob(
                    store = store,
                    objectPassword = candidate.encryption.toPassword(),
                    blobPath = candidate.blobPath,
                ) { sink ->
                    writeAttachment(
                        sink = sink,
                        cipher = cipher,
                        attachment = remoteAttachment,
                        onProgress = { loading ->
                            val downloaded = loading.downloaded
                            if (downloaded != null) {
                                currentDownloadedBytes = downloaded.coerceAtLeast(0L)
                                reportAttachmentProgress(currentDownloadedBytes)
                            }
                        },
                    )
                }.also {
                    writtenBlobIds += candidate.blobId
                    newlyWrittenBlobPaths += candidate.blobPath
                    downloadedBytes += remoteAttachment.size.coerceAtLeast(0L)
                }
            }

            processedAttachmentCount += 1
            reportAttachmentProgress()

            val previousAttachment = indexAttachments[candidate.fingerprint]
            indexAttachments[candidate.fingerprint] = BackupIndexAttachment(
                blobId = candidate.blobId,
                plainSize = remoteAttachment.size,
                createdAt = previousAttachment?.createdAt ?: now,
                lastSeenAt = now,
            )

            val previousBlob = indexBlobs[candidate.blobId]
            val lastValidatedAt = if (candidate.cached) {
                candidate.lastValidatedAt
            } else {
                previousBlob?.lastValidatedAt ?: now
            }
            indexBlobs[candidate.blobId] = BackupIndexBlob(
                path = candidate.blobPath,
                plainSize = remoteAttachment.size,
                encryptedSize = encryptedSize ?: previousBlob?.encryptedSize,
                createdAt = previousBlob?.createdAt ?: now,
                lastSeenAt = now,
                lastValidatedAt = lastValidatedAt,
                encryption = candidate.encryption,
            )

            manifestAttachments += BackupSnapshotAttachment(
                accountId = cipher.accountId,
                localCipherId = cipher.id,
                remoteCipherId = remoteAttachment.remoteCipherId,
                attachmentId = remoteAttachment.id,
                fileName = remoteAttachment.fileName,
                plainSize = remoteAttachment.size,
                fingerprint = candidate.fingerprint,
                blobId = candidate.blobId,
                blobPath = candidate.blobPath,
                exportPath = "attachments/${remoteAttachment.id}/${remoteAttachment.fileName}",
            )
        }

        diagnostics.backupAttachmentsCompleted(
            attachmentCount = manifestAttachments.size,
            newBlobCount = newBlobCount,
            reusedBlobCount = reusedBlobCount,
            skippedAttachmentCount = skippedAttachmentCount,
        )
        return BackupAttachmentResult(
            index = index.copy(
                attachments = indexAttachments.toMap(),
                blobs = indexBlobs.toMap(),
            ),
            attachments = manifestAttachments,
            newBlobCount = newBlobCount,
            reusedBlobCount = reusedBlobCount,
            skippedAttachmentCount = skippedAttachmentCount,
        )
    }

    private suspend fun cleanupNewlyWrittenBlobs(
        store: BackupObjectStore,
        password: Password?,
        blobPaths: Set<String>,
        indexWriteStarted: Boolean,
    ) {
        if (blobPaths.isEmpty()) {
            return
        }

        val indexedBlobPaths = if (indexWriteStarted) {
            // We do not know if the index was actually written to the store
            // or not, therefore we have to fetch the latest index and check
            // that it doesn't contain the newly downloaded blobs.
            try {
                readIndex(
                    store = store,
                    password = password,
                ).index.blobs
                    .values
                    .map { it.path }
                    .toSet()
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                return // can not prove index was not written
            }
        } else {
            emptySet()
        }

        blobPaths
            .filter { it !in indexedBlobPaths }
            .forEach { blobPath ->
                try {
                    backupRepository.deleteBlob(
                        store = store,
                        blobPath = blobPath,
                    )
                } catch (e: Exception) {
                    e.throwIfFatalOrCancellation()
                }
            }
    }

    private suspend fun resolveIndexedBlob(
        store: BackupObjectStore,
        now: Instant,
        blobId: String,
        blob: BackupIndexBlob,
        validationDecisionsByBlobId: MutableMap<String, IndexedBlobDecision>,
    ): IndexedBlobDecision = validationDecisionsByBlobId.getOrPut(blobId) {
        if (!shouldValidateBlob(blob, now)) {
            return@getOrPut IndexedBlobDecision(
                cached = backupRepository.hasBlob(
                    store = store,
                    blobPath = blob.path,
                ),
                lastValidatedAt = blob.lastValidatedAt,
            )
        }

        when (
            backupRepository.validateBlob(
                store = store,
                objectPassword = blob.encryption.toPassword(),
                blobPath = blob.path,
            )
        ) {
            BackupBlobValidationResult.Valid -> IndexedBlobDecision(
                cached = true,
                lastValidatedAt = now,
            )

            BackupBlobValidationResult.Invalid -> IndexedBlobDecision(
                cached = false,
                lastValidatedAt = null,
            )

            BackupBlobValidationResult.Unavailable -> IndexedBlobDecision(
                cached = true,
                lastValidatedAt = blob.lastValidatedAt,
            )
        }
    }

    private fun shouldValidateBlob(
        blob: BackupIndexBlob,
        now: Instant,
    ): Boolean {
        val anchor = blob.lastValidatedAt ?: blob.createdAt
        val age = now - anchor
        if (age < 7.days) {
            return false
        }
        if (age >= 14.days) {
            return true
        }
        return cryptoGenerator.random(0..99) < 30
    }

    private suspend fun writeAttachment(
        sink: kotlinx.io.Sink,
        cipher: DSecret,
        attachment: DSecret.Attachment.Remote,
        onProgress: suspend (DownloadProgress.Loading) -> Unit = {},
    ) {
        var sourceType: String? = null
        try {
            val writer = DownloadWriter.SinkWriter(sink)
            val request = DownloadAttachmentRequest.ByLocalCipherAttachment(
                localCipherId = cipher.id,
                remoteCipherId = attachment.remoteCipherId,
                attachmentId = attachment.id,
            )
            val meta = downloadAttachmentMetadata(request)
                .bind()
            val flow = when (val source = meta.source) {
                is DownloadAttachmentRequestData.DirectSource -> {
                    sourceType = "direct"
                    diagnostics.backupAttachmentDownloadStarted(
                        localCipherId = cipher.id,
                        remoteCipherId = attachment.remoteCipherId,
                        attachmentId = attachment.id,
                        attachmentName = attachment.fileName,
                        sourceType = sourceType,
                        plainSize = attachment.size,
                    )
                    downloadTask.fileLoader(
                        data = source.data,
                        key = meta.encryptionKey,
                        writer = writer,
                    )
                }

                is DownloadAttachmentRequestData.UrlSource -> {
                    sourceType = "url"
                    diagnostics.backupAttachmentDownloadStarted(
                        localCipherId = cipher.id,
                        remoteCipherId = attachment.remoteCipherId,
                        attachmentId = attachment.id,
                        attachmentName = attachment.fileName,
                        sourceType = sourceType,
                        plainSize = attachment.size,
                    )
                    downloadTask.fileLoader(
                        url = source.url,
                        key = meta.encryptionKey,
                        writer = writer,
                    )
                }
            }
            val complete = flow
                .onEach { progress ->
                    if (progress is DownloadProgress.Loading) {
                        onProgress(progress)
                    }
                }
                .last()
            require(complete is DownloadProgress.Complete) {
                "Attachment download did not complete."
            }
            complete.result.fold(
                ifLeft = { error ->
                    throw error.toBackupAttachmentDecryptionExceptionOrSelf(
                        localCipherId = cipher.id,
                        remoteCipherId = attachment.remoteCipherId,
                        attachmentId = attachment.id,
                        attachmentName = attachment.fileName,
                    )
                },
                ifRight = { },
            )
            diagnostics.backupAttachmentDownloadCompleted(
                localCipherId = cipher.id,
                remoteCipherId = attachment.remoteCipherId,
                attachmentId = attachment.id,
                sourceType = sourceType,
                plainSize = attachment.size,
            )
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            diagnostics.backupAttachmentDownloadFailed(
                localCipherId = cipher.id,
                remoteCipherId = attachment.remoteCipherId,
                attachmentId = attachment.id,
                sourceType = sourceType,
                error = e,
            )
            throw e
        }
    }

    private suspend fun applyRetention(
        store: BackupObjectStore,
        password: Password?,
        retention: BackupRetention,
        index: BackupIndex,
        now: Instant,
    ) {
        val maxSnapshots = retention.maxSnapshots
        if (maxSnapshots <= BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS) {
            diagnostics.backupRetentionStarted(
                maxSnapshots = maxSnapshots,
                snapshotCount = 0,
            )
            diagnostics.backupRetentionCompleted(
                maxSnapshots = maxSnapshots,
                retainedSnapshotCount = 0,
                deletedSnapshotCount = 0,
                deletedBlobCount = 0,
                indexUpdated = false,
            )
            return
        }

        val snapshotsByNewest = index.snapshots
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, BackupIndexSnapshot>> { it.value.createdAt }
                    .thenByDescending { it.key },
            )
        diagnostics.backupRetentionStarted(
            maxSnapshots = maxSnapshots,
            snapshotCount = snapshotsByNewest.size,
        )
        val retainedSnapshotIds = selectRetainedSnapshotIds(
            snapshotsByNewest = snapshotsByNewest,
            maxSnapshots = maxSnapshots,
            now = now,
        )
        val deletedSnapshotIds = snapshotsByNewest
            .map { it.key }
            .filter { it !in retainedSnapshotIds }
        val retainedSnapshots = index.snapshots
            .filterKeys { it in retainedSnapshotIds }
        val retainedBlobIds = retainedSnapshots
            .values
            .flatMap { it.blobIds }
            .toSet()

        val removedBlobPaths = index.blobs
            .filterKeys { it !in retainedBlobIds }
            .values
            .map { it.path }
        val retainedAttachments = index.attachments
            .filterValues { it.blobId in retainedBlobIds }
        val retainedBlobs = index.blobs
            .filterKeys { it in retainedBlobIds }
        val indexUpdated = retainedSnapshots != index.snapshots ||
                retainedAttachments != index.attachments ||
                retainedBlobs != index.blobs
        if (indexUpdated) {
            val retainedIndex = index.copy(
                indexId = cryptoGenerator.uuid(),
                generation = index.generation + 1L,
                parentIndexIds = setOf(index.indexId),
                updatedAt = now,
                snapshots = retainedSnapshots,
                attachments = retainedAttachments,
                blobs = retainedBlobs,
            )
            backupRepository.writeIndex(
                store = store,
                password = password,
                index = retainedIndex,
            )
            diagnostics.backupIndexWritten(
                generation = retainedIndex.generation,
                attachmentCount = retainedIndex.attachments.size,
                blobCount = retainedIndex.blobs.size,
            )
        }
        deletedSnapshotIds
            .forEach { snapshotId ->
                backupRepository.deleteSnapshot(
                    store = store,
                    snapshotId = snapshotId,
                )
            }
        removedBlobPaths.forEach { blobPath ->
            backupRepository.deleteBlob(
                store = store,
                blobPath = blobPath,
            )
        }
        diagnostics.backupRetentionCompleted(
            maxSnapshots = maxSnapshots,
            retainedSnapshotCount = retainedSnapshotIds.size,
            deletedSnapshotCount = deletedSnapshotIds.size,
            deletedBlobCount = removedBlobPaths.size,
            indexUpdated = indexUpdated,
        )
    }

    private fun selectRetainedSnapshotIds(
        snapshotsByNewest: List<Map.Entry<String, BackupIndexSnapshot>>,
        maxSnapshots: Int,
        now: Instant,
    ): Set<String> {
        if (maxSnapshots <= 0 || snapshotsByNewest.isEmpty()) {
            return emptySet()
        }

        val newestSnapshot = snapshotsByNewest.first()
        val retentionWindowStart = now - BackupRetentionMaxAge
        val eligibleSnapshots = snapshotsByNewest
            .filter { (snapshotId, snapshot) ->
                snapshotId == newestSnapshot.key || snapshot.createdAt >= retentionWindowStart
            }
        val retainedSnapshotIds = LinkedHashSet<String>()

        fun retain(snapshotId: String) {
            if (retainedSnapshotIds.size < maxSnapshots) {
                retainedSnapshotIds += snapshotId
            }
        }

        retain(newestSnapshot.key)
        BackupRetentionSparseBuckets
            .mapNotNull { bucket ->
                eligibleSnapshots.firstOrNull { (_, snapshot) ->
                    bucket.contains(
                        now = now,
                        createdAt = snapshot.createdAt,
                    )
                }
            }
            .forEach { (snapshotId, _) ->
                retain(snapshotId)
            }
        BackupRetentionDailyBuckets
            .mapNotNull { bucket ->
                eligibleSnapshots.firstOrNull { (_, snapshot) ->
                    bucket.contains(
                        now = now,
                        createdAt = snapshot.createdAt,
                    )
                }
            }
            .forEach { (snapshotId, _) ->
                retain(snapshotId)
            }
        eligibleSnapshots.forEach { (snapshotId, _) ->
            retain(snapshotId)
        }

        return retainedSnapshotIds
    }

    private data class BackupRetentionBucket(
        val minAge: Duration,
        val maxAge: Duration,
        val includeMaxAge: Boolean = false,
    ) {
        fun contains(
            now: Instant,
            createdAt: Instant,
        ): Boolean {
            val age = now - createdAt
            return age >= minAge &&
                    if (includeMaxAge) {
                        age <= maxAge
                    } else {
                        age < maxAge
                    }
        }
    }

    private fun mergeIndexHeads(
        heads: List<BackupIndex>,
    ): BackupIndex {
        require(heads.isNotEmpty()) {
            "Backup index merge requires at least one head."
        }
        if (heads.size == 1) {
            return heads.first()
        }

        val snapshots = mergeByKey(
            heads = heads,
            label = "snapshot",
            values = { it.snapshots },
        )
        val blobs = mergeByKey(
            heads = heads,
            label = "blob",
            values = { it.blobs },
        )
        val attachments = heads
            .flatMap { it.attachments.entries }
            .groupBy { it.key }
            .mapNotNull { (fingerprint, entries) ->
                val selected = entries
                    .map { it.value }
                    .filter { it.blobId in blobs }
                    .maxWithOrNull(
                        compareBy<BackupIndexAttachment> { it.lastSeenAt }
                            .thenBy { it.blobId },
                    )
                    ?: return@mapNotNull null
                fingerprint to selected
            }
            .toMap()

        return BackupIndex(
            generation = heads.maxOf { it.generation },
            updatedAt = heads
                .mapNotNull { it.updatedAt }
                .maxOrNull(),
            snapshots = snapshots,
            attachments = attachments,
            blobs = blobs,
        )
    }

    private fun <T> mergeByKey(
        heads: List<BackupIndex>,
        label: String,
        values: (BackupIndex) -> Map<String, T>,
    ): Map<String, T> {
        val result = mutableMapOf<String, T>()
        heads.forEach { head ->
            values(head).forEach { (id, value) ->
                val existing = result[id]
                if (existing != null && existing != value) {
                    error("Conflicting backup $label '$id' found while merging indexes.")
                }
                result[id] = value
            }
        }
        return result.toMap()
    }

    private fun createObjectEncryption(
        password: Password?,
    ): BackupObjectEncryption = if (password == null) {
        BackupObjectEncryption.None
    } else {
        BackupObjectEncryption(
            method = BackupObjectEncryptionMethod.ZipAes256,
            keyBase64 = base64Service.encodeToString(cryptoGenerator.seed(32)),
        )
    }

    private fun BackupObjectEncryption.toPassword(): Password? = when (method) {
        BackupObjectEncryptionMethod.None -> null
        BackupObjectEncryptionMethod.ZipAes256 -> Password(requireNotNull(keyBase64))
    }

    private fun snapshotPath(
        snapshotId: String,
    ): String = "snapshots/$snapshotId.zip"

    private fun createSnapshotId(
        now: Instant,
    ): String {
        val timestamp = dateFormatter.formatDateTimeMachine(now)
        return "$timestamp-${cryptoGenerator.uuid()}"
    }

    companion object {
        private val BackupRetentionMaxAge = 31.days
        private val BackupRetentionSparseBuckets = listOf(
            BackupRetentionBucket(
                minAge = 28.days,
                maxAge = BackupRetentionMaxAge,
                includeMaxAge = true,
            ),
            BackupRetentionBucket(
                minAge = 21.days,
                maxAge = 28.days,
            ),
            BackupRetentionBucket(
                minAge = 14.days,
                maxAge = 21.days,
            ),
            BackupRetentionBucket(
                minAge = 7.days,
                maxAge = 14.days,
            ),
        )
        private val BackupRetentionDailyBuckets = listOf(
            BackupRetentionBucket(
                minAge = 1.days,
                maxAge = 2.days,
            ),
            BackupRetentionBucket(
                minAge = 2.days,
                maxAge = 3.days,
            ),
            BackupRetentionBucket(
                minAge = 3.days,
                maxAge = 4.days,
            ),
            BackupRetentionBucket(
                minAge = 4.days,
                maxAge = 5.days,
            ),
            BackupRetentionBucket(
                minAge = 5.days,
                maxAge = 6.days,
            ),
            BackupRetentionBucket(
                minAge = 6.days,
                maxAge = 7.days,
            ),
        )
    }

    private data class BackupAttachmentResult(
        val index: BackupIndex,
        val attachments: List<BackupSnapshotAttachment>,
        val newBlobCount: Int,
        val reusedBlobCount: Int,
        val skippedAttachmentCount: Int,
    )

    private data class BackupAttachmentCandidate(
        val cipher: DSecret,
        val attachment: DSecret.Attachment.Remote,
        val fingerprint: String,
        val blobId: String,
        val blobPath: String,
        val encryption: BackupObjectEncryption,
        val lastValidatedAt: Instant?,
        val cached: Boolean,
    )

    private data class PlannedBlob(
        val blobId: String,
        val blobPath: String,
        val encryption: BackupObjectEncryption,
        val lastValidatedAt: Instant?,
    )

    private data class IndexedBlobDecision(
        val cached: Boolean,
        val lastValidatedAt: Instant?,
    )

    private data class BackupIndexState(
        val index: BackupIndex,
        val parentIndexIds: Set<String>,
    )
}

fun BackupRunResult.toStatus(
    startedAt: Instant,
    finishedAt: Instant,
): BackupStatus = BackupStatus(
    lastStartedAt = startedAt,
    lastFinishedAt = finishedAt,
    lastSnapshotId = snapshotId,
    lastSkippedReason = reason.takeIf { skipped },
    lastErrorMessage = null,
    lastStats = stats,
)
