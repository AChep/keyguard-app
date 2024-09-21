package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.crypto.util.hkdfSha256
import com.artemchep.keyguard.crypto.util.pbkdf2Sha256
import com.artemchep.keyguard.crypto.util.sha1
import com.artemchep.keyguard.crypto.util.sha256
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

class CryptoGeneratorJvm() : CryptoGenerator {
    companion object {
        const val DEFAULT_ARGON_HASH_LENGTH = 32
    }

    private val secureRandom by lazy {
        SecureRandom()
    }

    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = hkdfSha256(
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
    ): ByteArray = pbkdf2Sha256(seed, salt, iterations, length)

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray {
        val hash = ByteArray(DEFAULT_ARGON_HASH_LENGTH)

        val modeInt = when (mode) {
            Argon2Mode.ARGON2_D -> Argon2Parameters.ARGON2_d
            Argon2Mode.ARGON2_I -> Argon2Parameters.ARGON2_i
            Argon2Mode.ARGON2_ID -> Argon2Parameters.ARGON2_id
        }
        val params = Argon2Parameters.Builder(modeInt)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(parallelism)
            .withSalt(salt)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(seed, hash)
        return hash
    }

    override fun seed(length: Int): ByteArray = secureRandom.generateSeed(length)

    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    override fun hashSha1(data: ByteArray): ByteArray = sha1(data)

    override fun hashSha256(data: ByteArray): ByteArray = sha256(data)

    override fun hashMd5(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(data)
        return md.digest()
    }

    override fun uuid(): String = Uuid.random().toString()

    override fun random(): Int = secureRandom.nextInt()

    override fun random(range: IntRange): Int = kotlin.run {
        val size = range.last - range.first
        secureRandom.nextInt(size + 1) + range.first
    }
}
