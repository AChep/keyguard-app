package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DomainsEntity(
    @SerialName("globalEquivalentDomains")
    val globalEquivalentDomains: List<GlobalEquivalentDomainEntity>?,
    @SerialName("equivalentDomains")
    val equivalentDomains: List<List<String>>?,
)
