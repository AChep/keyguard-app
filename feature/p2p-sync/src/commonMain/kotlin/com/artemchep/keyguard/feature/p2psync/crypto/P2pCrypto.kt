package com.artemchep.keyguard.feature.p2psync.crypto

/**
 * The cryptographic primitives the protocol needs. The module never implements them:
 * the application injects an implementation backed by its native crypto library.
 */
interface P2pCrypto {
    /** Returns the SHA-256 digest of [bytes]. */
    fun sha256(bytes: ByteArray): ByteArray

    /** Derives [length] bytes with HKDF-SHA256 (RFC 5869) from [ikm], [salt] and [info]. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /** Signs [data] with the Ed25519 private key [privateKey]. */
    fun ed25519Sign(privateKey: ByteArray, data: ByteArray): ByteArray

    /** Returns `true` if [signature] is a valid Ed25519 signature of [data] by [publicKey]. */
    fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean

    /** Returns [count] cryptographically secure random bytes. */
    fun randomBytes(count: Int): ByteArray
}
