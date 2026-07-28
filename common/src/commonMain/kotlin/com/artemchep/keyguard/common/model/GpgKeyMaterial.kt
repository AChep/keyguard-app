package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Portable cryptographic material shared by import, renewal, and persistence. */
@Serializable
data class GpgKeyMaterial(
    @SerialName("privateKeyArmored")
    val privateKeyArmored: String,
    @SerialName("publicKeyArmored")
    val publicKeyArmored: String,
    @SerialName("fingerprint")
    val fingerprint: String,
    @SerialName("metadata")
    val metadata: GpgAgentKeyMetadata,
)

fun GeneratedGpgKey.toGpgKeyMaterial(): GpgKeyMaterial = GpgKeyMaterial(
    privateKeyArmored = privateKeyArmored,
    publicKeyArmored = publicKeyArmored,
    fingerprint = fingerprint,
    metadata = metadata,
)

fun GeneratedGpgKey.withGpgKeyMaterial(
    material: GpgKeyMaterial,
): GeneratedGpgKey = copy(
    privateKeyArmored = material.privateKeyArmored,
    publicKeyArmored = material.publicKeyArmored,
    fingerprint = material.fingerprint,
    metadata = material.metadata,
)
