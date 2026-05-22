package com.artemchep.keyguard.common.model

sealed class AttachmentPreviewException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class TooLarge(
        val maxBytes: Long,
        val actualBytes: Long? = null,
    ) : AttachmentPreviewException(
        message = "Attachment preview payload exceeds the $maxBytes bytes limit.",
    )

    class NetworkFailed(
        cause: Throwable? = null,
    ) : AttachmentPreviewException(
        message = "Failed to download attachment preview.",
        cause = cause,
    )

    class DecryptionFailed(
        cause: Throwable,
    ) : AttachmentPreviewException(
        message = "Failed to decrypt attachment preview.",
        cause = cause,
    )
}
