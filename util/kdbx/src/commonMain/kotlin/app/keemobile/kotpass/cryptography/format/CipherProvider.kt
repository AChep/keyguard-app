package app.keemobile.kotpass.cryptography.format

import kotlin.uuid.Uuid

interface CipherProvider {
    /**
     * Each cipher used for database encryption has unique ID.
     */
    val uuid: Uuid

    /**
     * The IV length depends on cryptographic primitive used by implementation.
     */
    val ivLength: UInt

    /**
     * Encrypt the [data] using provided [key] and [iv].
     */
    fun encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray
    ): ByteArray

    /**
     * Decrypt the [data] using provided [key] and [iv].
     */
    fun decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray
    ): ByteArray
}
