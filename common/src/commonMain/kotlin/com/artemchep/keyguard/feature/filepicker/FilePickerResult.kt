package com.artemchep.keyguard.feature.filepicker

import com.artemchep.keyguard.platform.LeUri

data class FilePickerResult(
    val uri: LeUri,
    val name: String?,
    val size: Long?,
    /**
     * Platform-specific access material for using the selected file after the
     * picker flow completes. Currently used on iOS to carry a security-scoped
     * bookmark for persisted file URIs.
     */
    val accessToken: String? = null,
    /** True only when this picker request created the document, so an abandoned write may delete it. */
    val created: Boolean = false,
)
