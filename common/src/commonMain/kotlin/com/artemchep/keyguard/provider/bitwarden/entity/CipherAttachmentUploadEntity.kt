package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadTarget
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class CipherAttachmentUploadEntity(
    @JsonNames("attachmentId")
    @SerialName("AttachmentId")
    val attachmentId: String? = null,
    @JsonNames("fileUploadType")
    @SerialName("FileUploadType")
    val fileUploadTypeCode: Int,
    @JsonNames("object")
    @SerialName("Object")
    val objectType: String,
    @JsonNames("url")
    @SerialName("Url")
    val url: String,
    @JsonNames("cipherResponse")
    @SerialName("CipherResponse")
    val cipherResponse: CipherEntity? = null,
    @JsonNames("cipherMiniResponse")
    @SerialName("CipherMiniResponse")
    val cipherMiniResponse: CipherEntity? = null,
) {
    val uploadTarget: SendFileUploadTarget
        get() = SendFileUploadTarget(
            type = SendFileUploadType.of(fileUploadTypeCode),
            url = url,
        )

    val requiredAttachmentId: String
        get() = requireNotNull(attachmentId) {
            "Cipher attachment upload create response must contain an attachment id."
        }

    val anyCipherResponse: CipherEntity
        get() = cipherResponse
            ?: requireNotNull(cipherMiniResponse) {
                "Cipher attachment upload response must contain a cipher payload."
            }
}
