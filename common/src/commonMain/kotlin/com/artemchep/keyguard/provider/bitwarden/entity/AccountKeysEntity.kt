package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountKeysEntity(
    @SerialName("signatureKeyPair")
    val signatureKeyPair: SignatureKeyPairEntity? = null,
    @SerialName("publicKeyEncryptionKeyPair")
    val publicKeyEncryptionKeyPair: PublicKeyEncryptionKeyPairEntity? = null,
    @SerialName("securityState")
    val securityState: SecurityStateEntity? = null,
) {
    @Serializable
    data class SignatureKeyPairEntity(
        @SerialName("wrappedSigningKey")
        val wrappedSigningKey: String,
        @SerialName("verifyingKey")
        val verifyingKey: String,
    )

    @Serializable
    data class PublicKeyEncryptionKeyPairEntity(
        @SerialName("wrappedPrivateKey")
        val wrappedPrivateKey: String,
        @SerialName("publicKey")
        val publicKey: String,
        @SerialName("signedPublicKey")
        val signedPublicKey: String? = null,
    )

    @Serializable
    data class SecurityStateEntity(
        @SerialName("securityState")
        val securityState: String,
        @SerialName("securityVersion")
        val securityVersion: Int,
    )
}
