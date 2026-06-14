package com.artemchep.keyguard.common.service.backup

sealed class BackupException(
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class AttachmentDecryption(
        val localCipherId: String,
        val remoteCipherId: String,
        val attachmentId: String,
        val attachmentName: String,
        cause: Throwable,
    ) : BackupException(
        retryable = false,
        message = "Failed to decrypt attachment '$attachmentName'.",
        cause = cause,
    )
}

internal fun Throwable.toBackupAttachmentDecryptionExceptionOrSelf(
    localCipherId: String,
    remoteCipherId: String,
    attachmentId: String,
    attachmentName: String,
): Throwable {
    if (this is BackupException.AttachmentDecryption) {
        return this
    }
    return if (isBackupAttachmentDecryptionFailure()) {
        BackupException.AttachmentDecryption(
            localCipherId = localCipherId,
            remoteCipherId = remoteCipherId,
            attachmentId = attachmentId,
            attachmentName = attachmentName,
            cause = this,
        )
    } else {
        this
    }
}

private fun Throwable.isBackupAttachmentDecryptionFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val className = current::class.simpleName
        val message = current.message.orEmpty()
        if (className == "InvalidCipherTextException") {
            return true
        }
        if (message.contains("Message authentication codes do not match")) {
            return true
        }
        if (message.contains("Invalid encrypted data")) {
            return true
        }
        if (message.contains("Can not decrypt data with")) {
            return true
        }
        current = current.cause
    }
    return false
}
