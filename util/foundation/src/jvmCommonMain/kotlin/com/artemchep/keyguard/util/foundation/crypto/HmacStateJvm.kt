package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.util.foundation.requireValidRange
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun createHmac(
    key: ByteArray,
    algorithm: CryptoHashAlgorithm,
): HmacState {
    val algorithmName = algorithm.hmacAlgorithmName()
    val mac = Mac.getInstance(algorithmName).apply {
        init(SecretKeySpec(key, algorithmName))
    }
    return JvmHmacState(mac)
}

private class JvmHmacState(
    private val mac: Mac,
) : HmacState {
    private var finalized = false

    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!finalized) {
            "HMAC has already been finalized."
        }
        data.requireValidRange(offset, length)
        mac.update(data, offset, length)
    }

    override fun doFinal(): ByteArray {
        check(!finalized) {
            "HMAC has already been finalized."
        }
        finalized = true
        return mac.doFinal()
    }
}

private fun CryptoHashAlgorithm.hmacAlgorithmName(): String = when (this) {
    CryptoHashAlgorithm.SHA_1 -> "HmacSHA1"
    CryptoHashAlgorithm.SHA_256 -> "HmacSHA256"
    CryptoHashAlgorithm.SHA_512 -> "HmacSHA512"
    CryptoHashAlgorithm.MD5 -> "HmacMD5"
}
