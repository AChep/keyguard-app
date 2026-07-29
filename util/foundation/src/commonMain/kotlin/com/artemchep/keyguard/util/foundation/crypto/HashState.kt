package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.nativecrypto.NativeHashAlgorithm
import com.artemchep.keyguard.util.foundation.requireValidRange

interface HashState : AutoCloseable {
    fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    )

    fun doFinal(): ByteArray

    /**
     * Releases native state without finalizing it. Idempotent.
     * [doFinal] also releases resources.
     */
    override fun close() = Unit
}

fun createHash(
    algorithm: CryptoHashAlgorithm,
): HashState = NativeHashSessionState(
    session = NativeCrypto.primitives.createDigest(
        algorithm = algorithm.toNativeHashAlgorithm(),
    ),
    label = "Hash",
)

fun createSha256(): HashState = createHash(CryptoHashAlgorithm.SHA_256)

internal class NativeHashSessionState(
    private val session: NativeCryptoSession,
    private val label: String,
) : HmacState {
    private var closed = false

    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!closed) {
            "$label has already been finalized."
        }
        data.requireValidRange(offset, length)
        var consumed = 0
        while (consumed < length) {
            val chunkLength = minOf(
                NATIVE_CRYPTO_STREAM_CHUNK_BYTES,
                length - consumed,
            )
            val output = session.update(
                data = data,
                offset = offset + consumed,
                length = chunkLength,
            )
            try {
                check(output.isEmpty()) {
                    "Native $label update returned unexpected output."
                }
            } finally {
                output.fill(0)
            }
            consumed += chunkLength
        }
    }

    override fun doFinal(): ByteArray {
        check(!closed) {
            "$label has already been finalized."
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

internal fun CryptoHashAlgorithm.toNativeHashAlgorithm(): NativeHashAlgorithm = when (this) {
    CryptoHashAlgorithm.SHA_1 -> NativeHashAlgorithm.SHA_1
    CryptoHashAlgorithm.SHA_256 -> NativeHashAlgorithm.SHA_256
    CryptoHashAlgorithm.SHA_512 -> NativeHashAlgorithm.SHA_512
    CryptoHashAlgorithm.MD5 -> NativeHashAlgorithm.MD5
}
