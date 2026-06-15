package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlin.time.Instant

@Serializable
data class SendEntity(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("accessId")
    @SerialName("AccessId")
    val accessId: String,
    @JsonNames("key")
    @SerialName("Key")
    val key: String? = null,
    @JsonNames("type")
    @SerialName("Type")
    val type: SendTypeEntity,
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("notes")
    @SerialName("Notes")
    val notes: String? = null,
    @JsonNames("file")
    @SerialName("File")
    val file: SendFileEntity? = null,
    @JsonNames("text")
    @SerialName("Text")
    val text: SendTextEntity? = null,
    @JsonNames("accessCount")
    @SerialName("AccessCount")
    val accessCount: Int,
    @JsonNames("maxAccessCount")
    @SerialName("MaxAccessCount")
    val maxAccessCount: Int? = null,
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: Instant,
    @JsonNames("creationDate")
    @SerialName("CreationDate")
    val creationDate: Instant? = null,
    @JsonNames("expirationDate")
    @SerialName("ExpirationDate")
    val expirationDate: Instant? = null,
    @JsonNames("deletionDate")
    @SerialName("DeletionDate")
    val deletionDate: Instant? = null,
    @JsonNames("password")
    @SerialName("Password")
    val password: String? = null,
    @JsonNames("disabled")
    @SerialName("Disabled")
    val disabled: Boolean,
    @JsonNames("hideEmail")
    @SerialName("HideEmail")
    val hideEmail: Boolean? = null,
    @JsonNames("authType")
    @SerialName("AuthType")
    val authType: SendAuthTypeEntity? = null,
    @JsonNames("emails")
    @SerialName("Emails")
    val emails: String? = null,
)
