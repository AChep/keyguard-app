package com.artemchep.keyguard.feature.attachments.model

import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.usecase.PrepareAttachmentContent
import com.artemchep.keyguard.crypto.FileEncryptionFormat
import com.artemchep.keyguard.feature.dragout.model.DragItem
import com.artemchep.keyguard.feature.dragout.model.DragOutCapabilities
import com.artemchep.keyguard.feature.dragout.model.DragPayload
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import com.artemchep.keyguard.feature.dragout.model.dragOutCapabilities

/** Prepares one attachment as a file payload within the platform's drag [capabilities]. */
class AttachmentDragPreparer(
    private val prepareAttachmentContent: PrepareAttachmentContent,
    private val capabilities: DragOutCapabilities = dragOutCapabilities,
) {
    /** Server metadata may include the authenticated frame and a full padding block. */
    fun canPrepare(size: Long): Boolean = capabilities.files && size >= 0 &&
        capabilities.fileMaximumBytes.let { limit -> limit == null || size <= limit + ENCRYPTED_FRAME_BYTES }

    suspend operator fun invoke(
        request: PrepareAttachmentContent.Request,
        onProgress: (DownloadProgress.Loading) -> Unit,
    ): DragPayload {
        check(capabilities.files) { "This platform cannot drag files" }
        val limit = capabilities.fileMaximumBytes
        val bounded = if (limit != null) {
            request.copy(maximumBytes = minOf(request.maximumBytes ?: limit, limit))
        } else request
        val content = prepareAttachmentContent(bounded, onProgress)
        return DragPayload(content.name, listOf(DragItem(DragRepresentation.File(content))))
    }
}

private const val ENCRYPTED_FRAME_BYTES = FileEncryptionFormat.HEADER_LENGTH + FileEncryptionFormat.IV_LENGTH
