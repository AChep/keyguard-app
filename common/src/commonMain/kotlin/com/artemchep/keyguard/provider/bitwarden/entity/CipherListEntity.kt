package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class CipherListEntity(
    @JsonNames("Data")
    @SerialName("data")
    val data: List<CipherEntity>,
)
