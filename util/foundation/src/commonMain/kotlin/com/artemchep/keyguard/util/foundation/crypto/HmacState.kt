package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.nativecrypto.NativeHashAlgorithm
import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES
import com.artemchep.keyguard.util.foundation.requireValidRange

interface HmacState : AutoCloseable {
    fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    )

    fun doFinal(): ByteArray

    /**
     * Releases any native resources held by this state. Idempotent.
     * [doFinal] also releases resources.
     */
    override fun close() {
    }
}

fun createHmac(
    key: ByteArray,
    algorithm: CryptoHashAlgorithm,
): HmacState = NativeHmacState(
    session = NativeCrypto.primitives.createHmac(
        key = key,
        algorithm = algorithm.toNativeHashAlgorithm(),
    ),
)

fun createHmacSha256(
    key: ByteArray,
): HmacState = createHmac(
    key = key,
    algorithm = CryptoHashAlgorithm.SHA_256,
)

private class NativeHmacState(
    private val session: NativeCryptoSession,
) : HmacState {
    private var closed = false

    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!closed) {
            "HMAC has already been finalized."
        }
        data.requireValidRange(offset, length)
        if (length == 0) return
        var consumed = 0
        while (consumed < length) {
            val chunkLength = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, length - consumed)
            val output = session.update(data, offset + consumed, chunkLength)
            try {
                check(output.isEmpty()) {
                    "Native HMAC update returned unexpected output."
                }
            } finally {
                output.fill(0)
            }
            consumed += chunkLength
        }
    }

    override fun doFinal(): ByteArray {
        check(!closed) {
            "HMAC has already been finalized."
        }
        closed = true
        return session.finish()
    }

    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }
}

private fun CryptoHashAlgorithm.toNativeHashAlgorithm(): NativeHashAlgorithm = when (this) {
    CryptoHashAlgorithm.SHA_1 -> NativeHashAlgorithm.SHA_1
    CryptoHashAlgorithm.SHA_256 -> NativeHashAlgorithm.SHA_256
    CryptoHashAlgorithm.SHA_512 -> NativeHashAlgorithm.SHA_512
    CryptoHashAlgorithm.MD5 -> NativeHashAlgorithm.MD5
}
