package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderUserTypeEntity {
    @SerialName("0")
    ProviderAdmin,

    @SerialName("1")
    ServiceUser,
}
