package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.Serializable

@Serializable
data class CipherListEntity(
    val data: List<CipherEntity>,
)
