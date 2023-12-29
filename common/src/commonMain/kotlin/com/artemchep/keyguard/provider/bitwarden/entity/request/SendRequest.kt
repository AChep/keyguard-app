package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.provider.bitwarden.entity.SendTypeEntity
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendRequest(
    @SerialName("type")
    val type: SendTypeEntity,
    @SerialName("name")
    val name: String,
    @SerialName("notes")
    val notes: String?,
    @SerialName("password")
    val password: String? = null,
    @SerialName("disabled")
    val disabled: Boolean,
    /**
     * `true` to hide the email of the author of the
     * send, `false` otherwise.
     */
    @SerialName("hideEmail")
    val hideEmail: Boolean,
    @SerialName("deletionDate")
    val deletionDate: Instant,
    @SerialName("expirationDate")
    val expirationDate: Instant?,
    @SerialName("maxAccessCount")
    val maxAccessCount: Int?,
    @SerialName("text")
    val text: SendTextRequest?,
    @SerialName("file")
    val file: SendFileRequest?,
)

@Serializable
data class SendTextRequest(
    @SerialName("name")
    val name: String,
    @SerialName("notes")
    val notes: String,
    @SerialName("password")
    val password: String? = null,
    val type: SendTypeEntity,
)


@Serializable
data class SendFileRequest(
    @SerialName("name")
    val name: String,
    @SerialName("notes")
    val notes: String,
    @SerialName("password")
    val password: String? = null,
    val type: SendTypeEntity,
)
