package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AttachmentPreviewLimits
import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.isAttachmentPreviewSupported
import com.artemchep.keyguard.common.usecase.CanPreviewAttachment

class CanPreviewAttachmentImpl : CanPreviewAttachment {
    override fun invoke(
        fileName: String,
        encryptedSize: Long?,
    ): AttachmentPreviewPolicy {
        if (!isAttachmentPreviewSupported(fileName)) {
            return AttachmentPreviewPolicy.UnsupportedType
        }
        // The reasoning behind the maximum size is that we are
        // holding the entire file in memory after downloading it,
        // therefore we are very susceptible to the OOMs.
        val maxEncryptedSize = AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES
        if (encryptedSize != null && encryptedSize > maxEncryptedSize) {
            return AttachmentPreviewPolicy.TooLarge(
                maxBytes = maxEncryptedSize,
                actualBytes = encryptedSize,
            )
        }

        return AttachmentPreviewPolicy.Previewable
    }
}
