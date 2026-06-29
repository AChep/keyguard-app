package com.artemchep.keyguard.util.foundation.crypto

expect class PlatformCryptoPrimitives() : CryptoPrimitives {
    override fun hkdfSha256(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray

    override fun pbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        length: Int,
    ): ByteArray

    override fun randomBytes(length: Int): ByteArray

    override fun randomInt(): Int

    override fun randomInt(until: Int): Int

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray

    override fun sha1(data: ByteArray): ByteArray

    override fun sha256(data: ByteArray): ByteArray

    override fun sha512(data: ByteArray): ByteArray

    override fun md5(data: ByteArray): ByteArray

    override fun aesEcbNoPaddingEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    override fun aesCbcPkcs7Encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray

    override fun aesCbcPkcs7Decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray
}
