package com.artemchep.keyguard.common.service.crypto

import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Encodes and decodes authenticated file-encryption frames.
 *
 * Streaming operations borrow both endpoints: they never close [Source] or
 * [Sink], and never flush the sink. On success the source is consumed through
 * end-of-stream. After failure the source position is unspecified and the sink
 * may contain a partial frame or plaintext.
 */
interface FileEncryptionCodec {
    data class EncryptionResult(
        val plainSize: Long,
        val encryptedSize: Long,
    )

    /**
     * Decrypts an authenticated file-encryption frame held in memory.
     *
     * The [key] must match the frame type. Implementations verify the MAC
     * before returning plaintext and throw if the frame, key, or authentication
     * check is invalid.
     */
    fun decrypt(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray

    /**
     * Reads an authenticated file-encryption frame from [input] and writes its
     * plaintext to [output].
     *
     * Callers that must not expose plaintext until every validation step
     * succeeds should supply a private staging sink and publish it only after
     * this call returns successfully.
     */
    fun decrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    )

    /**
     * Encrypts [data] into the current authenticated file-encryption frame.
     *
     * Encoders use the active file format and require a [key] valid for that
     * format.
     */
    fun encrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray

    /**
     * Reads plaintext from [input] and writes an authenticated file-encryption
     * frame to [output].
     *
     * Returns the plaintext and encrypted byte counts produced by the operation.
     * The output remains untouched until plaintext encryption has completed,
     * but an output failure may leave a partial frame.
     */
    fun encrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ): EncryptionResult
}
