package com.artemchep.keyguard.provider.bitwarden.entity.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherDeleteRequest(
    @SerialName("ids")
    val ids: List<String>,
)
