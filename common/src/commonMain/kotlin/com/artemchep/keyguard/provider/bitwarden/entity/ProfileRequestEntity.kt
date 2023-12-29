package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.Serializable

@Serializable
data class ProfileRequestEntity(
    val culture: String?,
    val name: String?,
    val masterPasswordHint: String?,
)
