package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class LoginUriEntity(
    @JsonNames("uri")
    @SerialName("Uri")
    val uri: String? = null,
    @JsonNames("match")
    @SerialName("Match")
    val match: UriMatchTypeEntity? = null,
)
