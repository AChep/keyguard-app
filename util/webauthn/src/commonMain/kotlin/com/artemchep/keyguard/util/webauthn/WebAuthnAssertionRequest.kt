package com.artemchep.keyguard.util.webauthn

/** Parsed assertion options. The provider adapter decodes the challenge from its request. */
data class WebAuthnAssertionRequest(
    val challenge: ByteArray,
    val userVerification: String?,
    val allowedCredentials: WebAuthnAllowedCredentialDescriptors,
    /** Non-null if the relying party requested the PRF extension. */
    val prf: WebAuthnPrfAssertionInputs? = null,
)

/** Inputs for a provider that already has the client's SHA-256 client-data hash. */
data class WebAuthnAssertionHashRequest(
    val rpId: String,
    val clientDataHash: ByteArray,
    val userVerification: String?,
    val allowedCredentials: WebAuthnAllowedCredentialDescriptors,
    /** The PRF inputs for the asserted credential, if the relying party requested any. */
    val prf: WebAuthnPrfValues? = null,
    /** Whether [prf] holds the salts that the platform already derived from the inputs. */
    val prfSalted: Boolean = false,
)
