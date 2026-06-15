package com.artemchep.keyguard.provider.bitwarden.entity.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherBulkUpdateCollectionsRequest(
    @SerialName("organizationId")
    val organizationId: String,
    @SerialName("cipherIds")
    val cipherIds: List<String>,
    @SerialName("collectionIds")
    val collectionIds: List<String>,
    @SerialName("removeCollections")
    val removeCollections: Boolean,
)
