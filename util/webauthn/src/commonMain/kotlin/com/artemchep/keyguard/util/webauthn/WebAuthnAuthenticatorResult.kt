package com.artemchep.keyguard.util.webauthn

/** Binary registration response, independent of client-data JSON and provider APIs. */
data class WebAuthnCredentialCreationResult(
    val credential: WebAuthnCredential,
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val attestationObject: ByteArray,
    val publicKeyAlgorithm: Int,
    val publicKey: ByteArray,
    /** Whether the credential supports the PRF extension, only if it was requested. */
    val prfEnabled: Boolean = false,
    /** The PRF outputs, owned by the caller. */
    val prfResults: WebAuthnPrfValues? = null,
) {
    fun clear() {
        prfResults?.clear()
    }
}

/**
 * Binary assertion response. The signature buffer belongs to the caller. Without a
 * readable stored [userHandle], it is `null` and must be omitted.
 */
data class WebAuthnAssertionResult(
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val userHandle: ByteArray?,
    /** The PRF outputs, owned by the caller. */
    val prfResults: WebAuthnPrfValues? = null,
    /**
     * The account names stored with the credential, empty if it has none. Set only after
     * user verification: CTAP returns them with the user handle only for a verified request.
     */
    val userName: String? = null,
    val userDisplayName: String? = null,
) {
    fun clear() {
        signature.fill(0)
        prfResults?.clear()
    }
}
