package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.Serializable

@Serializable
data class TwoFactorEmailRequestEntity(
    val email: String,
    val masterPasswordHash: String? = null,
    val deviceIdentifier: String,
    val ssoEmail2FaSessionToken: String? = null,
)
