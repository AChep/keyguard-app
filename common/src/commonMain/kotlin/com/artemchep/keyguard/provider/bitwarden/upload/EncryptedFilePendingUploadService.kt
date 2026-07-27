package com.artemchep.keyguard.provider.bitwarden.upload

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
}
