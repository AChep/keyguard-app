package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttachmentRequest(
    @SerialName("fileName")
    val fileName: String,
    @SerialName("key")
    val key: String,
//    @SerialName("fileSize")
//    val fileSize: Long,
)

fun AttachmentRequest.Companion.of(
    model: BitwardenCipher.Attachment.Remote,
) = kotlin.run {
    AttachmentRequest(
        fileName = model.fileName,
        key = model.keyBase64,
//        fileSize = model.size,
    )
}
