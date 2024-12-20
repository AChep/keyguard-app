package com.artemchep.keyguard.provider.bitwarden.entity;

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class GlobalEquivalentDomainEntity(
    @JsonNames("Excluded")
    @SerialName("excluded")
    val excluded: Boolean = false,
    @JsonNames("Domains")
    @SerialName("domains")
    val domains: List<String>? = null,
    @JsonNames("Type")
    @SerialName("type")
    val type: Int,
)
