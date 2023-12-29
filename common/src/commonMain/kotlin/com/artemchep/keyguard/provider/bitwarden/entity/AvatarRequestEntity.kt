package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.Serializable

@Serializable
data class AvatarRequestEntity(
    /** Color in the '#FFFFFF' format */
    val avatarColor: String,
)
