package com.artemchep.keyguard.util.webauthn

/** Parsed assertion options. The provider adapter decodes the challenge from its request. */
data class WebAuthnAssertionRequest(
    val challenge: ByteArray,
    val userVerification: String?,
    val allowedCredentials: WebAuthnAllowedCredentialDescriptors,
)

/** Inputs for a provider that already has the client's SHA-256 client-data hash. */
data class WebAuthnAssertionHashRequest(
    val rpId: String,
    val clientDataHash: ByteArray,
    val userVerification: String?,
    val allowedCredentials: WebAuthnAllowedCredentialDescriptors,
)
