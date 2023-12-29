package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class PasswordHistoryEntity(
    @JsonNames("password")
    @SerialName("Password")
    val password: String,
    @JsonNames("lastUsedDate")
    @SerialName("LastUsedDate")
    val lastUsedDate: Instant,
)
