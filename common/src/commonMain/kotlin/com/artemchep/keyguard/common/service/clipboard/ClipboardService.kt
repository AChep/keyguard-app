package com.artemchep.keyguard.common.service.clipboard

interface ClipboardService {
    fun setPrimaryClip(
        value: String,
        concealed: Boolean,
    )

    fun clearPrimaryClip()

    fun hasCopyNotification(): Boolean
}
