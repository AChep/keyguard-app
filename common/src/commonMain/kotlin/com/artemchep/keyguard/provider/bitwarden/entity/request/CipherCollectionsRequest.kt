package com.artemchep.keyguard.provider.bitwarden.entity.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherCollectionsRequest(
    @SerialName("collectionIds")
    val collectionIds: List<String>,
)
