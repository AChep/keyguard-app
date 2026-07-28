@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public enum class NativeCryptoCapability(
    internal val bit: Long,
) {
    HKDF(bit = 1L shl 0),
    PBKDF2(bit = 1L shl 1),
    ARGON2(bit = 1L shl 2),
    RNG(bit = 1L shl 3),
    HMAC(bit = 1L shl 4),
    DIGEST(bit = 1L shl 5),
    AES(bit = 1L shl 6),
    STREAMING(bit = 1L shl 7),
    KDBX_ARGON2(bit = 1L shl 8),
    STREAM_CIPHER_XOR_AT_OFFSET(bit = 1L shl 9),
    TWOFISH_CBC_PKCS7(bit = 1L shl 10),
    RSA_OAEP(bit = 1L shl 11),
    RSA_KEY_FORMATS(bit = 1L shl 12),
    SSH_AGENT_TCP_CHACHA20_POLY1305(bit = 1L shl 13),
    SSH_KEYS(bit = 1L shl 14),
    SSH_AGENT_SIGNING(bit = 1L shl 15),
    SSH_PRIVATE_KEY_IMPORT(bit = 1L shl 16),
    OPENPGP_READ(bit = 1L shl 17),
    OPENPGP_WRITE(bit = 1L shl 18),
    OPENPGP_MUTATION_AGENT(bit = 1L shl 19),
    AES_CBC_HMAC_SHA256(bit = 1L shl 20),
    AES_CBC_HMAC_SHA256_FAST_PATH(bit = 1L shl 21),
    AES_CBC_HMAC_SHA256_STREAMING(bit = 1L shl 22),
    RNG_FAST_PATH(bit = 1L shl 23),
}

public object NativeCrypto {
    public const val EXPECTED_ABI_VERSION: Int = 1
    public const val PROTOCOL_VERSION: Int = 1
    public const val MAX_CONTROL_ENVELOPE_BYTES: Int = 16 * 1024 * 1024

    private val client: NativeCryptoClient by lazy {
        NativeCryptoClient(NativeCryptoPlatform)
    }

    public val primitives: NativeCryptoPrimitives
        get() = NativeCryptoPrimitives

    public val ssh: NativeCryptoSsh
        get() = NativeCryptoSsh

    public val openPgp: NativeCryptoOpenPgp
        get() = NativeCryptoOpenPgp

    public val abiVersion: Int
        get() = client.abiVersion

    public val capabilities: Set<NativeCryptoCapability>
        get() = client.capabilities

    /** Eagerly loads the native library and verifies its ABI and required capabilities. */
    public fun ensureReady() {
        client.ensureReady()
    }

    internal fun call(
        operationName: String,
        operation: NativeRequestOperationProto,
    ): NativeResponseResultProto = client.call(operationName, operation)

    internal fun callInt32(
        operationName: String,
        operation: NativeRequestOperationProto,
    ): Int = client.callInt32(operationName, operation)

    internal fun randomInt(exclusiveUpperBound: Int): Int =
        client.randomInt(exclusiveUpperBound)

    internal fun aesCbcPkcs7HmacSha256EncryptFast(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Int = client.aesCbcPkcs7HmacSha256EncryptFast(
        encryptionKey = encryptionKey,
        macKey = macKey,
        iv = iv,
        plaintext = plaintext,
        ciphertextOutput = ciphertextOutput,
        macOutput = macOutput,
    )

    internal fun aesCbcPkcs7HmacSha256DecryptFast(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Int = client.aesCbcPkcs7HmacSha256DecryptFast(
        encryptionKey = encryptionKey,
        macKey = macKey,
        iv = iv,
        ciphertext = ciphertext,
        expectedMac = expectedMac,
        plaintextOutput = plaintextOutput,
    )

    internal fun openHmacSha256(key: ByteArray): NativeCryptoSession =
        client.openHmacSha256(key)

    internal fun openDigest(algorithm: HashAlgorithmProto): NativeCryptoSession =
        client.openDigest(algorithm)

    internal fun openHmac(
        algorithm: HashAlgorithmProto,
        key: ByteArray,
    ): NativeCryptoSession = client.openHmac(algorithm, key)

    internal fun openAesCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = client.openAesCbcPkcs7(direction, key, iv)

    internal fun openAesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = client.openAesCbcPkcs7HmacSha256Encrypt(
        encryptionKey = encryptionKey,
        macKey = macKey,
        iv = iv,
    )

    internal fun openAesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        expectedMac: ByteArray,
    ): NativeCryptoSession = client.openAesCbcPkcs7HmacSha256Decrypt(
        encryptionKey = encryptionKey,
        macKey = macKey,
        iv = iv,
        expectedMac = expectedMac,
    )

    internal fun openTwofishCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = client.openTwofishCbcPkcs7(direction, key, iv)

    internal fun openPgpDetachedVerification(
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = client.openPgpDetachedVerification(
        signature = signature,
        publicKeys = publicKeys,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    internal fun openPgpDetachedSigning(
        privateKey: ByteArray,
        preferredFingerprint: String,
        armored: Boolean,
        signatureTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = client.openPgpDetachedSigning(
        privateKey = privateKey,
        preferredFingerprint = preferredFingerprint,
        armored = armored,
        signatureTimeEpochSeconds = signatureTimeEpochSeconds,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    internal fun openPgpEncryption(
        publicKeys: List<ByteArray>,
        signingPrivateKey: ByteArray?,
        preferredSigningFingerprint: String,
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = client.openPgpEncryption(
        publicKeys = publicKeys,
        signingPrivateKey = signingPrivateKey,
        preferredSigningFingerprint = preferredSigningFingerprint,
        fileName = fileName,
        armored = armored,
        literalTimeEpochSeconds = literalTimeEpochSeconds,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    internal fun openPgpDecryption(
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = client.openPgpDecryption(
        privateKeys = privateKeys,
        verificationPublicKeys = verificationPublicKeys,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )
}

internal class NativeCryptoClient(
    private val bridge: NativeCryptoBridge,
    private val requiredCapabilitiesMask: Long = NativeCryptoCapability.entries
        .fold(0L) { mask, capability -> mask or capability.bit },
    private val onDiscardedOutputCleared: ((ByteArray) -> Unit)? = null,
) {
    private data class RuntimeInfo(
        val abiVersion: Int,
        val capabilitiesMask: Long,
    )

    private val runtimeInfo: RuntimeInfo by lazy {
        val actualAbiVersion = invokePlatform("bootstrap.abi") {
            bridge.abiVersion()
        }
        if (actualAbiVersion != NativeCrypto.EXPECTED_ABI_VERSION) {
            throw NativeCryptoException(
                operation = "bootstrap.abi",
                code = NativeCryptoErrorCode.ABI_MISMATCH,
            )
        }

        val actualCapabilities = invokePlatform("bootstrap.capabilities") {
            bridge.capabilities()
        }
        if (actualCapabilities and requiredCapabilitiesMask != requiredCapabilitiesMask) {
            throw NativeCryptoException(
                operation = "bootstrap.capabilities",
                code = NativeCryptoErrorCode.MISSING_CAPABILITY,
            )
        }
        RuntimeInfo(actualAbiVersion, actualCapabilities)
    }

    val abiVersion: Int
        get() = runtimeInfo.abiVersion

    val capabilities: Set<NativeCryptoCapability>
        get() = NativeCryptoCapability.entries
            .filterTo(linkedSetOf()) { capability ->
                runtimeInfo.capabilitiesMask and capability.bit != 0L
            }

    fun ensureReady() {
        runtimeInfo
    }

    fun call(
        operationName: String,
        operation: NativeRequestOperationProto,
    ): NativeResponseResultProto {
        ensureReady()
        val request = encodeEnvelope(
            operation = operationName,
            value = NativeRequestProto(
                protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                operation = operation,
            ),
        )
        val response = invokePlatform(operationName) {
            try {
                bridge.call(request)
            } finally {
                request.fill(0)
            }
        }
        return decodeResponse(
            expectedOperation = operationName,
            response = response,
            requireResult = true,
        ) ?: throw malformedResponse(operationName)
    }

    fun callInt32(
        operationName: String,
        operation: NativeRequestOperationProto,
    ): Int = requireResultType<Int32ResultProto>(
        operation = operationName,
        result = call(operationName, operation),
    ).value

    fun randomInt(exclusiveUpperBound: Int): Int {
        ensureReady()
        val result = invokePlatform(RANDOM_INT_OPERATION) {
            bridge.randomInt(exclusiveUpperBound)
        }
        val statusCode = result.nativeCryptoFastStatusCode()
        if (statusCode != 0) {
            val code = NativeCryptoErrorCode.fromWireValue(statusCode)
                ?: NativeCryptoErrorCode.INTERNAL
            throw NativeCryptoException(RANDOM_INT_OPERATION, code)
        }
        val value = result.nativeCryptoIntValue()
        if (exclusiveUpperBound > 0 && value !in 0 until exclusiveUpperBound) {
            throw malformedResponse(RANDOM_INT_OPERATION)
        }
        return value
    }

    fun aesCbcPkcs7HmacSha256EncryptFast(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Int = clearFastOutputsOnFailure(ciphertextOutput, macOutput) {
        ensureReady()
        ensureFastOutputsNotAliased(
            operation = AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION,
            outputs = arrayOf(ciphertextOutput, macOutput),
            inputs = arrayOf(encryptionKey, macKey, iv, plaintext),
        )
        val result = invokePlatform(AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION) {
            bridge.aesCbcPkcs7HmacSha256Encrypt(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                plaintext = plaintext,
                ciphertextOutput = ciphertextOutput,
                macOutput = macOutput,
            )
        }
        decodeFastResult(
            operation = AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION,
            result = result,
            outputCapacity = ciphertextOutput.size,
            exactOutputLength = ciphertextOutput.size,
        )
    }

    fun aesCbcPkcs7HmacSha256DecryptFast(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Int = clearFastOutputsOnFailure(plaintextOutput) {
        ensureReady()
        ensureFastOutputsNotAliased(
            operation = AES_CBC_HMAC_SHA256_DECRYPT_OPERATION,
            outputs = arrayOf(plaintextOutput),
            inputs = arrayOf(encryptionKey, macKey, iv, ciphertext, expectedMac),
        )
        val result = invokePlatform(AES_CBC_HMAC_SHA256_DECRYPT_OPERATION) {
            bridge.aesCbcPkcs7HmacSha256Decrypt(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                ciphertext = ciphertext,
                expectedMac = expectedMac,
                plaintextOutput = plaintextOutput,
            )
        }
        decodeFastResult(
            operation = AES_CBC_HMAC_SHA256_DECRYPT_OPERATION,
            result = result,
            outputCapacity = plaintextOutput.size,
            exactOutputLength = null,
        )
    }

    fun openHmacSha256(key: ByteArray): NativeCryptoSession {
        return openSession(
            operationName = "hmac_sha256.stream_open",
            operation = HmacSha256StreamOpenOperationProto(
                HmacSha256StreamOpenRequestProto(key),
            ),
        )
    }

    fun openDigest(algorithm: HashAlgorithmProto): NativeCryptoSession = openSession(
        operationName = "digest.stream_open",
        operation = DigestStreamOpenOperationProto(
            DigestStreamOpenRequestProto(algorithm),
        ),
    )

    fun openHmac(
        algorithm: HashAlgorithmProto,
        key: ByteArray,
    ): NativeCryptoSession = openSession(
        operationName = "hmac.stream_open",
        operation = HmacStreamOpenOperationProto(
            HmacStreamOpenRequestProto(algorithm = algorithm, key = key),
        ),
    )

    fun openAesCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = openSession(
        operationName = "aes_cbc_pkcs7.stream_open",
        operation = AesCbcPkcs7StreamOpenOperationProto(
            AesCbcPkcs7StreamOpenRequestProto(
                direction = direction,
                key = key,
                iv = iv,
            ),
        ),
    )

    fun openAesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = openSession(
        operationName = "aes_cbc_pkcs7_hmac_sha256_encrypt.stream_open",
        operation = AesCbcPkcs7HmacSha256EncryptStreamOpenOperationProto(
            AesCbcPkcs7HmacSha256EncryptStreamOpenRequestProto(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
            ),
        ),
    )

    fun openAesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        expectedMac: ByteArray,
    ): NativeCryptoSession = openSession(
        operationName = "aes_cbc_pkcs7_hmac_sha256_decrypt.stream_open",
        operation = AesCbcPkcs7HmacSha256DecryptStreamOpenOperationProto(
            AesCbcPkcs7HmacSha256DecryptStreamOpenRequestProto(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                expectedMac = expectedMac,
            ),
        ),
    )

    fun openTwofishCbcPkcs7(
        direction: CipherDirectionProto,
        key: ByteArray,
        iv: ByteArray,
    ): NativeCryptoSession = openSession(
        operationName = "twofish_cbc_pkcs7.stream_open",
        operation = TwofishCbcPkcs7StreamOpenOperationProto(
            TwofishCbcPkcs7StreamOpenRequestProto(
                direction = direction,
                key = key,
                iv = iv,
            ),
        ),
    )

    fun openPgpDetachedVerification(
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = openSession(
        operationName = "open_pgp_detached_verify.stream_open",
        operation = OpenPgpDetachedVerifyStreamOpenOperationProto(
            OpenPgpDetachedVerifyStreamOpenRequestProto(
                signature = signature,
                publicKeys = publicKeys,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        ),
    )

    fun openPgpDetachedSigning(
        privateKey: ByteArray,
        preferredFingerprint: String,
        armored: Boolean,
        signatureTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = openSession(
        operationName = "open_pgp_detached_sign.stream_open",
        operation = OpenPgpDetachedSignStreamOpenOperationProto(
            OpenPgpDetachedSignStreamOpenRequestProto(
                privateKey = privateKey,
                preferredFingerprint = preferredFingerprint,
                armored = armored,
                signatureTimeEpochSeconds = signatureTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        ),
    )

    fun openPgpEncryption(
        publicKeys: List<ByteArray>,
        signingPrivateKey: ByteArray?,
        preferredSigningFingerprint: String,
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = openSession(
        operationName = "open_pgp_encrypt.stream_open",
        operation = OpenPgpEncryptStreamOpenOperationProto(
            OpenPgpEncryptStreamOpenRequestProto(
                publicKeys = publicKeys,
                signingPrivateKey = signingPrivateKey,
                preferredSigningFingerprint = preferredSigningFingerprint,
                fileName = fileName,
                armored = armored,
                literalTimeEpochSeconds = literalTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        ),
    )

    fun openPgpDecryption(
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
    ): NativeCryptoSession = openSession(
        operationName = "open_pgp_decrypt.stream_open",
        operation = OpenPgpDecryptStreamOpenOperationProto(
            OpenPgpDecryptStreamOpenRequestProto(
                privateKeys = privateKeys,
                verificationPublicKeys = verificationPublicKeys,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        ),
    )

    private fun openSession(
        operationName: String,
        operation: NativeStreamOpenOperationProto,
    ): NativeCryptoSession {
        ensureReady()
        val request = encodeEnvelope(
            operation = operationName,
            value = NativeStreamOpenRequestProto(
                protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                operation = operation,
            ),
        )
        val response = invokePlatform(operationName) {
            try {
                bridge.streamOpen(request)
            } finally {
                request.fill(0)
            }
        }
        val handle = requireResultType<UInt64ResultProto>(
            operation = operationName,
            result = decodeResponse(
                expectedOperation = operationName,
                response = response,
                requireResult = true,
            ),
        ).value
        if (handle == 0L) {
            throw malformedResponse(operationName)
        }
        return NativeCryptoSessionImpl(
            client = this,
            handle = handle,
        )
    }

    internal fun streamUpdate(handle: Long, input: ByteArray): ByteArray {
        ensureEnvelopeSize("stream.update", input)
        val response = invokePlatform("stream.update") {
            bridge.streamUpdate(handle, input)
        }
        return when (
            val result = decodeResponse(
                expectedOperation = "stream.update",
                response = response,
                requireResult = false,
            )
        ) {
            null -> ByteArray(0)
            is BytesResultProto -> result.value
            else -> throw malformedResponse("stream.update")
        }
    }

    internal fun streamFinish(handle: Long): ByteArray {
        val response = invokePlatform("stream.finish") {
            bridge.streamFinish(handle)
        }
        return when (
            val result = decodeResponse(
                expectedOperation = "stream.finish",
                response = response,
                requireResult = true,
            )
        ) {
            is BytesResultProto -> result.value
            else -> throw malformedResponse("stream.finish")
        }
    }

    internal fun streamClose(handle: Long) {
        val response = invokePlatform("stream.close") {
            bridge.streamClose(handle)
        }
        when (
            val result = decodeResponse(
                expectedOperation = "stream.close",
                response = response,
                requireResult = false,
            )
        ) {
            null -> Unit
            is BytesResultProto -> if (result.value.isNotEmpty()) {
                clearDiscardedOutput(result.value)
                throw malformedResponse("stream.close")
            }

            else -> throw malformedResponse("stream.close")
        }
    }

    internal fun clearDiscardedOutput(output: ByteArray) {
        output.fill(0)
        onDiscardedOutputCleared?.invoke(output)
    }

    private inline fun <reified T> encodeEnvelope(
        operation: String,
        value: T,
    ): ByteArray {
        val encoded = try {
            ProtoBuf.encodeToByteArray(value)
        } catch (_: SerializationException) {
            throw NativeCryptoException(operation, NativeCryptoErrorCode.INTERNAL)
        }
        try {
            ensureEnvelopeSize(operation, encoded)
        } catch (failure: Throwable) {
            encoded.fill(0)
            throw failure
        }
        return encoded
    }

    internal fun decodeResponse(
        expectedOperation: String,
        response: ByteArray,
        requireResult: Boolean,
    ): NativeResponseResultProto? {
        var ownedResult: NativeResponseResultProto? = null
        return try {
            ensureEnvelopeSize(expectedOperation, response)
            val decoded = try {
                ProtoBuf.decodeFromByteArray<NativeResponseProto>(response)
            } catch (_: SerializationException) {
                throw malformedResponse(expectedOperation)
            } catch (_: IllegalArgumentException) {
                throw malformedResponse(expectedOperation)
            }
            ownedResult = decoded.result

            if (decoded.protocolVersion != NativeCrypto.PROTOCOL_VERSION) {
                throw NativeCryptoException(
                    operation = expectedOperation,
                    code = NativeCryptoErrorCode.UNSUPPORTED_PROTOCOL,
                )
            }
            val status = decoded.status ?: throw malformedResponse(expectedOperation)
            if (status.code != NativeErrorCodeProto.OK) {
                throw NativeCryptoException(expectedOperation, status.code.toErrorCode())
            }
            if (requireResult && ownedResult == null) {
                throw malformedResponse(expectedOperation)
            }
            ownedResult.also { ownedResult = null }
        } finally {
            try {
                clearDiscardedResult(ownedResult)
            } finally {
                response.fill(0)
            }
        }
    }

    private inline fun <reified T : NativeResponseResultProto> requireResultType(
        operation: String,
        result: NativeResponseResultProto?,
    ): T {
        if (result is T) return result
        clearDiscardedResult(result)
        throw malformedResponse(operation)
    }

    private fun clearDiscardedResult(result: NativeResponseResultProto?) {
        when (result) {
            is BytesResultProto -> clearDiscardedOutput(result.value)
            is Int32ResultProto,
            is UInt64ResultProto,
            null,
            -> Unit
        }
    }

    private fun ensureEnvelopeSize(operation: String, value: ByteArray) {
        if (value.size > NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES) {
            throw NativeCryptoException(operation, NativeCryptoErrorCode.RESOURCE_LIMIT)
        }
    }

    /** The native fast path writes into pinned output arrays, so aliasing would corrupt inputs. */
    private fun ensureFastOutputsNotAliased(
        operation: String,
        outputs: Array<ByteArray>,
        inputs: Array<ByteArray>,
    ) {
        for (index in outputs.indices) {
            val output = outputs[index]
            val aliased = inputs.any { input -> input === output } ||
                (index + 1 until outputs.size).any { otherIndex -> outputs[otherIndex] === output }
            if (aliased) {
                throw NativeCryptoException(operation, NativeCryptoErrorCode.INVALID_ARGUMENT)
            }
        }
    }

    private fun decodeFastResult(
        operation: String,
        result: Long,
        outputCapacity: Int,
        exactOutputLength: Int?,
    ): Int {
        val statusCode = result.nativeCryptoFastStatusCode()
        if (statusCode != 0) {
            val code = NativeCryptoErrorCode.fromWireValue(statusCode)
                ?: NativeCryptoErrorCode.INTERNAL
            throw NativeCryptoException(operation, code)
        }
        val outputLength = result.nativeCryptoFastOutputLength()
        if (
            outputLength < 0 ||
            outputLength > outputCapacity ||
            (exactOutputLength != null && outputLength != exactOutputLength)
        ) {
            throw malformedResponse(operation)
        }
        return outputLength
    }

    private inline fun <T> clearFastOutputsOnFailure(
        first: ByteArray,
        second: ByteArray,
        block: () -> T,
    ): T {
        var succeeded = false
        return try {
            block().also { succeeded = true }
        } finally {
            if (!succeeded) {
                first.fill(0)
                second.fill(0)
            }
        }
    }

    private inline fun <T> clearFastOutputsOnFailure(
        output: ByteArray,
        block: () -> T,
    ): T {
        var succeeded = false
        return try {
            block().also { succeeded = true }
        } finally {
            if (!succeeded) {
                output.fill(0)
            }
        }
    }

    private inline fun <T> invokePlatform(
        operation: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: NativeCryptoException) {
        throw e
    } catch (ignored: NativeCryptoPlatformException) {
        // Platform diagnostics can contain sensitive paths;
        // retain only the stable error code.
        throw NativeCryptoException(operation, ignored.code)
    } catch (_: Exception) {
        throw NativeCryptoException(operation, NativeCryptoErrorCode.INTERNAL)
    }

    private fun malformedResponse(operation: String): NativeCryptoException = NativeCryptoException(
        operation = operation,
        code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
    )

    private companion object {
        const val RANDOM_INT_OPERATION: String = "random_int"
        const val AES_CBC_HMAC_SHA256_ENCRYPT_OPERATION: String =
            "aes_cbc_pkcs7_hmac_sha256_encrypt"
        const val AES_CBC_HMAC_SHA256_DECRYPT_OPERATION: String =
            "aes_cbc_pkcs7_hmac_sha256_decrypt"
    }
}

public interface NativeCryptoSession : AutoCloseable {
    /** Updates this session. Callers must externally serialize access to a session. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Finishes and consumes this session. */
    public fun finish(): ByteArray

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

private class NativeCryptoSessionImpl(
    private val client: NativeCryptoClient,
    private val handle: Long,
) : NativeCryptoSession {
    private var consumed = false
    private var closeAcknowledged = false

    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        checkOpen("stream.update")
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid native crypto stream range"
        }
        if (length > NATIVE_CRYPTO_STREAM_CHUNK_BYTES) {
            throw NativeCryptoException("stream.update", NativeCryptoErrorCode.RESOURCE_LIMIT)
        }
        val ownedInput = if (offset == 0 && length == data.size) {
            null
        } else {
            data.copyOfRange(offset, offset + length)
        }
        return try {
            client.streamUpdate(handle, ownedInput ?: data)
        } finally {
            ownedInput?.fill(0)
        }
    }

    override fun finish(): ByteArray {
        checkOpen("stream.finish")
        consumed = true
        var primaryFailure: Throwable? = null
        var output: ByteArray? = null
        val result = try {
            client.streamFinish(handle).also { result -> output = result }
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            try {
                client.streamClose(handle)
                closeAcknowledged = true
            } catch (closeFailure: Throwable) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    output?.let(client::clearDiscardedOutput)
                    primaryFailure = closeFailure
                }
            }
        }
        primaryFailure?.let { throw it }
        return result
    }

    override fun close() {
        if (closeAcknowledged) return
        consumed = true
        client.streamClose(handle)
        closeAcknowledged = true
    }

    private fun checkOpen(operation: String) {
        if (consumed) {
            throw NativeCryptoException(operation, NativeCryptoErrorCode.INVALID_SESSION)
        }
    }

}
