package com.artemchep.keyguard.util.foundation.crypto

const val DEFAULT_HASH_LENGTH = 32

interface CryptoPrimitives {
    /**
     * Derives [length] bytes using HKDF with HMAC-SHA256 (RFC 5869).
     *
     * Note on [salt]: when [salt] is `null`, the HKDF *extract* step is skipped and
     * [seed] is used directly as the pseudo-random key
     * (BouncyCastle `HKDFParameters.skipExtractParameters` semantics).
     */
    fun hkdfSha256(
        seed: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray? = null,
        length: Int = DEFAULT_HASH_LENGTH,
    ): ByteArray

    fun pbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int = 1,
        length: Int = DEFAULT_HASH_LENGTH,
    ): ByteArray

    fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        length: Int = DEFAULT_HASH_LENGTH,
    ): ByteArray

    fun randomBytes(length: Int): ByteArray

    fun randomInt(): Int

    fun randomInt(until: Int): Int

    fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = hmac(
        key = key,
        data = data,
        algorithm = CryptoHashAlgorithm.SHA_256,
    )

    fun sha1(data: ByteArray): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun sha512(data: ByteArray): ByteArray

    fun md5(data: ByteArray): ByteArray

    fun aesEcbNoPaddingEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    fun aesCbcPkcs7Encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray

    fun aesCbcPkcs7Decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray
}
