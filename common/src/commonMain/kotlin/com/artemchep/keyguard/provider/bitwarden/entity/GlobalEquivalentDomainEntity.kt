package com.artemchep.keyguard.provider.bitwarden.entity;

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GlobalEquivalentDomainEntity(
    @SerialName("excluded")
    val excluded: Boolean,
    @SerialName("domains")
    val domains: List<String>?,
    @SerialName("type")
    val type: Int,
)
