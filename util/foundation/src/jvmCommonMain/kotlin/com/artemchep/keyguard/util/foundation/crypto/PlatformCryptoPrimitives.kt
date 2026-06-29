package com.artemchep.keyguard.util.foundation.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual class PlatformCryptoPrimitives actual constructor() : CryptoPrimitives {
    private val secureRandom by lazy {
        SecureRandom()
    }

    actual override fun hkdfSha256(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = hkdf(
        digest = SHA256Digest(),
        seed = seed,
        salt = salt,
        info = info,
        length = length,
    )

    actual override fun pbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = pbkdf2(
        digest = SHA256Digest(),
        seed = seed,
        salt = salt,
        iterations = iterations,
        length = length,
    )

    actual override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        length: Int,
    ): ByteArray {
        val hash = ByteArray(length)
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

    actual override fun randomBytes(length: Int): ByteArray {
        require(length >= 0) {
            "Random output length must not be negative."
        }
        val output = ByteArray(length)
        secureRandom.nextBytes(output)
        return output
    }

    actual override fun randomInt(): Int = secureRandom.nextInt()

    actual override fun randomInt(until: Int): Int = secureRandom.nextInt(until)

    actual override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray {
        val algorithmName = when (algorithm) {
            CryptoHashAlgorithm.SHA_1 -> "HmacSHA1"
            CryptoHashAlgorithm.SHA_256 -> "HmacSHA256"
            CryptoHashAlgorithm.SHA_512 -> "HmacSHA512"
            CryptoHashAlgorithm.MD5 -> "HmacMD5"
        }
        return Mac.getInstance(algorithmName).run {
            init(SecretKeySpec(key, algorithmName))
            doFinal(data)
        }
    }

    actual override fun sha1(data: ByteArray): ByteArray = messageDigest("SHA-1", data)

    actual override fun sha256(data: ByteArray): ByteArray = messageDigest("SHA-256", data)

    actual override fun sha512(data: ByteArray): ByteArray = messageDigest("SHA-512", data)

    actual override fun md5(data: ByteArray): ByteArray = messageDigest("MD5", data)

    actual override fun aesEcbNoPaddingEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = Cipher.getInstance("AES/ECB/NoPadding")
        .apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        .doFinal(data)

    actual override fun aesCbcPkcs7Encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = aesCbcPkcs7(Cipher.ENCRYPT_MODE, key, iv, data)

    actual override fun aesCbcPkcs7Decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = aesCbcPkcs7(Cipher.DECRYPT_MODE, key, iv, data)

    private fun hkdf(
        digest: Digest,
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray {
        require(length >= 0) {
            "HKDF output length must not be negative."
        }
        require(length <= 255 * digest.digestSize) {
            "HKDF output length must not exceed ${255 * digest.digestSize} bytes."
        }
        val out = ByteArray(length)
        HKDFBytesGenerator(digest)
            .apply {
                val params = if (salt != null) {
                    HKDFParameters(
                        seed,
                        salt,
                        info,
                    )
                } else {
                    HKDFParameters.skipExtractParameters(
                        seed,
                        info,
                    )
                }
                init(params)
            }
            .generateBytes(out, 0, length)
        return out
    }

    private fun pbkdf2(
        digest: Digest,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray {
        require(iterations > 0) {
            "PBKDF2 iterations must be positive."
        }
        require(length >= 0) {
            "PBKDF2 output length must not be negative."
        }
        if (length == 0) {
            return ByteArray(0)
        }
        val params = PKCS5S2ParametersGenerator(digest)
            .apply {
                init(
                    seed,
                    salt,
                    iterations,
                )
            }
            .generateDerivedMacParameters(length * 8)
        return (params as KeyParameter).key
    }

    private fun messageDigest(
        algorithm: String,
        data: ByteArray,
    ): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        digest.reset()
        return digest.digest(data)
    }

    private fun aesCbcPkcs7(
        mode: Int,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding")
        .apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        .doFinal(data)
}
