package com.artemchep.keyguard.provider.bitwarden.entity

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PolicyEntity(
    @SerialName("id")
    val id: String,
    @SerialName("organizationId")
    val organizationId: String,
    @SerialName("type")
    val type: PolicyTypeEntity,
    @SerialName("data")
    val data: JsonObject? = null,
    @SerialName("enabled")
    val enabled: Boolean,
    @SerialName("revisionDate")
    val revisionDate: Instant? = null,
)
