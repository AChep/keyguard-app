package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm

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
