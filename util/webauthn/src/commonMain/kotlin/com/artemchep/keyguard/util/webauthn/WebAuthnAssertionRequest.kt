package com.artemchep.keyguard.util.webauthn

/** Parsed assertion options. The provider adapter decodes the challenge from its request. */
data class WebAuthnAssertionRequest(
    val challenge: ByteArray,
    val userVerification: String?,
    val allowedCredentials: WebAuthnAllowedCredentialDescriptors,
)
