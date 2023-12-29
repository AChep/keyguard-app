package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class AttachmentEntity(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("url")
    @SerialName("Url")
    val url: String? = null,
    @JsonNames("fileName")
    @SerialName("FileName")
    val fileName: String,
    @JsonNames("key")
    @SerialName("Key")
    val key: String,
    @JsonNames("size")
    @SerialName("Size")
    val size: String,
    @JsonNames("sizeName")
    @SerialName("SizeName")
    val sizeName: String,
)
