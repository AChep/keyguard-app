package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import kotlin.test.Test
import kotlin.test.assertEquals

class CryptoGeneratorIosTest {
    private val cryptoGenerator = CryptoGeneratorIos()
    private val base64Service = Base64ServiceImpl()

    @Test
    fun pbkdf2UsesRawSeedBytes() {
        val seed = byteArrayOf(0x00, 0xff.toByte(), 0x10, 0x80.toByte())
        val salt = "salt".encodeToByteArray()

        val hash = cryptoGenerator.pbkdf2(
            seed = seed,
            salt = salt,
            iterations = 2,
            length = 32,
        )

        assertEquals(
            "0c9e9fb091314d4543802bbd245373307aa937bf82b660d459306b2fab0f2be1",
            hash.toHex(),
        )
    }

    @Test
    fun pbkdf2MatchesSha256TestVector() {
        val hash = cryptoGenerator.pbkdf2(
            seed = "password".encodeToByteArray(),
            salt = "salt".encodeToByteArray(),
            iterations = 1,
            length = 32,
        )

        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hash.toHex(),
        )
    }

    @Test
    fun hkdfWithoutSaltExpandsRawSeed() {
        val seed = ByteArray(32) { it.toByte() }

        val key = cryptoGenerator.hkdf(
            seed = seed,
            info = "enc".encodeToByteArray(),
            length = 32,
        )

        assertEquals(
            "9c5639fac602366b486253191cb7900d7d8e3a1514676b118d5803a11dd97213",
            key.toHex(),
        )
    }

    @Test
    fun cipherEncryptorDecodesAesCbc256HmacSha256() {
        val cipherEncryptor = CipherEncryptorIos(
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        )
        val key = SymmetricCryptoKey2(
            data = base64Service.decode(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+Pw==",
            ),
        )

        val result = cipherEncryptor.decode2(
            cipher = "2.EBESExQVFhcYGRobHB0eHw==|BGjxpSaepyRbuExk1FbpTQ==|wGR9fGga7qxs1rh5nR/8VCi0FzIK4hL5kD7uVJifZ0g=",
            symmetricCryptoKey = key,
            asymmetricCryptoKey = null,
        )

        assertEquals(
            "hello",
            result.data.decodeToString(),
        )
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff)
        .toString(16)
        .padStart(2, '0')
}
