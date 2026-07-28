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

    fun createEncryptor(
        key: ByteArray,
        iv: ByteArray,
    ): CipherSession

    fun createDecryptor(
        key: ByteArray,
        iv: ByteArray,
    ): CipherSession
}
