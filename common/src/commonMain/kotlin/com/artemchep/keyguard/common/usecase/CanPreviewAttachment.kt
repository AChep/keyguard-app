package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.DSecret

interface CanPreviewAttachment {
    operator fun invoke(
        fileName: String,
        encryptedSize: Long?,
    ): AttachmentPreviewPolicy

    operator fun invoke(
        attachment: DSecret.Attachment.Remote,
    ): AttachmentPreviewPolicy = invoke(
        fileName = attachment.fileName,
        encryptedSize = attachment.size,
    )
}
