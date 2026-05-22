package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.CanPreviewAttachment

fun createAttachmentPreviewRouteOrNull(
    localCipherId: String,
    remoteCipherId: String?,
    attachment: DSecret.Attachment,
    canPreviewAttachment: CanPreviewAttachment,
): AttachmentPreviewRoute? {
    val remoteAttachment = attachment as? DSecret.Attachment.Remote
        ?: return null
    if (canPreviewAttachment(remoteAttachment) != AttachmentPreviewPolicy.Previewable) {
        return null
    }

    return AttachmentPreviewRoute(
        args = AttachmentPreviewRoute.Args(
            localCipherId = localCipherId,
            remoteCipherId = remoteCipherId,
            attachmentId = remoteAttachment.id,
            fileName = remoteAttachment.fileName,
            encryptedSize = remoteAttachment.size,
        ),
    )
}
