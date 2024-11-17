package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SshKeyEntity(
    @JsonNames("privateKey")
    @SerialName("PrivateKey")
    val privateKey: String? = null,
    @JsonNames("publicKey")
    @SerialName("PublicKey")
    val publicKey: String? = null,
    @JsonNames("keyFingerprint")
    @SerialName("KeyFingerprint")
    val keyFingerprint: String? = null,
)
