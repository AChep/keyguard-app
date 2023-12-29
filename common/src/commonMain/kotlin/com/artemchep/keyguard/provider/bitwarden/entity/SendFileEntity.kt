package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SendFileEntity(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("fileName")
    @SerialName("FileName")
    val fileName: String,
    @JsonNames("key")
    @SerialName("Key")
    val key: String? = null,
    @JsonNames("size")
    @SerialName("Size")
    val size: String,
    @JsonNames("sizeName")
    @SerialName("SizeName")
    val sizeName: String,
)
