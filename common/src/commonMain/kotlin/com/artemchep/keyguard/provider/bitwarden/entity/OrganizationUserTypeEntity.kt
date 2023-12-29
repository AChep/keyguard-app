package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OrganizationUserTypeEntity {
    @SerialName("0")
    Owner,

    @SerialName("1")
    Admin,

    @SerialName("2")
    User,

    @SerialName("3")
    Manager,

    @SerialName("4")
    Custom,
}
