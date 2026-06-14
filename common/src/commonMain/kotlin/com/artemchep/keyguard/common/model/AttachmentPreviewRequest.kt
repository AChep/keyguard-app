package com.artemchep.keyguard.common.model

data class AttachmentPreviewRequest(
    val localCipherId: String,
    val remoteCipherId: String?,
    val attachmentId: String,
    val fileName: String,
    val encryptedSize: Long? = null,
    val localUrl: String? = null,
)
