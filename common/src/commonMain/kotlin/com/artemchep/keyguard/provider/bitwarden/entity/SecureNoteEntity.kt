package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SecureNoteEntity(
    @JsonNames("type")
    @SerialName("Type")
    val type: SecureNoteTypeEntity = SecureNoteTypeEntity.Generic,
)
