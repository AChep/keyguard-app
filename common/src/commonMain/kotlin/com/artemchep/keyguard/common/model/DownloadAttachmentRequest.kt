package com.artemchep.keyguard.common.model

sealed interface DownloadAttachmentRequest {
    data class ByLocalCipherAttachment(
        val localCipherId: String,
        val remoteCipherId: String?,
        val attachmentId: String,
    ) : DownloadAttachmentRequest {
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
