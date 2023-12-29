package com.artemchep.keyguard.provider.bitwarden.entity

data class ProviderEntity(
    val id: String,
    val name: String,
    val status: ProviderUserStatusTypeEntity,
    val type: ProviderUserTypeEntity,
    val enabled: Boolean,
    val userId: String,
    val useEvents: Boolean,
)
