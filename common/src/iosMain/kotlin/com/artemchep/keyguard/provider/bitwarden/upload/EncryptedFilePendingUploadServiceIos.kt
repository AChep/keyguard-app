package com.artemchep.keyguard.provider.bitwarden.upload

object EncryptedFilePendingUploadServiceIos : EncryptedFilePendingUploadService {
    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = throw UnsupportedOperationException(
        "Pending uploads are not supported on iOS yet.",
    )

    override suspend fun markUploaded(pendingUpload: PendingUploadFile) {
    }

    override suspend fun isUploaded(pendingUpload: PendingUploadFile): Boolean = false

    override suspend fun delete(pendingUpload: PendingUploadFile) {
    }
}
