package com.artemchep.keyguard.provider.bitwarden.entity.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendTextApi(
    @SerialName("text")
    val text: String,
    @SerialName("hidden")
    val hidden: Boolean,
)
