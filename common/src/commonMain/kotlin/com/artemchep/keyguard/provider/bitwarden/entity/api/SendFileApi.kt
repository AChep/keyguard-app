package com.artemchep.keyguard.provider.bitwarden.entity.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendFileApi(
    @SerialName("id")
    val id: String,
    @SerialName("fileName")
    val fileName: String,
    @SerialName("key")
    val key: String,
    @SerialName("size")
    val size: String,
    @SerialName("sizeName")
    val sizeName: String,
)
