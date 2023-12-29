package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderUserStatusTypeEntity {
    @SerialName("0")
    Invited,

    @SerialName("1")
    Accepted,

    @SerialName("2")
    Confirmed,
}
