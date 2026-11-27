package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2

interface CryptoGenerator {
    fun hkdf(
        seed: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray? = null,
        length: Int = 32,
    ): ByteArray

    fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int = 1,
        length: Int = 32,
    ): ByteArray

    fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray

    fun seed(
        length: Int = 32,
    ): ByteArray

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

    fun hashSha1(data: ByteArray): ByteArray

    fun hashSha256(data: ByteArray): ByteArray

    fun hashMd5(data: ByteArray): ByteArray

    fun uuid(): String

    fun random(): Int

    fun random(range: IntRange): Int
}

/**
 * Derives an AES-CBC-256 + HMAC-SHA256 key from the [seed] with HKDF, runs the
 * [block] with it, then wipes the key. The [seed] stays as it is.
 */
inline fun <T> CryptoGenerator.withHkdfCipherKey(
    seed: ByteArray,
    salt: ByteArray?,
    info: ByteArray,
    length: Int,
    block: (SymmetricCryptoKey2) -> T,
): T {
    val bytes = hkdf(
        seed = seed,
        salt = salt,
        info = info,
        length = length,
    )
    val key = SymmetricCryptoKey2(bytes)
    val parts = key.requireAesCbc256_HmacSha256_B64()
    return try {
        block(key)
    } finally {
        bytes.fill(0)
        parts.encKey.fill(0)
        parts.macKey.fill(0)
    }
}

fun CryptoGenerator.seedHex(
    length: Int = 32,
): String = seed(length)
    .toHex()

fun <T> CryptoGenerator.listRandomOrThrow(list: List<T>) = kotlin.run {
    val index = random(0..list.lastIndex)
    list[index]
}

fun <T> CryptoGenerator.listShuffled(list: List<T>): List<T> = kotlin.run {
    if (list.size < 2) {
        return list
    }

    val output = list.toMutableList()
    for (i in output.lastIndex downTo 1) {
        val j = random(0..i)
        if (i != j) {
            val tmp = output[i]
            output[i] = output[j]
            output[j] = tmp
        }
    }
    output
}
