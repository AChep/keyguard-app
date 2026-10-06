package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator

/**
 * A [CryptoGenerator] where every member throws. Tests subclass it and override
 * only the members they call, so an unexpected call fails loudly.
 */
@Suppress("TooManyFunctions")
internal open class TestCryptoGenerator : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = unused("hkdf")

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = unused("pbkdf2")

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = unused("argon2")

    override fun seed(length: Int): ByteArray = unused("seed")

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = unused("hmac")

    override fun hashSha1(data: ByteArray): ByteArray = unused("hashSha1")

    override fun hashSha256(data: ByteArray): ByteArray = unused("hashSha256")

    override fun hashMd5(data: ByteArray): ByteArray = unused("hashMd5")

    override fun uuid(): String = unused("uuid")

    override fun random(): Int = unused("random")

    override fun random(range: IntRange): Int = unused("random")

    private fun unused(member: String): Nothing =
        error("CryptoGenerator.$member is not used by this test")
}
