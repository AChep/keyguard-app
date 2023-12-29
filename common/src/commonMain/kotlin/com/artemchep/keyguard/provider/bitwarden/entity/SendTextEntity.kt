package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SendTextEntity(
    @JsonNames("text")
    @SerialName("Text")
    val text: String,
    @JsonNames("hidden")
    @SerialName("Hidden")
    val hidden: Boolean? = null,
)
