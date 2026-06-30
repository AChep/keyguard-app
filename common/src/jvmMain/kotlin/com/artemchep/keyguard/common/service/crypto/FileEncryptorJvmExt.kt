package com.artemchep.keyguard.common.service.crypto

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The JVM-only streaming side of [FileEncryptor]: decrypts an [InputStream]
 * lazily, never buffering the whole file in memory — attachments may be
 * arbitrarily large on the JVM download path.
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
 */
fun FileEncryptor.decode(
    input: InputStream,
    key: ByteArray,
): InputStream = when (this) {
    is StreamingFileDecryptor -> decode(input, key)
    else -> ByteArrayInputStream(decode(input.readBytes(), key))
}
