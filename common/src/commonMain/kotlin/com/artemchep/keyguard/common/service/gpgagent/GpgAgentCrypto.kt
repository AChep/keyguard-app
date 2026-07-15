package com.artemchep.keyguard.common.service.gpgagent

/**
 * Performs the raw private-key operations a gpg-agent must provide for the
 * PKSIGN and PKDECRYPT Assuan commands. The production implementation is shared
 * across platforms and delegates these operations to the native Rust module.
 *
 * Both entry points are pure and stateless: they take an ASCII-armored,
 * passphrase-less OpenPGP secret key, select the matching (sub)key via the
 * supplied [GpgAgentKeyMetadataKey], and return the response S-expression in
 * the exact shape gpg expects.
 *
 * The work split between the agent and gpg mirrors the real gpg-agent, verified
 * by the integration GPG end-to-end test:
 *  - RSA (sign): the agent returns the PKCS#1-padded signature bytes.
 *  - RSA (decrypt): the agent returns the bare modular-exponentiation result
 *    (m = c^d mod n); gpg strips the PKCS#1 padding itself.
 *  - ECDH (decrypt): newer `PKDECRYPT --kem=PGP` clients expect the agent to
 *    derive the shared secret, derive the KEK, and AES-unwrap the session key.
 *    Legacy plain `PKDECRYPT` clients expect the shared-secret value and perform
 *    the KDF + unwrap themselves.
 *  - ECDSA / EdDSA (sign): the agent signs the supplied digest directly.
 */
interface GpgAgentCrypto {
    /**
     * Signs [hash] (already digested with [hashAlgorithm]) with the private
     * (sub)key of [privateKeyArmored] selected by [metadataKey], returning the
     * `(sig-val ...)` S-expression response.
     *
     * @throws GpgAgentKeyNotFoundException if no usable signing key matches.
     * @throws GpgAgentUnsupportedAlgorithmException if the key's algorithm is
     *  not supported.
     */
    fun signHash(
        privateKeyArmored: String,
        metadataKey: GpgAgentKeyMetadataKey,
        hashAlgorithm: String,
        hash: ByteArray,
    ): GpgAgentMessages.SignHashResponse

    /**
     * Decrypts the `(enc-val ...)` [ciphertext] with the private (sub)key of
     * [privateKeyArmored] selected by [metadataKey], returning the `(value ...)`
     * S-expression response.
     *
     * @throws GpgAgentKeyNotFoundException if no usable encryption key matches.
     * @throws GpgAgentUnsupportedAlgorithmException if the enc-val algorithm is
     *  not supported.
     */
    fun pkdecrypt(
        privateKeyArmored: String,
        metadataKey: GpgAgentKeyMetadataKey,
        ciphertext: ByteArray,
        unwrapEcdh: Boolean,
    ): GpgAgentMessages.PkdecryptResponse
}

class GpgAgentKeyNotFoundException : Exception()

class GpgAgentUnsupportedAlgorithmException(
    message: String,
) : Exception(message)
