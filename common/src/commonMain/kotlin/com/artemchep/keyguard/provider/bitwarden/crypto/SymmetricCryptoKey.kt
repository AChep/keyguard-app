package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor

sealed interface CryptoKey {
    companion object;
}

fun CryptoKey.Companion.decodeAsymmetricOrThrow(
    data: ByteArray,
): AsymmetricCryptoKey = kotlin.run {
    AsymmetricCryptoKey(
        privateKey = data,
    )
}

fun CryptoKey.Companion.decodeSymmetricOrThrow(
    data: ByteArray,
): SymmetricCryptoKey2 = kotlin.run {
    SymmetricCryptoKey2(
        data = data,
    )
}

/**
 * @author Artem Chepurnyi
 */
@Suppress(
    "PrivatePropertyName",
    "FunctionName",
    "SpellCheckingInspection",
)
data class SymmetricCryptoKey2(
    val data: ByteArray,
) : CryptoKey {
    interface EncKeyProvider {
        val encKey: ByteArray
    }

    interface MacKeyProvider {
        val macKey: ByteArray
    }

    /**
     * @author Artem Chepurnyi
     */
    data class Crypto(
        override val encKey: ByteArray,
    ) : EncKeyProvider {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Crypto

            if (!encKey.contentEquals(other.encKey)) return false

            return true
        }

        override fun hashCode(): Int {
            return encKey.contentHashCode()
        }
    }

    /**
     * @author Artem Chepurnyi
     */
    data class CryptoWithMac(
        override val encKey: ByteArray,
        override val macKey: ByteArray,
    ) : EncKeyProvider, MacKeyProvider {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CryptoWithMac

            if (!encKey.contentEquals(other.encKey)) return false
            if (!macKey.contentEquals(other.macKey)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = encKey.contentHashCode()
            result = 31 * result + macKey.contentHashCode()
            return result
        }
    }

    private val aesCbc256_B64 by lazy {
        require(data.size == 32) {
            "Crypto key Aes+Cbc+256 must be 32 bytes long, got ${data.size} instead!"
        }
        Crypto(
            encKey = data,
        )
    }

    private val aesCbc256_HmacSha256_B64 by lazy {
        require(data.size == 64) {
            "Crypto key Aes+Cbc+256+Hmac256 must be 64 bytes long, got ${data.size} instead!"
        }
        CryptoWithMac(
            encKey = data.sliceArray(0 until 32),
            macKey = data.sliceArray(32 until 64),
        )
    }

    private val aesCbc128_HmacSha256_B64 by lazy {
        require(data.size == 32) {
            "Crypto key Aes+Cbc+128+Hmac256 must be 32 bytes long, got ${data.size} instead!"
        }
        CryptoWithMac(
            encKey = data.sliceArray(0 until 16),
            macKey = data.sliceArray(16 until 32),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SymmetricCryptoKey2

        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }

    // CBC 256

    fun requireAesCbc256_B64() = aesCbc256_B64

    fun requireAesCbc256_HmacSha256_B64() = aesCbc256_HmacSha256_B64

    // CBC 128

    fun requireAesCbc128_HmacSha256_B64() = aesCbc128_HmacSha256_B64
}

/**
 * @author Artem Chepurnyi
 */
data class AsymmetricCryptoKey(
    val privateKey: ByteArray,
) : CryptoKey {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AsymmetricCryptoKey

        if (!privateKey.contentEquals(other.privateKey)) return false

        return true
    }

    override fun hashCode(): Int {
        return privateKey.contentHashCode()
    }
}

/**
 * @author Artem Chepurnyi
 */
data class DecodeResult(
    val data: ByteArray,
    val type: CipherEncryptor.Type,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DecodeResult

        if (!data.contentEquals(other.data)) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + type.hashCode()
        return result
    }
}
