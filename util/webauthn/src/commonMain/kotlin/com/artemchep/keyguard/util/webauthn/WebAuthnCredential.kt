package com.artemchep.keyguard.util.webauthn

/** A credential source, independent of vault storage and provider APIs. */
data class WebAuthnCredential(
    val credentialId: String,
    val keyType: String,
    val keyAlgorithm: String,
    val keyCurve: String,
    val keyValue: String,
    val rpId: String,
    val discoverable: Boolean,
    val rpName: String? = null,
    val counter: Int? = null,
    val userHandle: String? = null,
    val userName: String? = null,
    val userDisplayName: String? = null,
) {
    override fun toString(): String = "WebAuthnCredential(credentialId=$credentialId, rpId=$rpId, keyValue=<redacted>)"
}

/**
 * Caller information established by the provider adapter. [origin] must come
 * from a trusted caller, and [rpId] must be resolved and validated against it
 * before using this context to create or assert a credential.
 */
data class WebAuthnCallerContext(
    val origin: String,
    val rpId: String,
    val androidPackageName: String? = null,
)

data class WebAuthnRegistrationResult(
    val responseJson: String,
    val credential: WebAuthnCredential,
)
