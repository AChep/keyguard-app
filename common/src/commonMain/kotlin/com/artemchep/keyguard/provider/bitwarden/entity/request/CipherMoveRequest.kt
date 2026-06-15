package com.artemchep.keyguard.provider.bitwarden.entity.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherMoveRequest(
    @SerialName("ids")
    val ids: List<String>,
    @SerialName("folderId")
    val folderId: String?,
)
