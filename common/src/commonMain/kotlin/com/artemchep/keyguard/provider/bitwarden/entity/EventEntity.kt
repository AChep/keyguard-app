package com.artemchep.keyguard.provider.bitwarden.entity

data class EventEntity(
    val type: EventTypeEntity,
    val cipherId: String,
    val date: String,
)
