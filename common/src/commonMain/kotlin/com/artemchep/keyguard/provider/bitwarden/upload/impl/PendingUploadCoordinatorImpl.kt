package com.artemchep.keyguard.provider.bitwarden.upload.impl

import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import com.artemchep.keyguard.provider.bitwarden.upload.deleteBestEffort
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Instant

class PendingUploadCoordinatorImpl(
    private val encryptedFilePendingUploadService: EncryptedFilePendingUploadService,
) : PendingUploadCoordinator {
    constructor(
        directDI: DirectDI,
    ) : this(
        encryptedFilePendingUploadService = directDI.instance(),
    )

    override suspend fun stage(
        target: PendingUploadTarget,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = encryptedFilePendingUploadService.stage(
        accountId = target.accountId,
        namespace = target.namespace,
        fileId = target.fileId,
        sourceUri = sourceUri,
        fileKey = fileKey,
    )

    override suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = encryptedFilePendingUploadService.readPlaintext(
        pendingUpload = pendingUpload,
        fileKey = fileKey,
    )

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ) = encryptedFilePendingUploadService.delete(pendingUpload)

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ) = encryptedFilePendingUploadService.markUploaded(pendingUpload)

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ) = encryptedFilePendingUploadService.isUploaded(pendingUpload)

    override suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ) = encryptedFilePendingUploadService.sweepOrphans(
        accountId = accountId,
        namespace = namespace,
        referencedPaths = referencedPaths,
        olderThan = olderThan,
    )

    override suspend fun <T> persist(
        createdPendingUploads: Collection<PendingUploadFile>,
        removedPendingUploads: Collection<PendingUploadFile>,
        block: suspend () -> T,
    ): T = try {
        block().also {
            deleteBestEffort(removedPendingUploads)
        }
    } catch (e: Throwable) {
        deleteBestEffort(createdPendingUploads)
        throw e
    }
}
