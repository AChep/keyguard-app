package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.util.foundation.crypto.PlatformCryptoPrimitives
import platform.Foundation.NSUUID

class CryptoGeneratorIos : CryptoGenerator {
    private val delegate = PlatformCryptoPrimitives()

    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = delegate.hkdfSha256(
        seed = seed,
        salt = salt,
        info = info,
        length = length,
    )

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = delegate.pbkdf2Sha256(
        seed = seed,
        salt = salt,
        iterations = iterations,
        length = length,
    )

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = delegate.argon2(
        mode = mode,
        seed = seed,
        salt = salt,
        iterations = iterations,
        memoryKb = memoryKb,
        parallelism = parallelism,
    )

    override fun seed(length: Int): ByteArray = delegate.randomBytes(length)

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = delegate.hmac(
        key = key,
        data = data,
        algorithm = algorithm,
    )

    override fun hashSha1(data: ByteArray): ByteArray = delegate.sha1(data)

    override fun hashSha256(data: ByteArray): ByteArray = delegate.sha256(data)

    override fun hashMd5(data: ByteArray): ByteArray = delegate.md5(data)

    override fun uuid(): String = NSUUID().UUIDString.lowercase()

    override fun random(): Int = delegate.randomInt()

    override fun random(range: IntRange): Int {
        val size = range.last.toLong() - range.first.toLong() + 1L
        require(size > 0L) {
            "Random range must not be empty."
        }
        if (size <= Int.MAX_VALUE) {
            return range.first + delegate.randomInt(size.toInt())
        }

        while (true) {
            val candidate = delegate.randomInt()
            if (candidate in range) {
                return candidate
            }
        }
    }
}
