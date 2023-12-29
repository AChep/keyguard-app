package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class FieldEntity(
    @JsonNames("type")
    @SerialName("Type")
    val type: FieldTypeEntity,
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("value")
    @SerialName("Value")
    val value: String? = null,
    @JsonNames("linkedId")
    @SerialName("LinkedId")
    val linkedId: LinkedIdTypeEntity? = null,
)
