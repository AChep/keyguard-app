package com.artemchep.keyguard.provider.bitwarden.entity

data class PolicyEntity(
    val id: String,
    val organizationId: String,
    val type: PolicyTypeEntity,
    val data: Any,
    val enabled: Boolean,
)
