package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.Argon2Mode

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

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    fun hashSha1(data: ByteArray): ByteArray

    fun hashSha256(data: ByteArray): ByteArray

    fun hashMd5(data: ByteArray): ByteArray

    fun uuid(): String

    fun random(): Int

    fun random(range: IntRange): Int
}
