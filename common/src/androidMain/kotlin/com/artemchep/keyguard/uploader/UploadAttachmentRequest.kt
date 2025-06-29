package com.artemchep.keyguard.android.uploader

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class UploadAttachmentRequest(
    val requestId: String,
    /** A cipher we want to append this attachment to */
    val cipherId: String,
    val createdDate: Instant,
    val revisionDate: Instant,
    val attachment: Attachment,
) {
    @Serializable
    data class Attachment(
        val name: String,
        val ref: Ref,
    ) {
        @Serializable
        sealed interface Ref {
            @Serializable
            data class FromFile(
                val path: String,
            ) : Ref

            @Serializable
            data class FromAttachment(
                val cipherId: String,
                val attachmentId: String,
            ) : Ref
        }
    }
}
