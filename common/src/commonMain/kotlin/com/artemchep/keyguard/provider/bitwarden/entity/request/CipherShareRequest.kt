package com.artemchep.keyguard.provider.bitwarden.entity.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherShareRequest(
    @SerialName("cipher")
    val cipher: CipherRequest,
    @SerialName("collectionIds")
    val collectionIds: List<String>,
)
