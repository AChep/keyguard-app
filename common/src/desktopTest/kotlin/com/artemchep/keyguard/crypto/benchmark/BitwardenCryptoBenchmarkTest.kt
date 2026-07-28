package com.artemchep.keyguard.crypto.benchmark

import com.artemchep.keyguard.crypto.bouncyCastleAesCbcPkcs7
import com.artemchep.keyguard.nativecrypto.NativeArgon2Mode
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeRsaOaepHash
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in BC-vs-Native Crypto comparison for the primitives on Bitwarden hot paths.
 *
 * Run with `./gradlew :common:bitwardenCryptoBenchmark`. This suite is excluded from the normal
 * desktop tests because the production-sized KDF cases are deliberately expensive.
 *
 * Timed Native Crypto operations include JNI and, where applicable, protobuf overhead. Fixture
 * loading, random IV generation, Base64/string framing, correctness assertions, and RSA key
 * generation are excluded.
 */
class BitwardenCryptoBenchmarkTest {
    private val harness = BitwardenCryptoBenchmarkHarness()

    @Test
    fun `compare Bouncy Castle and Native Crypto on Bitwarden operations`() {
        NativeCrypto.ensureReady()
        val rsaFixture = loadRsaFixture()
        val benchmarkCases = buildBenchmarkCases(rsaFixture)

        println(
            "$OUTPUT_PREFIX kind=environment" +
                " os=${token(System.getProperty("os.name"))}" +
                " arch=${token(System.getProperty("os.arch"))}" +
                " jvm=${token(System.getProperty("java.version"))}" +
                " bouncy_castle=${token(BouncyCastleProvider().versionStr)}" +
                " native_abi=${NativeCrypto.abiVersion}" +
                " cases=${benchmarkCases.size}",
        )

        val comparisons =
            benchmarkCases.map { case ->
                val bouncyCastleResult = case.bouncyCastle()
                val nativeCryptoResult = case.nativeCrypto()
                try {
                    assertContentEquals(
                        expected = bouncyCastleResult,
                        actual = nativeCryptoResult,
                        message = "BC/native preflight mismatch for ${case.spec.name}",
                    )
                } finally {
                    bouncyCastleResult.fill(0)
                    nativeCryptoResult.fill(0)
                }

                harness.compare(
                    spec = case.spec,
                    bouncyCastle = case.bouncyCastle,
                    nativeCrypto = case.nativeCrypto,
                )
            }

        assertEquals(benchmarkCases.size, comparisons.size)
        assertTrue(comparisons.all { it.bouncyCastle.samplesNsPerOperation.isNotEmpty() })
        assertTrue(comparisons.all { it.nativeCrypto.samplesNsPerOperation.isNotEmpty() })
    }

    private fun buildBenchmarkCases(rsaFixture: RsaFixture): List<BenchmarkCase> =
        buildList {
            val password = "correct horse battery staple".encodeToByteArray()
            val accountSalt = "benchmark@example.invalid".encodeToByteArray()
            val masterKey = deterministicBytes(32, seed = 0x31)
            val sendKeyMaterial = deterministicBytes(16, seed = 0x53)

            add(
                BenchmarkCase(
                    spec =
                        BitwardenCryptoBenchmarkSpec(
                            name = "login-pbkdf2-sha256-i600000",
                            operationsPerSample = 1,
                            warmupSamples = 1,
                            measurementSamples = 5,
                        ),
                    bouncyCastle = {
                        bcPbkdf2Sha256(
                            seed = password,
                            salt = accountSalt,
                            iterations = 600_000,
                            length = 32,
                        )
                    },
                    nativeCrypto = {
                        NativeCryptoPrimitives.pbkdf2Sha256(
                            seed = password,
                            salt = accountSalt,
                            iterations = 600_000,
                            length = 32,
                        )
                    },
                ),
            )

            add(
                BenchmarkCase(
                    spec =
                        BitwardenCryptoBenchmarkSpec(
                            name = "login-argon2id-v13-t3-m64mib-p4",
                            operationsPerSample = 1,
                            warmupSamples = 1,
                            measurementSamples = 5,
                        ),
                    bouncyCastle = {
                        val hashedSalt = bcSha256(accountSalt)
                        try {
                            bcArgon2id(
                                seed = password,
                                salt = hashedSalt,
                                iterations = 3,
                                memoryKb = 64 * 1024,
                                parallelism = 4,
                                length = 32,
                            )
                        } finally {
                            hashedSalt.fill(0)
                        }
                    },
                    nativeCrypto = {
                        val hashedSalt = NativeCryptoPrimitives.sha256(accountSalt)
                        try {
                            NativeCryptoPrimitives.argon2(
                                mode = NativeArgon2Mode.ARGON2_ID,
                                seed = password,
                                salt = hashedSalt,
                                iterations = 3,
                                memoryKb = 64 * 1024,
                                parallelism = 4,
                                length = 32,
                            )
                        } finally {
                            hashedSalt.fill(0)
                        }
                    },
                ),
            )

            add(
                BenchmarkCase(
                    spec =
                        BitwardenCryptoBenchmarkSpec(
                            name = "master-key-stretch-hkdf-sha256-enc-mac",
                            operationsPerSample = 5_000,
                        ),
                    bouncyCastle = {
                        bcHkdfSha256(masterKey, salt = null, info = ENC_INFO, length = 32) +
                            bcHkdfSha256(masterKey, salt = null, info = MAC_INFO, length = 32)
                    },
                    nativeCrypto = {
                        NativeCryptoPrimitives.hkdfSha256(
                            seed = masterKey,
                            salt = null,
                            info = ENC_INFO,
                            length = 32,
                        ) +
                            NativeCryptoPrimitives.hkdfSha256(
                                seed = masterKey,
                                salt = null,
                                info = MAC_INFO,
                                length = 32,
                            )
                    },
                ),
            )

            add(
                BenchmarkCase(
                    spec =
                        BitwardenCryptoBenchmarkSpec(
                            name = "send-key-hkdf-sha256",
                            operationsPerSample = 10_000,
                        ),
                    bouncyCastle = {
                        bcHkdfSha256(
                            seed = sendKeyMaterial,
                            salt = SEND_SALT,
                            info = SEND_INFO,
                            length = 64,
                        )
                    },
                    nativeCrypto = {
                        NativeCryptoPrimitives.hkdfSha256(
                            seed = sendKeyMaterial,
                            salt = SEND_SALT,
                            info = SEND_INFO,
                            length = 64,
                        )
                    },
                ),
            )

            addAesHmacCases(
                label = "vault-type1-aes128-cbc-hmac-sha256",
                key = deterministicBytes(32, seed = 0x11),
                encryptionKeyLength = 16,
                payloadSizes = listOf(1024),
            )
            addAesHmacCases(
                label = "vault-type2-aes256-cbc-hmac-sha256",
                key = deterministicBytes(64, seed = 0x22),
                encryptionKeyLength = 32,
                payloadSizes = listOf(32, 1024, 64 * 1024),
            )
            addAttachmentStreamingCases(
                key = deterministicBytes(64, seed = 0x44),
                payloadBytes = 4 * 1024 * 1024,
            )

            listOf(
                "rsa2048-oaep-sha1-decrypt" to NativeRsaOaepHash.SHA_1,
                "rsa2048-oaep-sha256-decrypt" to NativeRsaOaepHash.SHA_256,
            ).forEach { (name, hash) ->
                val ciphertext =
                    when (hash) {
                        NativeRsaOaepHash.SHA_1 -> rsaFixture.oaepSha1
                        NativeRsaOaepHash.SHA_256 -> rsaFixture.oaepSha256
                    }
                check(
                    bcRsaOaepDecrypt(rsaFixture.privateKeyPkcs8, ciphertext, hash)
                        .contentEquals(rsaFixture.plaintext),
                )
                check(
                    NativeCryptoPrimitives
                        .rsaOaepDecrypt(
                            privateKeyPkcs8 = rsaFixture.privateKeyPkcs8,
                            ciphertext = ciphertext,
                            hash = hash,
                        ).contentEquals(rsaFixture.plaintext),
                )
                add(
                    BenchmarkCase(
                        spec =
                            BitwardenCryptoBenchmarkSpec(
                                name = name,
                                operationsPerSample = 80,
                                warmupSamples = 3,
                                measurementSamples = 8,
                            ),
                        bouncyCastle = {
                            bcRsaOaepDecrypt(rsaFixture.privateKeyPkcs8, ciphertext, hash)
                        },
                        nativeCrypto = {
                            NativeCryptoPrimitives.rsaOaepDecrypt(
                                privateKeyPkcs8 = rsaFixture.privateKeyPkcs8,
                                ciphertext = ciphertext,
                                hash = hash,
                            )
                        },
                    ),
                )
            }

            add(
                BenchmarkCase(
                    spec =
                        BitwardenCryptoBenchmarkSpec(
                            name = "rsa2048-pkcs8-to-spki",
                            operationsPerSample = 500,
                            warmupSamples = 3,
                            measurementSamples = 8,
                        ),
                    bouncyCastle = {
                        bcRsaPublicKeySpkiFromPkcs8(rsaFixture.privateKeyPkcs8)
                    },
                    nativeCrypto = {
                        NativeCryptoPrimitives.rsaPublicKeySpkiFromPkcs8(rsaFixture.privateKeyPkcs8)
                    },
                ),
            )
        }

    private fun MutableList<BenchmarkCase>.addAesHmacCases(
        label: String,
        key: ByteArray,
        encryptionKeyLength: Int,
        payloadSizes: List<Int>,
    ) {
        val encryptionKey = key.copyOfRange(0, encryptionKeyLength)
        val macKey = key.copyOfRange(encryptionKeyLength, key.size)
        val iv = deterministicBytes(16, seed = encryptionKeyLength)

        payloadSizes.forEach { payloadBytes ->
            val plaintext = deterministicBytes(payloadBytes, seed = payloadBytes xor encryptionKeyLength)
            val frame = bcAesHmacEncrypt(encryptionKey, macKey, iv, plaintext)
            val operationsPerSample =
                when {
                    payloadBytes <= 32 -> 5_000
                    payloadBytes <= 1024 -> 2_000
                    else -> 50
                }

            add(
                BenchmarkCase(
                    spec =
                        BitwardenCryptoBenchmarkSpec(
                            name = "$label-encrypt-${sizeLabel(payloadBytes)}",
                            payloadBytes = payloadBytes,
                            operationsPerSample = operationsPerSample,
                            warmupSamples = 4,
                        ),
                    bouncyCastle = {
                        bcAesHmacEncrypt(encryptionKey, macKey, iv, plaintext).encoded()
                    },
                    nativeCrypto = {
                        nativeAesHmacEncrypt(encryptionKey, macKey, iv, plaintext).encoded()
                    },
                ),
            )
            add(
                BenchmarkCase(
                    spec =
                        BitwardenCryptoBenchmarkSpec(
                            name = "$label-decrypt-${sizeLabel(payloadBytes)}",
                            payloadBytes = payloadBytes,
                            operationsPerSample = operationsPerSample,
                            warmupSamples = 4,
                        ),
                    bouncyCastle = {
                        bcAesHmacDecrypt(encryptionKey, macKey, frame)
                    },
                    nativeCrypto = {
                        nativeAesHmacDecrypt(encryptionKey, macKey, frame)
                    },
                ),
            )
        }
    }

    private fun MutableList<BenchmarkCase>.addAttachmentStreamingCases(
        key: ByteArray,
        payloadBytes: Int,
    ) {
        val encryptionKey = key.copyOfRange(0, 32)
        val macKey = key.copyOfRange(32, 64)
        val iv = deterministicBytes(16, seed = 0x7a)
        val plaintextChunks =
            deterministicBytes(payloadBytes, seed = 0x6f)
                .toChunks(ATTACHMENT_CHUNK_BYTES)
        val frame = bcAesHmacEncryptStreaming(encryptionKey, macKey, iv, plaintextChunks)
        val ciphertextChunks = frame.ciphertext.toChunks(ATTACHMENT_CHUNK_BYTES)

        add(
            BenchmarkCase(
                spec =
                    BitwardenCryptoBenchmarkSpec(
                        name = "attachment-type2-stream-encrypt-${sizeLabel(payloadBytes)}",
                        payloadBytes = payloadBytes,
                        operationsPerSample = 1,
                        warmupSamples = 2,
                        measurementSamples = 6,
                    ),
                bouncyCastle = {
                    bcAesHmacEncryptStreaming(encryptionKey, macKey, iv, plaintextChunks).encoded()
                },
                nativeCrypto = {
                    nativeAesHmacEncryptStreaming(encryptionKey, macKey, iv, plaintextChunks).encoded()
                },
            ),
        )
        add(
            BenchmarkCase(
                spec =
                    BitwardenCryptoBenchmarkSpec(
                        name = "attachment-type2-stream-decrypt-${sizeLabel(payloadBytes)}",
                        payloadBytes = payloadBytes,
                        operationsPerSample = 1,
                        warmupSamples = 2,
                        measurementSamples = 6,
                    ),
                bouncyCastle = {
                    bcAesHmacDecryptStreaming(
                        encryptionKey = encryptionKey,
                        macKey = macKey,
                        iv = iv,
                        expectedMac = frame.mac,
                        ciphertextChunks = ciphertextChunks,
                    )
                },
                nativeCrypto = {
                    nativeAesHmacDecryptStreaming(
                        encryptionKey = encryptionKey,
                        macKey = macKey,
                        iv = iv,
                        expectedMac = frame.mac,
                        ciphertextChunks = ciphertextChunks,
                    )
                },
            ),
        )
    }

    private companion object {
        const val OUTPUT_PREFIX = "[bitwarden-crypto-benchmark]"
        const val ATTACHMENT_CHUNK_BYTES = 64 * 1024

        val ENC_INFO = "enc".encodeToByteArray()
        val MAC_INFO = "mac".encodeToByteArray()
        val SEND_SALT = "bitwarden-send".encodeToByteArray()
        val SEND_INFO = "send".encodeToByteArray()
    }
}

private data class BenchmarkCase(
    val spec: BitwardenCryptoBenchmarkSpec,
    val bouncyCastle: () -> ByteArray,
    val nativeCrypto: () -> ByteArray,
)

private data class AesHmacFrame(
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val mac: ByteArray,
) {
    fun encoded(): ByteArray = ciphertext + mac
}

private data class RsaFixture(
    val privateKeyPkcs8: ByteArray,
    val publicKeySpki: ByteArray,
    val plaintext: ByteArray,
    val oaepSha1: ByteArray,
    val oaepSha256: ByteArray,
)

private fun loadRsaFixture(): RsaFixture {
    val fields =
        requireNotNull(
            BitwardenCryptoBenchmarkTest::class.java.getResourceAsStream(
                "/com/artemchep/keyguard/crypto/benchmark/rsa_oaep_openssl.hex",
            ),
        ) {
            "Missing synthetic RSA benchmark fixture"
        }.bufferedReader().useLines { lines ->
            lines
                .filterNot { it.isBlank() || it.startsWith('#') }
                .associate { line ->
                    val (name, value) = line.split('=', limit = 2)
                    name to value.hexToByteArray()
                }
        }
    return RsaFixture(
        privateKeyPkcs8 = fields.getValue("pkcs8"),
        publicKeySpki = fields.getValue("spki"),
        plaintext = fields.getValue("plaintext"),
        oaepSha1 = fields.getValue("oaep_sha1"),
        oaepSha256 = fields.getValue("oaep_sha256"),
    ).also { fixture ->
        assertContentEquals(
            fixture.publicKeySpki,
            bcRsaPublicKeySpkiFromPkcs8(fixture.privateKeyPkcs8),
        )
    }
}

private fun bcHkdfSha256(
    seed: ByteArray,
    salt: ByteArray?,
    info: ByteArray?,
    length: Int,
): ByteArray =
    ByteArray(length).also { output ->
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
    val parameters =
        PKCS5S2ParametersGenerator(SHA256Digest())
            .apply {
                init(seed, salt, iterations)
            }.generateDerivedMacParameters(length * 8)
    return (parameters as KeyParameter).key
}

private fun bcArgon2id(
    seed: ByteArray,
    salt: ByteArray,
    iterations: Int,
    memoryKb: Int,
    parallelism: Int,
    length: Int,
): ByteArray {
    val parameters =
        Argon2Parameters
            .Builder(Argon2Parameters.ARGON2_id)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(parallelism)
            .withSalt(salt)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
    return ByteArray(length).also { output ->
        Argon2BytesGenerator()
            .apply {
                init(parameters)
            }.generateBytes(seed, output)
    }
}

private fun bcSha256(data: ByteArray): ByteArray =
    ByteArray(32).also { output ->
        SHA256Digest().apply {
            update(data, 0, data.size)
            doFinal(output, 0)
        }
    }

private fun bcHmacSha256(
    key: ByteArray,
    vararg chunks: ByteArray,
): ByteArray {
    val hmac =
        HMac(SHA256Digest()).apply {
            init(KeyParameter(key))
        }
    chunks.forEach { chunk -> hmac.update(chunk, 0, chunk.size) }
    return ByteArray(hmac.macSize).also { output ->
        hmac.doFinal(output, 0)
    }
}

private fun nativeHmacSha256(
    key: ByteArray,
    vararg chunks: ByteArray,
): ByteArray =
    NativeCryptoPrimitives.createHmacSha256(key).use { session ->
        chunks.forEach { chunk ->
            var offset = 0
            while (offset < chunk.size) {
                val length = minOf(64 * 1024, chunk.size - offset)
                val output = session.update(chunk, offset, length)
                check(output.isEmpty()) { "Native HMAC update produced output" }
                offset += length
            }
        }
        session.finish()
    }

private fun bcAesHmacEncrypt(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): AesHmacFrame {
    val ciphertext =
        bouncyCastleAesCbcPkcs7(
            key = encryptionKey,
            iv = iv,
            data = plaintext,
            encrypt = true,
        )
    return AesHmacFrame(
        iv = iv,
        ciphertext = ciphertext,
        mac = bcHmacSha256(macKey, iv, ciphertext),
    )
}

private fun nativeAesHmacEncrypt(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): AesHmacFrame {
    val result = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey = encryptionKey,
        macKey = macKey,
        iv = iv,
        plaintext = plaintext,
    )
    return AesHmacFrame(
        iv = iv,
        ciphertext = result.ciphertext,
        mac = result.mac,
    )
}

private fun bcAesHmacDecrypt(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    frame: AesHmacFrame,
): ByteArray {
    val actualMac = bcHmacSha256(macKey, frame.iv, frame.ciphertext)
    check(MessageDigest.isEqual(frame.mac, actualMac))
    return bouncyCastleAesCbcPkcs7(
        key = encryptionKey,
        iv = frame.iv,
        data = frame.ciphertext,
        encrypt = false,
    )
}

private fun nativeAesHmacDecrypt(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    frame: AesHmacFrame,
): ByteArray =
    NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey = encryptionKey,
        macKey = macKey,
        iv = frame.iv,
        ciphertext = frame.ciphertext,
        expectedMac = frame.mac,
    )

private fun bcAesHmacEncryptStreaming(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    plaintextChunks: List<ByteArray>,
): AesHmacFrame {
    val cipher = bcAesCipher(encrypt = true, encryptionKey, iv)
    val hmac =
        HMac(SHA256Digest()).apply {
            init(KeyParameter(macKey))
            update(iv, 0, iv.size)
        }
    val plaintextSize = plaintextChunks.sumOf(ByteArray::size)
    val output = ByteArray(cipher.getOutputSize(plaintextSize))
    var outputOffset = 0
    plaintextChunks.forEach { chunk ->
        val length = cipher.processBytes(chunk, 0, chunk.size, output, outputOffset)
        if (length > 0) hmac.update(output, outputOffset, length)
        outputOffset += length
    }
    val finalLength = cipher.doFinal(output, outputOffset)
    if (finalLength > 0) hmac.update(output, outputOffset, finalLength)
    outputOffset += finalLength
    return AesHmacFrame(
        iv = iv,
        ciphertext = output.copyOf(outputOffset),
        mac = ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) },
    )
}

private fun nativeAesHmacEncryptStreaming(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    plaintextChunks: List<ByteArray>,
): AesHmacFrame =
    NativeCryptoPrimitives
        .createAesCbcPkcs7HmacSha256Encryptor(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
        ).use { cipher ->
            val plaintextSize = plaintextChunks.sumOf(ByteArray::size)
            val output = ByteArray(plaintextSize + 16)
            var outputOffset = 0
            plaintextChunks.forEach { chunk ->
                val ciphertext = cipher.update(chunk)
                ciphertext.copyInto(output, destinationOffset = outputOffset)
                outputOffset += ciphertext.size
            }
            val finalResult = cipher.finish()
            finalResult.ciphertext.copyInto(output, destinationOffset = outputOffset)
            outputOffset += finalResult.ciphertext.size
            AesHmacFrame(
                iv = iv,
                ciphertext = output.copyOf(outputOffset),
                mac = finalResult.mac,
            ).also {
                finalResult.ciphertext.fill(0)
            }
        }

private fun bcAesHmacDecryptStreaming(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    expectedMac: ByteArray,
    ciphertextChunks: List<ByteArray>,
): ByteArray {
    val cipher = bcAesCipher(encrypt = false, encryptionKey, iv)
    val hmac =
        HMac(SHA256Digest()).apply {
            init(KeyParameter(macKey))
            update(iv, 0, iv.size)
        }
    val ciphertextSize = ciphertextChunks.sumOf(ByteArray::size)
    val output = ByteArray(cipher.getOutputSize(ciphertextSize))
    var outputOffset = 0
    ciphertextChunks.forEach { chunk ->
        hmac.update(chunk, 0, chunk.size)
        outputOffset += cipher.processBytes(chunk, 0, chunk.size, output, outputOffset)
    }
    outputOffset += cipher.doFinal(output, outputOffset)
    val actualMac = ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) }
    check(MessageDigest.isEqual(expectedMac, actualMac))
    return output.copyOf(outputOffset)
}

private fun nativeAesHmacDecryptStreaming(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    expectedMac: ByteArray,
    ciphertextChunks: List<ByteArray>,
): ByteArray =
    NativeCryptoPrimitives
        .createAesCbcPkcs7HmacSha256Decryptor(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
            expectedMac = expectedMac,
        ).use { provisionalDecryptor ->
            val ciphertextSize = ciphertextChunks.sumOf(ByteArray::size)
            val output = ByteArray(ciphertextSize)
            var outputOffset = 0
            ciphertextChunks.forEach { chunk ->
                val provisionalPlaintext = provisionalDecryptor.updateProvisional(chunk)
                provisionalPlaintext.copyInto(output, destinationOffset = outputOffset)
                outputOffset += provisionalPlaintext.size
            }
            val finalPlaintext = provisionalDecryptor.authenticateAndFinish()
            finalPlaintext.copyInto(output, destinationOffset = outputOffset)
            outputOffset += finalPlaintext.size
            output.copyOf(outputOffset)
        }

private fun bcAesCipher(
    encrypt: Boolean,
    key: ByteArray,
    iv: ByteArray,
): PaddedBufferedBlockCipher =
    PaddedBufferedBlockCipher(
        CBCBlockCipher.newInstance(AESEngine.newInstance()),
        PKCS7Padding(),
    ).apply {
        init(encrypt, ParametersWithIV(KeyParameter(key), iv))
    }

private fun bcRsaOaepDecrypt(
    privateKeyPkcs8: ByteArray,
    ciphertext: ByteArray,
    hash: NativeRsaOaepHash,
): ByteArray {
    fun digest(): Digest =
        when (hash) {
            NativeRsaOaepHash.SHA_1 -> SHA1Digest()
            NativeRsaOaepHash.SHA_256 -> SHA256Digest()
        }

    val privateKey = PrivateKeyFactory.createKey(privateKeyPkcs8) as RSAKeyParameters
    return OAEPEncoding(RSAEngine(), digest(), digest(), null).run {
        init(false, privateKey)
        processBlock(ciphertext, 0, ciphertext.size)
    }
}

private fun bcRsaPublicKeySpkiFromPkcs8(privateKeyPkcs8: ByteArray): ByteArray {
    val privateKey = PrivateKeyFactory.createKey(privateKeyPkcs8) as RSAPrivateCrtKeyParameters
    val publicKey = RSAKeyParameters(false, privateKey.modulus, privateKey.publicExponent)
    return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKey).encoded
}

private fun ByteArray.toChunks(chunkSize: Int): List<ByteArray> {
    val bytes = this
    return buildList {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + chunkSize)
            add(bytes.copyOfRange(offset, end))
            offset = end
        }
    }
}

private fun deterministicBytes(
    size: Int,
    seed: Int,
): ByteArray =
    ByteArray(size) { index ->
        ((seed + index * 31 + (index ushr 3) * 17) and 0xff).toByte()
    }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex fixture has an odd length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun sizeLabel(bytes: Int): String =
    when {
        bytes % (1024 * 1024) == 0 -> "${bytes / (1024 * 1024)}mib"
        bytes % 1024 == 0 -> "${bytes / 1024}kib"
        else -> "${bytes}b"
    }

private fun token(value: String?): String =
    value
        ?.trim()
        ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        ?.ifEmpty { "unknown" }
        ?: "unknown"
