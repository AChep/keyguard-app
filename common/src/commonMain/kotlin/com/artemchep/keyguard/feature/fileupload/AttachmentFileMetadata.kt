package com.artemchep.keyguard.feature.fileupload

import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.LeUri

internal data class AttachmentFileMetadata(
    val uri: LeUri,
    val uriString: String,
    val name: String,
    val rawName: String?,
    val size: Long?,
)

internal fun FilePickerResult.toAttachmentFileMetadata(
    fallbackName: String = "",
): AttachmentFileMetadata = AttachmentFileMetadata(
    uri = uri,
    uriString = uri.toString(),
    name = name ?: fallbackName,
    rawName = name,
    size = size,
)
