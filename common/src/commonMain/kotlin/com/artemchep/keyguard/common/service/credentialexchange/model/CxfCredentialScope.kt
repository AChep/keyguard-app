package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.Serializable

/**
 * Describes where a set of [credentials][CxfCredential] applies: web origins
 * and/or native Android applications.
 */
@Serializable
data class CxfCredentialScope(
    val urls: List<String>,
    val androidApps: List<CxfAndroidAppId>,
)

@Serializable
data class CxfAndroidAppId(
    val bundleId: String,
    /**
     * A fingerprint of the certificate that signs the application. The CXF
     * v1.0 specification models this as a single object.
     */
    val certificate: CxfAndroidAppCertificateFingerprint? = null,
    val name: String? = null,
)

/**
 * A hash of an Android application's signing certificate.
 */
@Serializable
data class CxfAndroidAppCertificateFingerprint(
    /**
     * The raw bytes of the certificate hash,
     * base64url-encoded.
     */
    val fingerprint: String,
    /**
     * The hash algorithm, one of [HASH_ALG_SHA256] or [HASH_ALG_SHA512].
     */
    val hashAlg: String,
) {
    companion object {
        const val HASH_ALG_SHA256 = "sha256"
        const val HASH_ALG_SHA512 = "sha512"
    }
}
