package com.artemchep.keyguard.util.foundation.crypto

private val defaultCryptoPrimitives: CryptoPrimitives by lazy {
    PlatformCryptoPrimitives()
}

fun hkdfSha256(
    seed: ByteArray,
    salt: ByteArray? = null,
    info: ByteArray? = null,
    length: Int = DEFAULT_HASH_LENGTH,
): ByteArray = defaultCryptoPrimitives.hkdfSha256(
    seed = seed,
    salt = salt,
    info = info,
    length = length,
)

fun pbkdf2Sha256(
    seed: ByteArray,
    salt: ByteArray,
    iterations: Int = 1,
    length: Int = DEFAULT_HASH_LENGTH,
): ByteArray = defaultCryptoPrimitives.pbkdf2Sha256(
    seed = seed,
    salt = salt,
    iterations = iterations,
    length = length,
)

fun randomBytes(length: Int): ByteArray = defaultCryptoPrimitives.randomBytes(length)

fun hmac(
    key: ByteArray,
    data: ByteArray,
    algorithm: CryptoHashAlgorithm,
): ByteArray = defaultCryptoPrimitives.hmac(
    key = key,
    data = data,
    algorithm = algorithm,
)

fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray = defaultCryptoPrimitives.hmacSha256(
    key = key,
    data = data,
)

fun sha1(data: ByteArray): ByteArray = defaultCryptoPrimitives.sha1(data)

fun sha256(data: ByteArray): ByteArray = defaultCryptoPrimitives.sha256(data)

fun sha512(data: ByteArray): ByteArray = defaultCryptoPrimitives.sha512(data)

fun md5(data: ByteArray): ByteArray = defaultCryptoPrimitives.md5(data)

fun aesEcbNoPaddingEncrypt(
    key: ByteArray,
    data: ByteArray,
): ByteArray = defaultCryptoPrimitives.aesEcbNoPaddingEncrypt(
    key = key,
    data = data,
)

fun aesCbcPkcs7Encrypt(
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray = defaultCryptoPrimitives.aesCbcPkcs7Encrypt(
    key = key,
    iv = iv,
    data = data,
)

fun aesCbcPkcs7Decrypt(
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray = defaultCryptoPrimitives.aesCbcPkcs7Decrypt(
    key = key,
    iv = iv,
    data = data,
)

