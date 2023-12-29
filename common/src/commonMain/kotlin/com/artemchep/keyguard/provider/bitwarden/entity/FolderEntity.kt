package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class FolderEntity(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("name")
    @SerialName("Name")
    val name: String,
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: Instant,
)
