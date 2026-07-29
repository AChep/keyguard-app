package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.KeyPair

data class SshKeyPkcs8Export(
    val type: KeyPair.Type,
    val der: ByteArray,
)

/**
 * Validates a stored SSH key pair and converts its private key into the PKCS#8
 * ASN.1 DER representation required by the FIDO Credential Exchange Format.
 */
interface SshKeyPkcs8Exporter {
    /**
     * Returns the validated algorithm and unencrypted PKCS#8 DER, or `null`
     * when the pair cannot be converted (e.g. mismatched, encrypted, an
     * unsupported format, or the native crypto backend is unavailable).
     * Implementations signal failure with `null`; the caller counts it as one
     * skipped credential.
     */
    fun exportPkcs8(
        privateKeyPem: String,
        publicKeyOpenSsh: String,
    ): SshKeyPkcs8Export?
}
