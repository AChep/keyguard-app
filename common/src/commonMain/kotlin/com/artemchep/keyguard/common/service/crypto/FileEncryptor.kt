package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.platform.LocalPath
import kotlinx.io.Source

interface FileEncryptor {
    data class EncodeResult(
        val plainSize: Long,
        val encryptedSize: Long,
    )

    /**
     * Decrypts an authenticated file encryption frame held in memory.
     *
     * The [key] must match the frame type. Implementations verify the MAC
     * before returning plaintext and throw if the frame, key, or authentication
     * check is invalid.
     */
    fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray

    /**
     * Streams an authenticated file encryption frame from [input] into
     * plaintext at [output].
     *
     * The output should be treated as temporary until this call completes
     * successfully. Platforms without streaming decryption support throw
     * [UnsupportedOperationException].
     */
    fun decode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ) {
        throw UnsupportedOperationException(
            "Streaming file decryption is not supported on this platform.",
        )
    }

    /**
     * Encrypts [data] into the current authenticated file encryption frame.
     *
     * Encoders use the active file format and require a [key] valid for that
     * format.
     */
    fun encode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray

    /**
     * Streams plaintext from [input] into an authenticated file encryption
     * frame at [output].
     *
     * Returns the plaintext and encrypted byte counts written by the operation.
     * Platforms without streaming encryption support throw
     * [UnsupportedOperationException].
     */
    fun encode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ): EncodeResult = throw UnsupportedOperationException(
        "Streaming file encryption is not supported on this platform.",
    )
}
