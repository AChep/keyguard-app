package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The extension data of a [passkey][CxfCredential.Passkey], CXF v1.0 §3.3.12.2.
 * Only the PRF seeds are modeled; `credBlob`, `largeBlob` and `payments` are
 * not, so the import ignores them.
 */
@Serializable
data class CxfFido2Extensions(
    val hmacCredentials: CxfFido2HmacCredentials? = null,
)

/**
 * The seeds of the WebAuthn PRF and CTAP2 hmac-secret extensions, CXF v1.0
 * §3.3.12.3, base64url-encoded. All three members are required; they are
 * optional here so that an incomplete entry costs the import only the
 * extension, not the passkey.
 */
@Serializable
data class CxfFido2HmacCredentials(
    /**
     * The HMAC algorithm, normally [ALGORITHM_HMAC_SHA256].
     */
    val algorithm: String? = null,
    /**
     * The seed used when user verification was performed.
     */
    @SerialName("credWithUV")
    val credWithUv: String? = null,
    /**
     * The seed used when user verification was not performed.
     */
    @SerialName("credWithoutUV")
    val credWithoutUv: String? = null,
) {
    companion object {
        const val ALGORITHM_HMAC_SHA256 = "hmac-sha256"
    }
}
