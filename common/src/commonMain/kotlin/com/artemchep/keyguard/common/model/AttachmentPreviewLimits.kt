package com.artemchep.keyguard.common.model

object AttachmentPreviewLimits {
    const val MAX_ENTRY_PREVIEW_BYTES: Long = 8L * 1024L * 1024L
    const val MAX_ENTRY_EXPORT_BYTES: Long = 128L * 1024L * 1024L
    const val MAX_ARCHIVE_ENTRIES: Int = 10_000
    const val MAX_ENCRYPTED_BYTES: Long = 8L * 1024L * 1024L
}
