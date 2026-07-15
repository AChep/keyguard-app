package com.artemchep.keyguard.common.service.crypto

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The JVM-only streaming side of [FileEncryptor]. Implementations may stage data on private disk,
 * but must authenticate the complete encrypted frame and validate its padding before returning
 * any plaintext from the resulting stream.
 */
interface StreamingFileDecryptor {
    fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream
}

/**
 * Decrypts the [input] stream, streaming when the encryptor supports it and
 * falling back to buffering the stream in memory otherwise.
 *
 * Streaming implementations authenticate the complete frame before exposing plaintext.
 */
fun FileEncryptor.decode(
    input: InputStream,
    key: ByteArray,
): InputStream = when (this) {
    is StreamingFileDecryptor -> decode(input, key)
    else -> ByteArrayInputStream(decode(input.readBytes(), key))
}
