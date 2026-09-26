package com.artemchep.keyguard.util.webauthn.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// https://www.w3.org/TR/webauthn-2/#dictionary-assertion-options
@Serializable
data class GetPasskey(
    val allowCredentials: List<PublicKeyCredentialDescriptor> = emptyList(),
    @SerialName("challenge")
    val challengeBase64: String,
    val rpId: String? = null,
    val userVerification: UserVerification? = UserVerification.PREFERRED,
    // https://www.w3.org/TR/webauthn-2/#enum-attestation-convey
    val attestation: String? = "none",
) {
    // https://www.w3.org/TR/webauthn-2/#enum-userVerificationRequirement
    enum class UserVerification {
        @SerialName("required")
        REQUIRED,

        @SerialName("preferred")
        PREFERRED,

        @SerialName("discouraged")
        DISCOURAGED,
    }

    // https://www.w3.org/TR/webauthn-2/#dictionary-credential-descriptor
    @Serializable
    data class PublicKeyCredentialDescriptor(
        val type: String,
        @SerialName("id")
        val idBase64: String,
        // https://www.w3.org/TR/webauthn-2/#enum-transport
        val transports: List<String> = emptyList(),
    )
}
