package com.artemchep.keyguard.provider.bitwarden.entity

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class LoginFido2CredentialsEntity(
    @JsonNames("credentialId")
    @SerialName("CredentialId")
    val credentialId: String? = null,
    @JsonNames("keyType")
    @SerialName("KeyType")
    val keyType: String,
    @JsonNames("keyAlgorithm")
    @SerialName("KeyAlgorithm")
    val keyAlgorithm: String,
    @JsonNames("keyCurve")
    @SerialName("KeyCurve")
    val keyCurve: String,
    @JsonNames("keyValue")
    @SerialName("KeyValue")
    val keyValue: String,
    @JsonNames("rpId")
    @SerialName("RpId")
    val rpId: String,
    @JsonNames("rpName")
    @SerialName("RpName")
    val rpName: String?,
    @JsonNames("counter")
    @SerialName("Counter")
    val counter: String,
    @JsonNames("userHandle")
    @SerialName("UserHandle")
    val userHandle: String,
    @JsonNames("userName")
    @SerialName("UserName")
    val userName: String? = null,
    @JsonNames("userDisplayName")
    @SerialName("UserDisplayName")
    val userDisplayName: String? = null,
    @JsonNames("discoverable")
    @SerialName("Discoverable")
    val discoverable: String,
    @JsonNames("creationDate")
    @SerialName("CreationDate")
    val creationDate: Instant,
)
