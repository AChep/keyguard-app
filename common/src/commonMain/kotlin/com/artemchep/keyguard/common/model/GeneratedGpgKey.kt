package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeneratedGpgKey(
    @SerialName("privateKeyArmored")
    val privateKeyArmored: String,
    @SerialName("publicKeyArmored")
    val publicKeyArmored: String,
    @SerialName("fingerprint")
    val fingerprint: String,
    @SerialName("metadata")
    val metadata: GpgAgentKeyMetadata,
    @SerialName("userId")
    val userId: String,
    @SerialName("typeLabel")
    val typeLabel: String,
)
