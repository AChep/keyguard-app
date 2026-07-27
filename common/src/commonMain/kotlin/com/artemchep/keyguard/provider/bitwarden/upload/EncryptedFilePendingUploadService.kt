package com.artemchep.keyguard.provider.bitwarden.upload

import kotlin.time.Instant

interface EncryptedFilePendingUploadService {
    suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile

    suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = throw UnsupportedOperationException(
        "Reading pending uploads is not supported on this platform.",
    )

    suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    )

    suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean

    suspend fun delete(
        pendingUpload: PendingUploadFile,
    )

    /**
     * Deletes staged-file artifacts in one account namespace when their base
     * upload path is not referenced by local state and every sibling is older
     * than [olderThan].
     */
    suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    )
}
