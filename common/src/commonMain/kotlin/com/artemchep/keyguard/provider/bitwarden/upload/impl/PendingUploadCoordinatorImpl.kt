package com.artemchep.keyguard.provider.bitwarden.upload.impl

import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import org.kodein.di.DirectDI
import org.kodein.di.instance

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

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ) = encryptedFilePendingUploadService.delete(pendingUpload)

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ) = encryptedFilePendingUploadService.markUploaded(pendingUpload)

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ) = encryptedFilePendingUploadService.isUploaded(pendingUpload)

    override suspend fun <T> persist(
        createdPendingUploads: Collection<PendingUploadFile>,
        removedPendingUploads: Collection<PendingUploadFile>,
        block: suspend () -> T,
    ): T = try {
        block().also {
            removedPendingUploads.forEach { pendingUpload ->
                deleteBestEffort(pendingUpload)
            }
        }
    } catch (e: Throwable) {
        createdPendingUploads.forEach { pendingUpload ->
            deleteBestEffort(pendingUpload)
        }
        throw e
    }

    private suspend fun deleteBestEffort(
        pendingUpload: PendingUploadFile,
    ) {
        runCatching {
            delete(pendingUpload)
        }
    }
}
