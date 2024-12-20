package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class DomainsEntity(
    @JsonNames("GlobalEquivalentDomains")
    @SerialName("globalEquivalentDomains")
    val globalEquivalentDomains: List<GlobalEquivalentDomainEntity>? = null,
    @JsonNames("EquivalentDomains")
    @SerialName("equivalentDomains")
    val equivalentDomains: List<List<String>>? = null,
)
