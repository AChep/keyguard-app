package com.artemchep.keyguard.core.store.bitwarden

import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile

internal data class PendingSendFileUploadReconciliationResult(
    val send: BitwardenSend,
    val obsoletePendingUpload: PendingUploadFile?,
)

internal fun BitwardenSend.withPendingUpload(
    pendingUpload: PendingUploadFile?,
): BitwardenSend {
    val file = file ?: return this
    return copy(
        file = file.copy(
            pendingUpload = pendingUpload,
        ),
    )
}

internal fun BitwardenSend.reconcilePendingSendFileUpload(
    local: BitwardenSend?,
    uploadCompletedLocally: Boolean,
): PendingSendFileUploadReconciliationResult {
    val localFile = local?.file
    val pendingUpload = localFile?.pendingUpload
    if (pendingUpload == null) {
        return PendingSendFileUploadReconciliationResult(
            send = this,
            obsoletePendingUpload = null,
        )
    }

    return if (
        uploadCompletedLocally &&
        hasCompletedPendingSendFileUpload(local, localFile, pendingUpload)
    ) {
        PendingSendFileUploadReconciliationResult(
            send = withPendingUpload(null),
            obsoletePendingUpload = pendingUpload,
        )
    } else {
        PendingSendFileUploadReconciliationResult(
            send = withPendingSendFileUpload(
                localFile = localFile,
                pendingUpload = pendingUpload,
            ),
            obsoletePendingUpload = null,
        )
    }
}

private fun BitwardenSend.withPendingSendFileUpload(
    localFile: BitwardenSend.File,
    pendingUpload: PendingUploadFile,
): BitwardenSend {
    val reconciledFile = (file ?: localFile).copy(
        pendingUpload = pendingUpload,
    )
    return copy(
        type = BitwardenSend.Type.File,
        file = reconciledFile,
        text = null,
    )
}

private fun BitwardenSend.hasCompletedPendingSendFileUpload(
    local: BitwardenSend,
    localFile: BitwardenSend.File,
    pendingUpload: PendingUploadFile,
): Boolean {
    if (local.type != BitwardenSend.Type.File || type != BitwardenSend.Type.File) {
        return false
    }

    val remoteFile = file
        ?: return false
    val localFileKey = localFile.keyBase64
        ?: return false
    if (remoteFile.keyBase64 != localFileKey) {
        return false
    }

    remoteFile.size?.let { remoteSize ->
        if (remoteSize != pendingUpload.encryptedSize) {
            return false
        }
    }

    if (local.service.remote != null && remoteFile.id != localFile.id) {
        return false
    }

    return true
}
