package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildApiUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

enum class SendFileUploadType(
    val code: Int,
) {
    Direct(0),
    Azure(1),
    ;

    companion object {
        fun of(code: Int): SendFileUploadType = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown send file upload type: $code")
    }
}

data class SendFileUploadTarget(
    val type: SendFileUploadType,
    val url: String,
) {
    fun resolveUrl(
        env: ServerEnv,
    ): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> env.buildApiUrl()
            .removeSuffix("/")
            .plus("/")
            .plus(url.removePrefix("/"))
    }
}

@Serializable
data class SendFileUploadEntity(
    @JsonNames("fileUploadType")
    @SerialName("FileUploadType")
    val fileUploadTypeCode: Int,
    @JsonNames("object")
    @SerialName("Object")
    val objectType: String,
    @JsonNames("url")
    @SerialName("Url")
    val url: String,
    @JsonNames("sendResponse")
    @SerialName("SendResponse")
    val sendResponse: SendEntity? = null,
) {
    val uploadTarget: SendFileUploadTarget
        get() = SendFileUploadTarget(
            type = SendFileUploadType.of(fileUploadTypeCode),
            url = url,
        )

    val requiredSendResponse: SendEntity
        get() = requireNotNull(sendResponse) {
            "Bitwarden send file upload response must contain a send payload."
        }
}
