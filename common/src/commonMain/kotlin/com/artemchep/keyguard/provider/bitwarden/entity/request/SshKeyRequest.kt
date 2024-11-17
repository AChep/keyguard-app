package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SshKeyRequest(
    @SerialName("privateKey")
    val privateKey: String?,
    @SerialName("publicKey")
    val publicKey: String?,
    @SerialName("keyFingerprint")
    val keyFingerprint: String?,
) {
    companion object
}

fun SshKeyRequest.Companion.of(
    model: BitwardenCipher.SshKey,
) = kotlin.run {
    SshKeyRequest(
        privateKey = model.privateKey,
        publicKey = model.publicKey,
        keyFingerprint = model.fingerprint,
    )
}
