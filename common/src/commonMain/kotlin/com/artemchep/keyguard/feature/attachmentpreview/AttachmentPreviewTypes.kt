package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.CanPreviewAttachment
import com.artemchep.keyguard.feature.navigation.Route

fun createAttachmentPreviewRouteOrNull(
    localCipherId: String,
    remoteCipherId: String?,
    attachment: DSecret.Attachment,
    canPreviewAttachment: CanPreviewAttachment,
    attachmentPreviewRouteFactory: AttachmentPreviewRouteFactory,
): Route? {
    val remoteAttachment = attachment as? DSecret.Attachment.Remote
        ?: return null
    if (canPreviewAttachment(remoteAttachment) != AttachmentPreviewPolicy.Previewable) {
        return null
    }

    return attachmentPreviewRouteFactory.create(
        args = AttachmentPreviewRoute.Args(
            localCipherId = localCipherId,
            remoteCipherId = remoteCipherId,
            attachmentId = remoteAttachment.id,
            fileName = remoteAttachment.fileName,
            encryptedSize = remoteAttachment.size,
        ),
    )
}
