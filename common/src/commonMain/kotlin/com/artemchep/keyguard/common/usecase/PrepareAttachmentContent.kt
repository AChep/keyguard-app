package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.dragout.PreparedContent

/** Prepares fully validated bytes without publishing a plaintext file or a platform URI. */
fun interface PrepareAttachmentContent {
    suspend operator fun invoke(
        request: Request,
        onProgress: (DownloadProgress.Loading) -> Unit,
    ): PreparedContent

    data class Request(
        val attachment: DownloadAttachmentRequest.ByLocalCipherAttachment,
        val name: String,
        val localUri: String? = null,
        val maximumBytes: Long? = null,
    ) {
        init {
            require(maximumBytes == null || maximumBytes > 0) { "Attachment byte limit must be positive" }
        }
    }
}
