package com.artemchep.keyguard.provider.bitwarden.entity

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

@Serializable
data class PolicyEntity(
    @JsonNames("Id")
    @SerialName("id")
    val id: String,
    @JsonNames("OrganizationId")
    @SerialName("organizationId")
    val organizationId: String,
    @JsonNames("Type")
    @SerialName("type")
    val type: PolicyTypeEntity,
    @JsonNames("Data")
    @SerialName("data")
    val data: JsonObject? = null,
    @JsonNames("Enabled")
    @SerialName("enabled")
    val enabled: Boolean,
    @JsonNames("RevisionDate")
    @SerialName("revisionDate")
    val revisionDate: Instant? = null,
)
