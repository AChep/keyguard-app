@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public enum class NativeArgon2Mode {
    ARGON2_D,
    ARGON2_I,
    ARGON2_ID,
}

public enum class NativeArgon2Version {
    VERSION_1_0,
    VERSION_1_3,
}

public enum class NativeStreamCipherAlgorithm {
    SALSA20,
    CHACHA20,
}

public enum class NativeHashAlgorithm {
    SHA_1,
    SHA_256,
    SHA_512,
    MD5,
}

public enum class NativeRsaOaepHash {
    SHA_1,
    SHA_256,
}

public data class NativeAesCbcHmacSha256Result(
    val ciphertext: ByteArray,
    val mac: ByteArray,
)

public interface NativeAesCbcPkcs7HmacSha256EncryptSession : AutoCloseable {
    /** Encrypts and authenticates the next plaintext chunk. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Finishes encryption and returns the final ciphertext block and MAC. */
    public fun finish(): NativeAesCbcHmacSha256Result

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

/**
 * Incrementally decrypts AES-CBC ciphertext authenticated with HMAC-SHA256.
 *
 * Plaintext returned by [updateProvisional] is not authenticated. It must not be parsed,
 * displayed, executed, or permanently committed until [authenticateAndFinish] returns
 * successfully. If authentication or finalization fails, callers must discard all previously
 * returned provisional plaintext.
 */
public interface NativeAesCbcPkcs7HmacSha256DecryptSession : AutoCloseable {
    /**
     * Decrypts the next ciphertext chunk.
     *
     * The returned plaintext is provisional and attacker-controlled until
     * [authenticateAndFinish] succeeds.
     */
    public fun updateProvisional(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /**
     * Verifies the HMAC before validating the final encrypted block.
     *
     * A successful return authenticates all plaintext previously returned by
     * [updateProvisional] and returns the final plaintext bytes.
     */
    public fun authenticateAndFinish(): ByteArray

    /**
     * Releases this session without authenticating it.
     *
     * Previously returned provisional plaintext remains unauthenticated.
     */
    override fun close()
}

public object NativeCryptoPrimitives {
    public fun hkdfSha256(
        seed: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray? = null,
        length: Int = DEFAULT_KDF_OUTPUT_BYTES,
    ): ByteArray {
        require(length in 0..HKDF_SHA256_MAX_LENGTH) {
            "HKDF-SHA256 output length must be in 0..$HKDF_SHA256_MAX_LENGTH"
        }
        return NativeCrypto.call(
            operationName = "hkdf_sha256",
            operation = HkdfSha256OperationProto(
                HkdfSha256RequestProto(
                    seed = seed,
                    salt = salt,
                    info = info,
                    length = length,
                ),
            ),
        ).requireBytes("hkdf_sha256")
            .requireNativeCryptoOutputSize("hkdf_sha256", length)
    }

    public fun pbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int = 1,
        length: Int = DEFAULT_KDF_OUTPUT_BYTES,
    ): ByteArray {
        require(iterations > 0) { "PBKDF2 iterations must be positive" }
        require(length >= 0) { "PBKDF2 output length must not be negative" }
        return NativeCrypto.call(
            operationName = "pbkdf2_sha256",
            operation = Pbkdf2Sha256OperationProto(
                Pbkdf2Sha256RequestProto(
                    seed = seed,
                    salt = salt,
                    iterations = iterations,
                    length = length,
                ),
            ),
        ).requireBytes("pbkdf2_sha256")
            .requireNativeCryptoOutputSize("pbkdf2_sha256", length)
    }

    public fun argon2(
        mode: NativeArgon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        length: Int = DEFAULT_KDF_OUTPUT_BYTES,
        version: NativeArgon2Version = NativeArgon2Version.VERSION_1_3,
        secret: ByteArray? = null,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(iterations > 0) { "Argon2 iterations must be positive" }
        require(memoryKb >= 0) { "Argon2 memory must not be negative" }
        require(parallelism > 0) { "Argon2 parallelism must be positive" }
        require(length >= ARGON2_MIN_OUTPUT_LENGTH) {
            "Argon2 output length must be at least $ARGON2_MIN_OUTPUT_LENGTH bytes"
        }
        return NativeCrypto.call(
            operationName = "argon2",
            operation = Argon2OperationProto(
                Argon2RequestProto(
                    mode = when (mode) {
                        NativeArgon2Mode.ARGON2_D -> Argon2ModeProto.D
                        NativeArgon2Mode.ARGON2_I -> Argon2ModeProto.I
                        NativeArgon2Mode.ARGON2_ID -> Argon2ModeProto.ID
                    },
                    seed = seed,
                    salt = salt,
                    iterations = iterations,
                    memoryKib = memoryKb,
                    parallelism = parallelism,
                    length = length,
                    version = when (version) {
                        NativeArgon2Version.VERSION_1_0 -> 0x10
                        NativeArgon2Version.VERSION_1_3 -> 0
                    },
                    secret = secret,
                    associatedData = associatedData,
                ),
            ),
        ).requireBytes("argon2")
            .requireNativeCryptoOutputSize("argon2", length)
    }

    public fun randomBytes(length: Int): ByteArray {
        require(length >= 0) { "Random byte count must not be negative" }
        if (length <= NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            return requestRandomBytes(length)
        }

        val result = ByteArray(length)
        return try {
            var offset = 0
            while (offset < result.size) {
                val chunkLength = minOf(NATIVE_CRYPTO_INLINE_DATA_BYTES, result.size - offset)
                val chunk = requestRandomBytes(chunkLength)
                try {
                    chunk.copyInto(result, destinationOffset = offset)
                } finally {
                    chunk.fill(0)
                }
                offset += chunkLength
            }
            result
        } catch (failure: Throwable) {
            result.fill(0)
            throw failure
        }
    }

    private fun requestRandomBytes(length: Int): ByteArray {
        val result = NativeCrypto.call(
            operationName = "random_bytes",
            operation = RandomBytesOperationProto(RandomBytesRequestProto(length)),
        ).requireBytes("random_bytes")
        if (result.size != length) {
            result.fill(0)
            throw NativeCryptoException(
                operation = "random_bytes",
                code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
            )
        }
        return result
    }

    public fun randomInt(): Int = NativeCrypto.randomInt(exclusiveUpperBound = 0)

    public fun randomInt(until: Int): Int {
        require(until > 0) { "Random upper bound must be positive" }
        return NativeCrypto.randomInt(exclusiveUpperBound = until)
    }

    public fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: NativeHashAlgorithm,
    ): ByteArray {
        val proto = algorithm.toProto()
        val expectedOutputSize = algorithm.outputSizeBytes()
        if (shouldUseOneShotHmac(key.size, data.size)) {
            return NativeCrypto.call(
                operationName = "hmac",
                operation = HmacOperationProto(
                    HmacRequestProto(
                        algorithm = proto,
                        key = key,
                        data = data,
                    ),
                ),
            ).requireBytes("hmac")
                .requireNativeCryptoOutputSize("hmac", expectedOutputSize)
        }

        val streamKey = if (key.size > NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            digest(algorithm, key)
        } else {
            key
        }
        return try {
            collectNativeStreamToExpectedSize(
                session = NativeCrypto.openHmac(proto, streamKey),
                input = data,
                expectedOutputSize = expectedOutputSize,
                operation = "hmac",
            )
        } finally {
            if (streamKey !== key) streamKey.fill(0)
        }
    }

    public fun createHmac(
        key: ByteArray,
        algorithm: NativeHashAlgorithm,
    ): NativeCryptoSession {
        val streamKey = if (key.size > NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            digest(algorithm, key)
        } else {
            key
        }
        return try {
            NativeCrypto.openHmac(algorithm.toProto(), streamKey)
                .withExpectedFinalOutputSize(
                    operation = "hmac.stream_finish",
                    expectedSize = algorithm.outputSizeBytes(),
                )
        } finally {
            if (streamKey !== key) streamKey.fill(0)
        }
    }

    public fun createHmacSha256(key: ByteArray): NativeCryptoSession =
        createHmac(key, NativeHashAlgorithm.SHA_256)

    public fun sha1(data: ByteArray): ByteArray = digest(NativeHashAlgorithm.SHA_1, data)

    public fun sha256(data: ByteArray): ByteArray = digest(NativeHashAlgorithm.SHA_256, data)

    public fun sha512(data: ByteArray): ByteArray = digest(NativeHashAlgorithm.SHA_512, data)

    public fun md5(data: ByteArray): ByteArray = digest(NativeHashAlgorithm.MD5, data)

    public fun aesEcbNoPaddingEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        requireValidAesKey(key)
        require(data.size % AES_BLOCK_BYTES == 0) {
            "AES-ECB input must contain complete blocks"
        }
        return processAesEcbChunks(
            operationName = "aes_ecb_no_padding_encrypt",
            data = data,
        ) { chunk ->
            NativeCrypto.call(
                operationName = "aes_ecb_no_padding_encrypt",
                operation = AesEcbNoPaddingEncryptOperationProto(
                    AesEcbNoPaddingEncryptRequestProto(key = key, data = chunk),
                ),
            ).requireBytes("aes_ecb_no_padding_encrypt")
        }
    }

    /** Applies AES-ECB encryption repeatedly without crossing the native boundary per round. */
    public fun aesEcbNoPaddingTransform(
        key: ByteArray,
        data: ByteArray,
        rounds: Int,
    ): ByteArray {
        require(rounds >= 0) { "AES-ECB transform rounds must not be negative" }
        if (rounds > MAX_AES_TRANSFORM_ROUNDS) {
            throw NativeCryptoException(
                operation = "aes_ecb_no_padding_transform",
                code = NativeCryptoErrorCode.RESOURCE_LIMIT,
            )
        }
        // Zero rounds is an identity transform regardless of key and data shape.
        if (rounds == 0) {
            return data.copyOf()
        }
        requireValidAesKey(key)
        require(data.size % AES_BLOCK_BYTES == 0) {
            "AES-ECB transform input must contain complete blocks"
        }
        val work = rounds.toLong() * (data.size / AES_BLOCK_BYTES)
        if (work > MAX_AES_TRANSFORM_BLOCK_ROUNDS) {
            throw NativeCryptoException(
                operation = "aes_ecb_no_padding_transform",
                code = NativeCryptoErrorCode.RESOURCE_LIMIT,
            )
        }

        return processAesEcbChunks(
            operationName = "aes_ecb_no_padding_transform",
            data = data,
        ) { chunk ->
            NativeCrypto.call(
                operationName = "aes_ecb_no_padding_transform",
                operation = AesEcbNoPaddingTransformOperationProto(
                    AesEcbNoPaddingTransformRequestProto(
                        key = key,
                        data = chunk,
                        rounds = rounds,
                    ),
                ),
            ).requireBytes("aes_ecb_no_padding_transform")
        }
    }

    public fun aesCbcPkcs7Encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = aesCbcPkcs7(
        direction = CipherDirectionProto.ENCRYPT,
        key = key,
        iv = iv,
        data = data,
    )

    public fun aesCbcPkcs7Decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = aesCbcPkcs7(
        direction = CipherDirectionProto.DECRYPT,
        key = key,
        iv = iv,
        data = data,
    )

    /**
     * Encrypts and authenticates a Bitwarden-compatible `iv || ciphertext` frame in one native
     * call, using caller-owned fixed-shape output buffers at the platform boundary.
     */
    public fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
    ): NativeAesCbcHmacSha256Result {
        requireValidAesKey(encryptionKey)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        val ciphertext = ByteArray(aesCbcHmacEncryptedSize(plaintext.size))
        val mac = ByteArray(HMAC_SHA256_BYTES)
        return try {
            NativeCrypto.aesCbcPkcs7HmacSha256EncryptFast(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                plaintext = plaintext,
                ciphertextOutput = ciphertext,
                macOutput = mac,
            )
            NativeAesCbcHmacSha256Result(ciphertext = ciphertext, mac = mac)
        } catch (failure: Throwable) {
            ciphertext.fill(0)
            mac.fill(0)
            throw failure
        }
    }

    /**
     * Authenticates `iv || ciphertext` before validating or decrypting the CBC payload.
     *
     * The ciphertext shape is deliberately not validated here: the MAC check must run first so
     * that malformed attacker-supplied ciphertext fails with [NativeCryptoErrorCode.AUTHENTICATION_FAILED].
     */
    public fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
    ): ByteArray {
        requireValidAesKey(encryptionKey)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        val plaintextOutput = ByteArray(ciphertext.size)
        return try {
            val plaintextLength = NativeCrypto.aesCbcPkcs7HmacSha256DecryptFast(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                ciphertext = ciphertext,
                expectedMac = expectedMac,
                plaintextOutput = plaintextOutput,
            )
            if (plaintextLength >= ciphertext.size) {
                throw NativeCryptoException(
                    operation = AES_CBC_HMAC_SHA256_DECRYPT_OPERATION,
                    code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
                )
            }
            plaintextOutput.copyOf(plaintextLength)
        } finally {
            plaintextOutput.fill(0)
        }
    }

    internal fun aesCbcPkcs7HmacSha256EncryptViaProtobuf(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
    ): NativeAesCbcHmacSha256Result {
        requireValidAesKey(encryptionKey)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        val expectedCiphertextSize = aesCbcHmacEncryptedSize(plaintext.size)
        val encodedResult = NativeCrypto.call(
            operationName = AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION,
            operation = AesCbcPkcs7HmacSha256EncryptOperationProto(
                AesCbcPkcs7HmacSha256EncryptRequestProto(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    plaintext = plaintext,
                ),
            ),
        ).requireBytes(AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION)
        return try {
            val result = decodeFusedEncryptionResult(encodedResult)
            if (result.ciphertext.size != expectedCiphertextSize || result.mac.size != HMAC_SHA256_BYTES) {
                result.ciphertext.fill(0)
                result.mac.fill(0)
                throw NativeCryptoException(
                    operation = AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION,
                    code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
                )
            }
            NativeAesCbcHmacSha256Result(
                ciphertext = result.ciphertext,
                mac = result.mac,
            )
        } finally {
            encodedResult.fill(0)
        }
    }

    internal fun aesCbcPkcs7HmacSha256DecryptViaProtobuf(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
    ): ByteArray {
        requireValidAesKey(encryptionKey)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        val plaintext = NativeCrypto.call(
            operationName = AES_CBC_HMAC_SHA256_DECRYPT_OPERATION,
            operation = AesCbcPkcs7HmacSha256DecryptOperationProto(
                AesCbcPkcs7HmacSha256DecryptRequestProto(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    ciphertext = ciphertext,
                    expectedMac = expectedMac,
                ),
            ),
        ).requireBytes(AES_CBC_HMAC_SHA256_DECRYPT_OPERATION)
        if (plaintext.size >= ciphertext.size) {
            plaintext.fill(0)
            throw NativeCryptoException(
                operation = AES_CBC_HMAC_SHA256_DECRYPT_OPERATION,
                code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
            )
        }
        return plaintext
    }

    public fun createAesCbcPkcs7Encryptor(
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = openAesCbcPkcs7(
        direction = CipherDirectionProto.ENCRYPT,
        key = key,
        iv = iv,
    )

    public fun createAesCbcPkcs7Decryptor(
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = openAesCbcPkcs7(
        direction = CipherDirectionProto.DECRYPT,
        key = key,
        iv = iv,
    )

    public fun createTwofishCbcPkcs7Encryptor(
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = openTwofishCbcPkcs7(
        direction = CipherDirectionProto.ENCRYPT,
        key = key,
        iv = iv,
    )

    public fun createTwofishCbcPkcs7Decryptor(
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = openTwofishCbcPkcs7(
        direction = CipherDirectionProto.DECRYPT,
        key = key,
        iv = iv,
    )

    public fun createAesCbcPkcs7HmacSha256Encryptor(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
    ): NativeAesCbcPkcs7HmacSha256EncryptSession {
        requireValidAesKey(encryptionKey)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        return NativeAesCbcPkcs7HmacSha256EncryptSessionImpl(
            delegate = NativeCrypto.openAesCbcPkcs7HmacSha256Encrypt(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
            ),
        )
    }

    public fun createAesCbcPkcs7HmacSha256Decryptor(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        expectedMac: ByteArray,
    ): NativeAesCbcPkcs7HmacSha256DecryptSession {
        requireValidAesKey(encryptionKey)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        return NativeAesCbcPkcs7HmacSha256DecryptSessionImpl(
            delegate = NativeCrypto.openAesCbcPkcs7HmacSha256Decrypt(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                expectedMac = expectedMac,
            ),
        )
    }

    public fun rsaOaepDecrypt(
        privateKeyPkcs8: ByteArray,
        ciphertext: ByteArray,
        hash: NativeRsaOaepHash,
    ): ByteArray {
        require(privateKeyPkcs8.isNotEmpty()) { "RSA private key must not be empty" }
        return NativeCrypto.call(
            operationName = "rsa_oaep_decrypt",
            operation = RsaOaepDecryptOperationProto(
                RsaOaepDecryptRequestProto(
                    hash = hash.toProto(),
                    privateKeyPkcs8 = privateKeyPkcs8,
                    ciphertext = ciphertext,
                ),
            ),
        ).requireBytes("rsa_oaep_decrypt")
    }

    public fun rsaOaepEncrypt(
        publicKeySpki: ByteArray,
        plaintext: ByteArray,
        hash: NativeRsaOaepHash,
    ): ByteArray {
        require(publicKeySpki.isNotEmpty()) { "RSA public key must not be empty" }
        return NativeCrypto.call(
            operationName = "rsa_oaep_encrypt",
            operation = RsaOaepEncryptOperationProto(
                RsaOaepEncryptRequestProto(
                    hash = hash.toProto(),
                    publicKeySpki = publicKeySpki,
                    plaintext = plaintext,
                ),
            ),
        ).requireBytes("rsa_oaep_encrypt")
    }

    public fun rsaPublicKeySpkiFromPkcs8(
        privateKeyPkcs8: ByteArray,
    ): ByteArray {
        require(privateKeyPkcs8.isNotEmpty()) { "RSA private key must not be empty" }
        return NativeCrypto.call(
            operationName = "rsa_pkcs8_to_spki",
            operation = RsaPkcs8ToSpkiOperationProto(
                RsaPkcs8ToSpkiRequestProto(
                    privateKeyPkcs8 = privateKeyPkcs8,
                ),
            ),
        ).requireBytes("rsa_pkcs8_to_spki")
    }

    public fun sshAgentTcpChaCha20Poly1305Encrypt(
        key: ByteArray,
        nonce: ByteArray,
        header: ByteArray,
        payload: ByteArray,
    ): ByteArray = sshAgentTcpChaCha20Poly1305(
        direction = CipherDirectionProto.ENCRYPT,
        key = key,
        nonce = nonce,
        header = header,
        payload = payload,
    )

    public fun sshAgentTcpChaCha20Poly1305Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        header: ByteArray,
        payload: ByteArray,
    ): ByteArray = sshAgentTcpChaCha20Poly1305(
        direction = CipherDirectionProto.DECRYPT,
        key = key,
        nonce = nonce,
        header = header,
        payload = payload,
    )

    public fun streamCipherXorAtOffset(
        algorithm: NativeStreamCipherAlgorithm,
        key: ByteArray,
        nonce: ByteArray,
        offset: Long,
        data: ByteArray,
    ): ByteArray {
        require(key.size == STREAM_CIPHER_KEY_BYTES) { "Stream-cipher key must contain 32 bytes" }
        val expectedNonceSize = when (algorithm) {
            NativeStreamCipherAlgorithm.SALSA20 -> SALSA20_NONCE_BYTES
            NativeStreamCipherAlgorithm.CHACHA20 -> CHACHA20_NONCE_BYTES
        }
        require(nonce.size == expectedNonceSize) {
            "${algorithm.name} nonce must contain $expectedNonceSize bytes"
        }
        require(offset >= 0) { "Stream-cipher offset must not be negative" }
        require(offset <= Long.MAX_VALUE - data.size) { "Stream-cipher range is too large" }

        if (data.size <= NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            return requestStreamCipherXorAtOffset(algorithm, key, nonce, offset, data)
        }

        val result = ByteArray(data.size)
        return try {
            var dataOffset = 0
            while (dataOffset < data.size) {
                val chunkLength = minOf(NATIVE_CRYPTO_INLINE_DATA_BYTES, data.size - dataOffset)
                val inputChunk = data.copyOfRange(dataOffset, dataOffset + chunkLength)
                val outputChunk = try {
                    requestStreamCipherXorAtOffset(
                        algorithm = algorithm,
                        key = key,
                        nonce = nonce,
                        offset = offset + dataOffset,
                        data = inputChunk,
                    )
                } finally {
                    inputChunk.fill(0)
                }
                try {
                    if (outputChunk.size != chunkLength) {
                        throw NativeCryptoException(
                            operation = STREAM_CIPHER_OPERATION,
                            code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
                        )
                    }
                    outputChunk.copyInto(result, destinationOffset = dataOffset)
                } finally {
                    outputChunk.fill(0)
                }
                dataOffset += chunkLength
            }
            result
        } catch (failure: Throwable) {
            result.fill(0)
            throw failure
        }
    }

    public fun twofishCbcPkcs7Encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = twofishCbcPkcs7(CipherDirectionProto.ENCRYPT, key, iv, data)

    public fun twofishCbcPkcs7Decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = twofishCbcPkcs7(CipherDirectionProto.DECRYPT, key, iv, data)

    private fun digest(
        algorithm: NativeHashAlgorithm,
        data: ByteArray,
    ): ByteArray {
        val proto = algorithm.toProto()
        val expectedOutputSize = algorithm.outputSizeBytes()
        return if (data.size <= NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            NativeCrypto.call(
                operationName = "digest",
                operation = DigestOperationProto(
                    DigestRequestProto(algorithm = proto, data = data),
                ),
            ).requireBytes("digest")
                .requireNativeCryptoOutputSize("digest", expectedOutputSize)
        } else {
            collectNativeStreamToExpectedSize(
                session = NativeCrypto.openDigest(proto),
                input = data,
                expectedOutputSize = expectedOutputSize,
                operation = "digest",
            )
        }
    }

    private fun requestStreamCipherXorAtOffset(
        algorithm: NativeStreamCipherAlgorithm,
        key: ByteArray,
        nonce: ByteArray,
        offset: Long,
        data: ByteArray,
    ): ByteArray {
        val output = NativeCrypto.call(
            operationName = STREAM_CIPHER_OPERATION,
            operation = StreamCipherXorAtOffsetOperationProto(
                StreamCipherXorAtOffsetRequestProto(
                    algorithm = when (algorithm) {
                        NativeStreamCipherAlgorithm.SALSA20 -> StreamCipherAlgorithmProto.SALSA20
                        NativeStreamCipherAlgorithm.CHACHA20 -> StreamCipherAlgorithmProto.CHACHA20
                    },
                    key = key,
                    nonce = nonce,
                    offset = offset,
                    data = data,
                ),
            ),
        ).requireBytes(STREAM_CIPHER_OPERATION)
        if (output.size != data.size) {
            output.fill(0)
            throw NativeCryptoException(
                operation = STREAM_CIPHER_OPERATION,
                code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
            )
        }
        return output
    }

    private fun sshAgentTcpChaCha20Poly1305(
        direction: CipherDirectionProto,
        key: ByteArray,
        nonce: ByteArray,
        header: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        require(key.size == CHACHA20_POLY1305_KEY_BYTES) {
            "SSH agent transport key must contain $CHACHA20_POLY1305_KEY_BYTES bytes"
        }
        require(nonce.size == CHACHA20_POLY1305_NONCE_BYTES) {
            "SSH agent transport nonce must contain $CHACHA20_POLY1305_NONCE_BYTES bytes"
        }
        require(header.size == SSH_AGENT_TCP_HEADER_BYTES) {
            "SSH agent transport header must contain $SSH_AGENT_TCP_HEADER_BYTES bytes"
        }
        when (direction) {
            CipherDirectionProto.ENCRYPT -> {
                if (payload.size > SSH_AGENT_TCP_MAX_PLAINTEXT_BYTES) {
                    throw NativeCryptoException(
                        operation = SSH_AGENT_TCP_CHACHA20_POLY1305_OPERATION,
                        code = NativeCryptoErrorCode.RESOURCE_LIMIT,
                    )
                }
            }

            CipherDirectionProto.DECRYPT -> {
                if (payload.size > SSH_AGENT_TCP_MAX_CIPHERTEXT_BYTES) {
                    throw NativeCryptoException(
                        operation = SSH_AGENT_TCP_CHACHA20_POLY1305_OPERATION,
                        code = NativeCryptoErrorCode.RESOURCE_LIMIT,
                    )
                }
                require(payload.size >= CHACHA20_POLY1305_TAG_BYTES) {
                    "SSH agent transport ciphertext must contain a tag"
                }
            }

            CipherDirectionProto.UNSPECIFIED -> {
                error("Cipher direction must be specified")
            }
        }

        val output = NativeCrypto.call(
            operationName = SSH_AGENT_TCP_CHACHA20_POLY1305_OPERATION,
            operation = SshAgentTcpChaCha20Poly1305OperationProto(
                SshAgentTcpChaCha20Poly1305RequestProto(
                    direction = direction,
                    key = key,
                    nonce = nonce,
                    header = header,
                    payload = payload,
                ),
            ),
        ).requireBytes(SSH_AGENT_TCP_CHACHA20_POLY1305_OPERATION)
        val expectedOutputSize = when (direction) {
            CipherDirectionProto.ENCRYPT -> payload.size + CHACHA20_POLY1305_TAG_BYTES
            CipherDirectionProto.DECRYPT -> payload.size - CHACHA20_POLY1305_TAG_BYTES
            CipherDirectionProto.UNSPECIFIED -> error("Cipher direction must be specified")
        }
        if (output.size != expectedOutputSize) {
            output.fill(0)
            throw NativeCryptoException(
                operation = SSH_AGENT_TCP_CHACHA20_POLY1305_OPERATION,
                code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
            )
        }
        return output
    }

    private fun aesCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        requireValidAesKey(key)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        if (direction == CipherDirectionProto.DECRYPT) {
            require(data.isNotEmpty() && data.size % AES_BLOCK_BYTES == 0) {
                "AES-CBC ciphertext must contain complete blocks"
            }
        }
        val output = if (data.size <= NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            NativeCrypto.call(
                operationName = AES_CBC_OPERATION,
                operation = AesCbcPkcs7OperationProto(
                    AesCbcPkcs7RequestProto(
                        direction = direction,
                        key = key,
                        iv = iv,
                        data = data,
                    ),
                ),
            ).requireBytes(AES_CBC_OPERATION)
        } else if (direction == CipherDirectionProto.ENCRYPT) {
            collectNativeStreamToExpectedSize(
                session = NativeCrypto.openAesCbcPkcs7(direction, key, iv),
                input = data,
                expectedOutputSize = aesCbcEncryptedSize(data.size),
                operation = AES_CBC_OPERATION,
            )
        } else {
            collectNativeStream(
                session = NativeCrypto.openAesCbcPkcs7(direction, key, iv),
                input = data,
            )
        }
        return output.requireNativeCryptoCbcOutputShape(
            operation = AES_CBC_OPERATION,
            direction = direction,
            inputSize = data.size,
            blockSize = AES_BLOCK_BYTES,
        )
    }

    private fun openAesCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession {
        requireValidAesKey(key)
        require(iv.size == AES_BLOCK_BYTES) { "AES-CBC IV must be 16 bytes" }
        return NativeCrypto.openAesCbcPkcs7(direction, key, iv)
    }

    private fun openTwofishCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession {
        requireValidBlockCipherKey("Twofish", key)
        require(iv.size == AES_BLOCK_BYTES) { "Twofish-CBC IV must be 16 bytes" }
        return NativeCrypto.openTwofishCbcPkcs7(direction, key, iv)
    }

    private fun NativeRsaOaepHash.toProto(): RsaOaepHashProto = when (this) {
        NativeRsaOaepHash.SHA_1 -> RsaOaepHashProto.SHA1
        NativeRsaOaepHash.SHA_256 -> RsaOaepHashProto.SHA256
    }

    private fun twofishCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        requireValidBlockCipherKey("Twofish", key)
        require(iv.size == AES_BLOCK_BYTES) { "Twofish-CBC IV must be 16 bytes" }
        if (direction == CipherDirectionProto.DECRYPT) {
            require(data.isNotEmpty() && data.size % AES_BLOCK_BYTES == 0) {
                "Twofish-CBC ciphertext must contain complete blocks"
            }
        }
        val output = if (data.size <= NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            NativeCrypto.call(
                operationName = TWOFISH_OPERATION,
                operation = TwofishCbcPkcs7OperationProto(
                    TwofishCbcPkcs7RequestProto(
                        direction = direction,
                        key = key,
                        iv = iv,
                        data = data,
                    ),
                ),
            ).requireBytes(TWOFISH_OPERATION)
        } else if (direction == CipherDirectionProto.ENCRYPT) {
            collectNativeStreamToExpectedSize(
                session = NativeCrypto.openTwofishCbcPkcs7(direction, key, iv),
                input = data,
                expectedOutputSize = cbcEncryptedSize(data.size, TWOFISH_OPERATION),
                operation = TWOFISH_OPERATION,
            )
        } else {
            collectNativeStream(
                session = NativeCrypto.openTwofishCbcPkcs7(direction, key, iv),
                input = data,
            )
        }
        return output.requireNativeCryptoCbcOutputShape(
            operation = TWOFISH_OPERATION,
            direction = direction,
            inputSize = data.size,
            blockSize = AES_BLOCK_BYTES,
        )
    }

    private fun processAesEcbChunks(
        operationName: String,
        data: ByteArray,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray {
        if (data.size <= NATIVE_CRYPTO_INLINE_DATA_BYTES) {
            val output = transform(data)
            if (output.size != data.size) {
                output.fill(0)
                throw NativeCryptoException(
                    operation = operationName,
                    code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
                )
            }
            return output
        }

        val result = ByteArray(data.size)
        return try {
            var offset = 0
            while (offset < data.size) {
                val chunkLength = minOf(NATIVE_CRYPTO_INLINE_DATA_BYTES, data.size - offset)
                val inputChunk = data.copyOfRange(offset, offset + chunkLength)
                val outputChunk = try {
                    transform(inputChunk)
                } finally {
                    inputChunk.fill(0)
                }
                try {
                    if (outputChunk.size != chunkLength) {
                        throw NativeCryptoException(
                            operation = operationName,
                            code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
                        )
                    }
                    outputChunk.copyInto(result, destinationOffset = offset)
                } finally {
                    outputChunk.fill(0)
                }
                offset += chunkLength
            }
            result
        } catch (failure: Throwable) {
            result.fill(0)
            throw failure
        }
    }

    private fun requireValidAesKey(key: ByteArray) {
        requireValidBlockCipherKey("AES", key)
    }

    private fun requireValidBlockCipherKey(algorithm: String, key: ByteArray) {
        require(
            key.size == BLOCK_CIPHER_128_KEY_BYTES ||
                key.size == BLOCK_CIPHER_192_KEY_BYTES ||
                key.size == BLOCK_CIPHER_256_KEY_BYTES,
        ) { "$algorithm key must contain 16, 24, or 32 bytes" }
    }

    private fun aesCbcEncryptedSize(inputSize: Int): Int =
        cbcEncryptedSize(inputSize, AES_CBC_OPERATION)

    private fun aesCbcHmacEncryptedSize(inputSize: Int): Int =
        cbcEncryptedSize(inputSize, AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION)

    private fun decodeFusedEncryptionResult(
        encoded: ByteArray,
    ): AesCbcPkcs7HmacSha256EncryptResultProto = try {
        ProtoBuf.decodeFromByteArray(encoded)
    } catch (_: SerializationException) {
        throw NativeCryptoException(
            operation = AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION,
            code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
        )
    } catch (_: IllegalArgumentException) {
        throw NativeCryptoException(
            operation = AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION,
            code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
        )
    }

    private fun cbcEncryptedSize(inputSize: Int, operation: String): Int {
        val outputSize = (inputSize.toLong() / AES_BLOCK_BYTES + 1L) * AES_BLOCK_BYTES
        if (outputSize > Int.MAX_VALUE) {
            throw NativeCryptoException(
                operation = operation,
                code = NativeCryptoErrorCode.RESOURCE_LIMIT,
            )
        }
        return outputSize.toInt()
    }

    private const val HKDF_SHA256_MAX_LENGTH: Int = 255 * 32
    private const val ARGON2_MIN_OUTPUT_LENGTH: Int = 4
    private const val DEFAULT_KDF_OUTPUT_BYTES: Int = 32
    private const val AES_BLOCK_BYTES: Int = 16
    private const val BLOCK_CIPHER_128_KEY_BYTES: Int = 16
    private const val BLOCK_CIPHER_192_KEY_BYTES: Int = 24
    private const val BLOCK_CIPHER_256_KEY_BYTES: Int = 32
    private const val STREAM_CIPHER_KEY_BYTES: Int = 32
    private const val SALSA20_NONCE_BYTES: Int = 8
    private const val CHACHA20_NONCE_BYTES: Int = 12
    private const val HMAC_SHA256_BYTES: Int = 32
    private const val CHACHA20_POLY1305_KEY_BYTES: Int = 32
    private const val CHACHA20_POLY1305_NONCE_BYTES: Int = 12
    private const val CHACHA20_POLY1305_TAG_BYTES: Int = 16
    private const val SSH_AGENT_TCP_HEADER_BYTES: Int = 18
    private const val SSH_AGENT_TCP_MAX_PLAINTEXT_BYTES: Int = 1024 * 1024
    private const val SSH_AGENT_TCP_MAX_CIPHERTEXT_BYTES: Int =
        SSH_AGENT_TCP_MAX_PLAINTEXT_BYTES + CHACHA20_POLY1305_TAG_BYTES
    private const val MAX_AES_TRANSFORM_ROUNDS: Int = 100_000_000
    private const val MAX_AES_TRANSFORM_BLOCK_ROUNDS: Long = 200_000_000L
    private const val AES_CBC_OPERATION: String = "aes_cbc_pkcs7"
    private const val STREAM_CIPHER_OPERATION: String = "stream_cipher_xor_at_offset"
    private const val TWOFISH_OPERATION: String = "twofish_cbc_pkcs7"
    private const val SSH_AGENT_TCP_CHACHA20_POLY1305_OPERATION: String =
        "ssh_agent_tcp_chacha20_poly1305"
    private const val AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION: String =
        "aes_cbc_pkcs7_hmac_sha256_encrypt"
    private const val AES_CBC_HMAC_SHA256_DECRYPT_OPERATION: String =
        "aes_cbc_pkcs7_hmac_sha256_decrypt"

    private class NativeAesCbcPkcs7HmacSha256EncryptSessionImpl(
        private val delegate: NativeCryptoSession,
    ) : NativeAesCbcPkcs7HmacSha256EncryptSession {
        override fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ): ByteArray = delegate.update(data, offset, length)

        override fun finish(): NativeAesCbcHmacSha256Result {
            val encodedResult = delegate.finish()
            return try {
                val result = NativeCryptoPrimitives.decodeFusedEncryptionResult(encodedResult)
                if (
                    result.ciphertext.size != NativeCryptoPrimitives.AES_BLOCK_BYTES ||
                    result.mac.size != NativeCryptoPrimitives.HMAC_SHA256_BYTES
                ) {
                    result.ciphertext.fill(0)
                    result.mac.fill(0)
                    throw NativeCryptoException(
                        operation = NativeCryptoPrimitives.AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION,
                        code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
                    )
                }
                NativeAesCbcHmacSha256Result(
                    ciphertext = result.ciphertext,
                    mac = result.mac,
                )
            } finally {
                encodedResult.fill(0)
            }
        }

        override fun close() {
            delegate.close()
        }
    }

    private class NativeAesCbcPkcs7HmacSha256DecryptSessionImpl(
        private val delegate: NativeCryptoSession,
    ) : NativeAesCbcPkcs7HmacSha256DecryptSession {
        override fun updateProvisional(
            data: ByteArray,
            offset: Int,
            length: Int,
        ): ByteArray = delegate.update(data, offset, length)

        override fun authenticateAndFinish(): ByteArray = delegate.finish()

        override fun close() {
            delegate.close()
        }
    }
}

internal fun ByteArray.requireNativeCryptoCbcOutputShape(
    operation: String,
    direction: CipherDirectionProto,
    inputSize: Int,
    blockSize: Int,
): ByteArray {
    require(inputSize >= 0) { "CBC input size must not be negative" }
    require(blockSize > 0) { "CBC block size must be positive" }
    val valid = when (direction) {
        CipherDirectionProto.ENCRYPT -> {
            val expectedSize = (inputSize.toLong() / blockSize + 1L) * blockSize
            size.toLong() == expectedSize
        }

        CipherDirectionProto.DECRYPT -> size < inputSize
        CipherDirectionProto.UNSPECIFIED -> false
    }
    if (!valid) {
        fill(0)
        throw NativeCryptoException(
            operation = operation,
            code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
        )
    }
    return this
}

internal fun shouldUseOneShotHmac(
    keySize: Int,
    dataSize: Int,
): Boolean = keySize.toLong() + dataSize <= NATIVE_CRYPTO_HMAC_ONE_SHOT_MAX_BYTES

private fun NativeHashAlgorithm.toProto(): HashAlgorithmProto = when (this) {
    NativeHashAlgorithm.SHA_1 -> HashAlgorithmProto.SHA1
    NativeHashAlgorithm.SHA_256 -> HashAlgorithmProto.SHA256
    NativeHashAlgorithm.SHA_512 -> HashAlgorithmProto.SHA512
    NativeHashAlgorithm.MD5 -> HashAlgorithmProto.MD5
}

private const val SHA1_DIGEST_BYTES: Int = 20
private const val SHA256_DIGEST_BYTES: Int = 32
private const val SHA512_DIGEST_BYTES: Int = 64
private const val MD5_DIGEST_BYTES: Int = 16

private fun NativeHashAlgorithm.outputSizeBytes(): Int = when (this) {
    NativeHashAlgorithm.SHA_1 -> SHA1_DIGEST_BYTES
    NativeHashAlgorithm.SHA_256 -> SHA256_DIGEST_BYTES
    NativeHashAlgorithm.SHA_512 -> SHA512_DIGEST_BYTES
    NativeHashAlgorithm.MD5 -> MD5_DIGEST_BYTES
}
