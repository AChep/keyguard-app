package com.artemchep.keyguard.util.foundation.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

/**
 * Independent differential gate against the former production BC implementation.
 *
 * Keep this test and its test-only BC dependency permanently alongside
 * independent KATs, property tests, and external interoperability fixtures.
 * BC is never selected by production code.
 */
class BouncyCastleDifferentialTest {
    private val native = PlatformCryptoPrimitives()
    private val provider = BouncyCastleProvider()

    @Test
    fun digestsAndHmacsMatchBouncyCastle() {
        val random = Random(0x44494745)
        val inputs = listOf(
            ByteArray(0),
            random.nextBytes(1),
            random.nextBytes(63),
            random.nextBytes(64),
            random.nextBytes(65),
            random.nextBytes(4_097),
        )
        val keys = listOf(random.nextBytes(1), random.nextBytes(32), random.nextBytes(257))
        val algorithms = listOf(
            DigestCase("SHA-1", "HmacSHA1", CryptoHashAlgorithm.SHA_1, native::sha1),
            DigestCase("SHA-256", "HmacSHA256", CryptoHashAlgorithm.SHA_256, native::sha256),
            DigestCase("SHA-512", "HmacSHA512", CryptoHashAlgorithm.SHA_512, native::sha512),
            DigestCase("MD5", "HmacMD5", CryptoHashAlgorithm.MD5, native::md5),
        )

        for (case in algorithms) {
            for (input in inputs) {
                assertContentEquals(
                    MessageDigest.getInstance(case.digestName, provider).digest(input),
                    case.nativeDigest(input),
                    "${case.digestName} mismatch for input=${input.size}",
                )
                for (key in keys) {
                    val expected = Mac.getInstance(case.hmacName, provider).run {
                        init(SecretKeySpec(key, case.hmacName))
                        doFinal(input)
                    }
                    assertContentEquals(
                        expected,
                        native.hmac(key, input, case.nativeAlgorithm),
                        "${case.hmacName} mismatch for key=${key.size}, input=${input.size}",
                    )
                }
            }
        }
    }

    @Test
    fun aesModesMatchBouncyCastleAcrossKeyAndPayloadSizes() {
        val random = Random(0x41455344)
        val iv = random.nextBytes(16)
        for (keyLength in listOf(16, 24, 32)) {
            val key = random.nextBytes(keyLength)
            for (dataLength in listOf(0, 16, 64, 4_096)) {
                val data = random.nextBytes(dataLength)
                assertContentEquals(
                    bcAes("AES/ECB/NoPadding", Cipher.ENCRYPT_MODE, key, null, data),
                    native.aesEcbNoPaddingEncrypt(key, data),
                    "AES-ECB mismatch for key=$keyLength, input=$dataLength",
                )
            }
            for (dataLength in listOf(0, 1, 15, 16, 17, 95, 4_097)) {
                val data = random.nextBytes(dataLength)
                val expected = bcAes("AES/CBC/PKCS7Padding", Cipher.ENCRYPT_MODE, key, iv, data)
                val actual = native.aesCbcPkcs7Encrypt(key, iv, data)
                assertContentEquals(
                    expected,
                    actual,
                    "AES-CBC encryption mismatch for key=$keyLength, input=$dataLength",
                )
                assertContentEquals(data, native.aesCbcPkcs7Decrypt(key, iv, expected))
                assertContentEquals(
                    data,
                    bcAes("AES/CBC/PKCS7Padding", Cipher.DECRYPT_MODE, key, iv, actual),
                )
            }
        }
    }

    @Test
    fun hkdfSha256MatchesBouncyCastleAcrossOptionalInputsAndLengths() {
        val random = Random(0x484b4446)
        val seeds = listOf(
            ByteArray(0),
            ByteArray(1) { 0x5a },
            random.nextBytes(32),
            random.nextBytes(97),
        )
        val salts = listOf<ByteArray?>(null, ByteArray(0), random.nextBytes(16))
        val infos = listOf<ByteArray?>(null, ByteArray(0), random.nextBytes(29))
        val lengths = listOf(0, 1, 31, 32, 33, 64, 255)

        for (seed in seeds) {
            for (salt in salts) {
                for (info in infos) {
                    for (length in lengths) {
                        assertContentEquals(
                            bcHkdfSha256(seed, salt, info, length),
                            native.hkdfSha256(seed, salt, info, length),
                            "HKDF mismatch for seed=${seed.size}, salt=${salt?.size}, " +
                                "info=${info?.size}, length=$length",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun pbkdf2Sha256MatchesBouncyCastleAcrossWorkFactorsAndLengths() {
        val random = Random(0x50424b44)
        val passwords = listOf(ByteArray(0), random.nextBytes(1), random.nextBytes(33))
        val salts = listOf(ByteArray(0), random.nextBytes(8), random.nextBytes(32))
        val iterations = listOf(1, 2, 17, 4_096)
        val lengths = listOf(0, 1, 16, 20, 32, 65)

        for (password in passwords) {
            for (salt in salts) {
                for (iterationCount in iterations) {
                    for (length in lengths) {
                        assertContentEquals(
                            bcPbkdf2Sha256(password, salt, iterationCount, length),
                            native.pbkdf2Sha256(password, salt, iterationCount, length),
                            "PBKDF2 mismatch for password=${password.size}, salt=${salt.size}, " +
                                "iterations=$iterationCount, length=$length",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun pbkdf2PreservesEveryPositiveIntIterationCount() {
        val password = "password".encodeToByteArray()
        val salt = "salt".encodeToByteArray()

        assertContentEquals(
            bcPbkdf2Sha256(password, salt, Int.MAX_VALUE, length = 0),
            native.pbkdf2Sha256(password, salt, Int.MAX_VALUE, length = 0),
        )
    }

    @Test
    fun argon2Version13MatchesBouncyCastleForEveryMode() {
        val random = Random(0x4152474f)
        val seed = random.nextBytes(41)
        val salt = random.nextBytes(16)
        val cases = listOf(
            Argon2Case(Argon2Mode.ARGON2_D, iterations = 1, memoryKb = 8, parallelism = 1, length = 16),
            Argon2Case(Argon2Mode.ARGON2_I, iterations = 2, memoryKb = 32, parallelism = 1, length = 32),
            Argon2Case(Argon2Mode.ARGON2_ID, iterations = 2, memoryKb = 64, parallelism = 2, length = 65),
        )

        for (case in cases) {
            assertContentEquals(
                bcArgon2(seed, salt, case),
                native.argon2(
                    mode = case.mode,
                    seed = seed,
                    salt = salt,
                    iterations = case.iterations,
                    memoryKb = case.memoryKb,
                    parallelism = case.parallelism,
                    length = case.length,
                ),
                "Argon2 mismatch for $case",
            )
        }
    }

    @Test
    fun argon2PreservesBouncyCastleMinimumOutputLength() {
        val seed = "password".encodeToByteArray()
        val salt = "somesalt".encodeToByteArray()
        val case = Argon2Case(
            mode = Argon2Mode.ARGON2_ID,
            iterations = 1,
            memoryKb = 8,
            parallelism = 1,
            length = 4,
        )

        for (length in 0 until 4) {
            assertFails {
                bcArgon2(seed, salt, case.copy(length = length))
            }
            assertFails {
                native.argon2(
                    mode = case.mode,
                    seed = seed,
                    salt = salt,
                    iterations = case.iterations,
                    memoryKb = case.memoryKb,
                    parallelism = case.parallelism,
                    length = length,
                )
            }
        }

        assertContentEquals(
            bcArgon2(seed, salt, case),
            native.argon2(
                mode = case.mode,
                seed = seed,
                salt = salt,
                iterations = case.iterations,
                memoryKb = case.memoryKb,
                parallelism = case.parallelism,
                length = case.length,
            ),
        )
    }

    @Test
    fun argon2PreservesBouncyCastleShortSaltAndLowMemorySemantics() {
        val seed = "password".encodeToByteArray()
        val cases = listOf(
            ByteArray(0) to Argon2Case(
                mode = Argon2Mode.ARGON2_D,
                iterations = 1,
                memoryKb = 0,
                parallelism = 1,
                length = 16,
            ),
            byteArrayOf(1) to Argon2Case(
                mode = Argon2Mode.ARGON2_I,
                iterations = 1,
                memoryKb = 1,
                parallelism = 1,
                length = 16,
            ),
            ByteArray(7) { index -> index.toByte() } to Argon2Case(
                mode = Argon2Mode.ARGON2_ID,
                iterations = 2,
                memoryKb = 7,
                parallelism = 1,
                length = 32,
            ),
            byteArrayOf(3, 1, 4) to Argon2Case(
                mode = Argon2Mode.ARGON2_ID,
                iterations = 1,
                memoryKb = 8,
                parallelism = 2,
                length = 16,
            ),
        )

        for ((salt, case) in cases) {
            assertContentEquals(
                bcArgon2(seed, salt, case),
                native.argon2(
                    mode = case.mode,
                    seed = seed,
                    salt = salt,
                    iterations = case.iterations,
                    memoryKb = case.memoryKb,
                    parallelism = case.parallelism,
                    length = case.length,
                ),
                "Argon2 legacy-input mismatch for salt=${salt.size}, $case",
            )
        }
    }

    private fun bcHkdfSha256(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = ByteArray(length).also { output ->
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(
                if (salt == null) {
                    HKDFParameters.skipExtractParameters(seed, info)
                } else {
                    HKDFParameters(seed, salt, info)
                },
            )
            generateBytes(output, 0, output.size)
        }
    }

    private fun bcPbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray {
        if (length == 0) return ByteArray(0)
        val parameters = PKCS5S2ParametersGenerator(SHA256Digest()).apply {
            init(seed, salt, iterations)
        }.generateDerivedMacParameters(length * 8)
        return (parameters as KeyParameter).key
    }

    private fun bcArgon2(
        seed: ByteArray,
        salt: ByteArray,
        case: Argon2Case,
    ): ByteArray {
        val mode = when (case.mode) {
            Argon2Mode.ARGON2_D -> Argon2Parameters.ARGON2_d
            Argon2Mode.ARGON2_I -> Argon2Parameters.ARGON2_i
            Argon2Mode.ARGON2_ID -> Argon2Parameters.ARGON2_id
        }
        val parameters = Argon2Parameters.Builder(mode)
            .withIterations(case.iterations)
            .withMemoryAsKB(case.memoryKb)
            .withParallelism(case.parallelism)
            .withSalt(salt)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        return ByteArray(case.length).also { output ->
            Argon2BytesGenerator().apply {
                init(parameters)
            }.generateBytes(seed, output)
        }
    }

    private fun bcAes(
        transformation: String,
        mode: Int,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray = Cipher.getInstance(transformation, provider).run {
        val keySpec = SecretKeySpec(key, "AES")
        if (iv == null) {
            init(mode, keySpec)
        } else {
            init(mode, keySpec, IvParameterSpec(iv))
        }
        doFinal(data)
    }

    private data class DigestCase(
        val digestName: String,
        val hmacName: String,
        val nativeAlgorithm: CryptoHashAlgorithm,
        val nativeDigest: (ByteArray) -> ByteArray,
    )

    private data class Argon2Case(
        val mode: Argon2Mode,
        val iterations: Int,
        val memoryKb: Int,
        val parallelism: Int,
        val length: Int,
    )
}
