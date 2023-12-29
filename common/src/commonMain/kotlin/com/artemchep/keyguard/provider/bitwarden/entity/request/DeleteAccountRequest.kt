package com.artemchep.keyguard.provider.bitwarden.entity.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeleteAccountRequest(
    @SerialName("masterPasswordHash")
    val masterPasswordHash: String,
    @SerialName("otp")
    val otp: String? = null,
)
