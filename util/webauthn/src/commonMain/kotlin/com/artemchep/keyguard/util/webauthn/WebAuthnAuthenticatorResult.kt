package com.artemchep.keyguard.util.webauthn

/** Binary registration response, independent of client-data JSON and provider APIs. */
data class WebAuthnCredentialCreationResult(
    val credential: WebAuthnCredential,
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val attestationObject: ByteArray,
    val publicKeyAlgorithm: Int,
    val publicKey: ByteArray,
)

/** Binary assertion response. The signature buffer belongs to the caller. */
data class WebAuthnAssertionResult(
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val userHandle: String?,
)
