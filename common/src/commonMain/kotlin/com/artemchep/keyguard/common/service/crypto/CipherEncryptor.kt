package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.provider.bitwarden.crypto.AsymmetricCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2

interface CipherEncryptor {
    @Suppress("EnumEntryName")
    enum class Type(
        val byte: Byte,
        val type: String = byte.toString(),
    ) {
        AesCbc256_B64(byte = 0),
        AesCbc128_HmacSha256_B64(byte = 1),
        AesCbc256_HmacSha256_B64(byte = 2),
        Rsa2048_OaepSha256_B64(byte = 3),
        Rsa2048_OaepSha1_B64(byte = 4),
        Rsa2048_OaepSha256_HmacSha256_B64(byte = 5),
        Rsa2048_OaepSha1_HmacSha256_B64(byte = 6),
    }

    fun decode2(
        cipher: String,
        symmetricCryptoKey: SymmetricCryptoKey2? = null,
        asymmetricCryptoKey: AsymmetricCryptoKey? = null,
    ): DecodeResult

    fun encode2(
        cipherType: Type,
        plainText: ByteArray,
        symmetricCryptoKey: SymmetricCryptoKey2? = null,
        asymmetricCryptoKey: AsymmetricCryptoKey? = null,
    ): String
}
