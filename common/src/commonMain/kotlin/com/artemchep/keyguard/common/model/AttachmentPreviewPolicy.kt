package com.artemchep.keyguard.common.model

sealed interface AttachmentPreviewPolicy {
    data object Previewable : AttachmentPreviewPolicy

    data class TooLarge(
        val maxBytes: Long,
        val actualBytes: Long? = null,
    ) : AttachmentPreviewPolicy

    data object UnsupportedType : AttachmentPreviewPolicy

    data object UnsupportedPlatform : AttachmentPreviewPolicy
}
