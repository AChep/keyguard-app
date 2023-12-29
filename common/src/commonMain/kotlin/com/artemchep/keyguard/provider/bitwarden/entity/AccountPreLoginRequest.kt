package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountPreLoginRequest(
    @SerialName("email")
    val email: String,
)
