package com.artemchep.keyguard.util.foundation.crypto

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

expect fun createHmac(
    key: ByteArray,
    algorithm: CryptoHashAlgorithm,
): HmacState

fun createHmacSha256(
    key: ByteArray,
): HmacState = createHmac(
    key = key,
    algorithm = CryptoHashAlgorithm.SHA_256,
)

