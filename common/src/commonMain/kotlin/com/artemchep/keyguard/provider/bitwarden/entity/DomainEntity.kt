package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNames

@kotlinx.serialization.Serializable
data class DomainEntity(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("organizationId")
    @SerialName("OrganizationId")
    val organizationId: String,
    @JsonNames("name")
    @SerialName("Name")
    val name: String,
    @JsonNames("externalId")
    @SerialName("externalId")
    val externalId: String? = null,
    @JsonNames("readOnly")
    @SerialName("ReadOnly")
    val readOnly: Boolean,
    @JsonNames("hidePasswords")
    @SerialName("HidePasswords")
    val hidePasswords: Boolean,
)
