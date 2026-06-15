package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SubscriptionResponseModel(
    @JsonNames("object")
    @SerialName("Object")
    val obj: String? = null,
    @JsonNames("storageName")
    @SerialName("StorageName")
    val storageName: String? = null,
    @JsonNames("storageGb")
    @SerialName("StorageGb")
    val storageGb: Double? = null,
    @JsonNames("maxStorageGb")
    @SerialName("MaxStorageGb")
    val maxStorageGb: Int? = null,
)
