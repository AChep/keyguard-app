package com.artemchep.keyguard.common.model

sealed interface RemoveAttachmentRequest {
    data class ByDownloadId(
        val downloadId: String,
    ) : RemoveAttachmentRequest

    data class ByLocalCipherAttachment(
        val localCipherId: String,
        val remoteCipherId: String? = null,
        val attachmentId: String,
    ) : RemoveAttachmentRequest {
        constructor(
            cipher: DSecret,
            attachment: DSecret.Attachment.Remote,
        ) : this(
            localCipherId = cipher.id,
            remoteCipherId = cipher.service.remote?.id,
            attachmentId = attachment.id,
        )
    }
}
