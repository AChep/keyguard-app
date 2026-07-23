@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public sealed interface NativeOpenPgpPublicKeyParseResult {
    public data class Success(
        val keys: List<NativeOpenPgpPublicKeyInfo>,
    ) : NativeOpenPgpPublicKeyParseResult

    public data class Error(
        val reason: NativeOpenPgpPublicKeyParseError,
    ) : NativeOpenPgpPublicKeyParseResult
}

public enum class NativeOpenPgpPublicKeyParseError {
    EMPTY,
    MALFORMED,
    UNSUPPORTED_KEY_VERSION,
}

public data class NativeOpenPgpPublicKeyInfo(
    val fingerprint: String,
    val keygrip: String?,
    val keyId: String,
    val algorithm: String,
    val bitStrength: Int?,
    val userIds: List<String>,
    val emails: List<String>,
    val createdAtEpochSeconds: Long?,
    val expiresAtEpochSeconds: Long?,
    val revoked: Boolean,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    val publicKeyArmored: String,
    val subkeys: List<NativeOpenPgpPublicSubKeyInfo>,
)

public data class NativeOpenPgpPublicSubKeyInfo(
    val fingerprint: String,
    val keygrip: String?,
    val keyId: String,
    val algorithm: String,
    val bitStrength: Int?,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    val revoked: Boolean,
    val createdAtEpochSeconds: Long?,
    val expiresAtEpochSeconds: Long?,
)

public enum class NativeOpenPgpVerificationStatus {
    VALID,
    INVALID,
    MISSING_PUBLIC_KEY,
}

public enum class NativeOpenPgpVerificationWarning {
    KEY_REVOKED,
    KEY_EXPIRED,
    SIGNATURE_EXPIRED,
}

public data class NativeOpenPgpVerification(
    val status: NativeOpenPgpVerificationStatus,
    val keyId: String,
    val fingerprint: String?,
    val userIds: List<String>,
    val createdAtEpochSeconds: Long?,
    val warnings: List<NativeOpenPgpVerificationWarning>,
)

public data class NativeOpenPgpKeyMetadata(
    val version: Int,
    val keys: List<NativeOpenPgpKeyMetadataKey>,
)

public data class NativeOpenPgpKeyMetadataKey(
    val keygrip: String,
    val fingerprint: String,
    val algorithm: String,
    val capabilities: Set<String>,
)

public enum class NativeOpenPgpKeyKind {
    LEGACY_ED25519_X25519,
    RSA,
}

public data class NativeOpenPgpKeyMaterial(
    val privateKeyArmored: ByteArray,
    val publicKeyArmored: ByteArray,
    val fingerprint: String,
)

public sealed interface NativeOpenPgpKeyImportResult {
    public data class Success(
        val keyMaterial: NativeOpenPgpKeyMaterial,
    ) : NativeOpenPgpKeyImportResult

    public data class NeedsPassphrase(
        val formatLabel: String,
    ) : NativeOpenPgpKeyImportResult

    public data class Error(
        val reason: NativeOpenPgpKeyImportError,
    ) : NativeOpenPgpKeyImportResult
}

public enum class NativeOpenPgpKeyImportError {
    EMPTY,
    UNSUPPORTED_FORMAT,
    INVALID_PASSPHRASE,
    MALFORMED_KEY,
}

public enum class NativeOpenPgpProtectionMode {
    SEIPD_V1_MDC,
    GNUPG_OCB,
}

public data class NativeOpenPgpEncryptResult(
    val data: ByteArray,
    val protectionMode: NativeOpenPgpProtectionMode,
)

public data class NativeOpenPgpDecryptResult(
    val data: ByteArray,
    val verification: NativeOpenPgpVerification?,
)

public data class NativeOpenPgpEncryptFinal(
    val data: ByteArray,
    val protectionMode: NativeOpenPgpProtectionMode,
)

public data class NativeOpenPgpDecryptFinal(
    val data: ByteArray,
    val verification: NativeOpenPgpVerification?,
)

public sealed interface NativeOpenPgpExpirationUpdateResult {
    public data class Success(
        val keyMaterial: NativeOpenPgpKeyMaterial,
        val metadata: NativeOpenPgpKeyMetadata,
    ) : NativeOpenPgpExpirationUpdateResult

    public data class Error(
        val reason: NativeOpenPgpExpirationUpdateError,
    ) : NativeOpenPgpExpirationUpdateResult
}

public enum class NativeOpenPgpExpirationUpdateError {
    EMPTY_PRIVATE_KEY,
    MALFORMED_KEY,
    FINGERPRINT_MISMATCH,
    NO_COMPONENTS_SELECTED,
    COMPONENT_NOT_FOUND,
    REVOKED_COMPONENT,
    UNRESOLVED_REVOCATION_AUTHORITY,
    UNSUPPORTED_KEY_VERSION,
    MISSING_SECRET_KEY,
    PROTECTED_SECRET_KEY,
    MISSING_SELF_SIGNATURE,
    INVALID_EXPIRATION,
    TIME_CONFLICT,
    SIGNATURE_VERIFICATION_FAILED,
    METADATA_RESOLUTION_FAILED,
    INTERNAL_FAILURE,
}

public sealed interface NativeOpenPgpAgentSignResult {
    public data class Success(
        val canonicalSexp: ByteArray,
    ) : NativeOpenPgpAgentSignResult

    public data class Error(
        val reason: NativeOpenPgpAgentError,
    ) : NativeOpenPgpAgentSignResult
}

public sealed interface NativeOpenPgpAgentDecryptResult {
    public data class Success(
        val canonicalSexp: ByteArray,
    ) : NativeOpenPgpAgentDecryptResult

    public data class Error(
        val reason: NativeOpenPgpAgentError,
    ) : NativeOpenPgpAgentDecryptResult
}

public enum class NativeOpenPgpAgentError {
    KEY_NOT_FOUND,
    UNSUPPORTED_ALGORITHM,
}

public interface NativeOpenPgpVerificationSession : AutoCloseable {
    /** Supplies at most 64 KiB of the detached signature's body. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    )

    /** Finishes and consumes this session, returning the authenticated result. */
    public fun finish(): NativeOpenPgpVerification

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpDetachedSigningSession : AutoCloseable {
    /** Supplies at most 64 KiB of the exact file body to sign. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    )

    /** Finishes and consumes this session, returning the detached signature. */
    public fun finish(): ByteArray

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpEncryptionSession : AutoCloseable {
    /** Supplies plaintext and returns the next encrypted output chunk. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Finishes and consumes this session, returning final bytes and the selected mode. */
    public fun finish(): NativeOpenPgpEncryptFinal

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpDecryptionSession : AutoCloseable {
    /** Supplies encrypted input and returns provisional plaintext for caller-owned staging. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Authenticates, consumes this session, and returns final provisional plaintext. */
    public fun finish(): NativeOpenPgpDecryptFinal

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

/** Safe, application-internal facade for the OpenPGP read path. */
public object NativeCryptoOpenPgp {
    /** Maximum number of key documents accepted by one native OpenPGP request. */
    public const val MAX_KEY_DOCUMENTS_PER_REQUEST: Int = 64

    /**
     * Maximum plaintext accepted by the in-memory [encrypt] and returned by [decrypt].
     * One MiB of the 16 MiB native envelope remains available for the encoded final chunk and
     * verification metadata. Larger payloads must use [openEncryption] or [openDecryption].
     */
    public const val MAX_IN_MEMORY_PLAINTEXT_BYTES: Int = 15 * 1024 * 1024

    public fun parsePublicKeys(
        keyData: ByteArray,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpPublicKeyParseResult {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_public_key_parse",
            operation = OpenPgpPublicKeyParseOperationProto(
                OpenPgpPublicKeyParseRequestProto(
                    keyData = keyData,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_public_key_parse")
        val result = decodePayload<OpenPgpPublicKeyParseResultProto>(
            operation = "open_pgp_public_key_parse",
            payload = payload,
        )
        return when (val outcome = result.result) {
            is OpenPgpPublicKeyParseSuccessOutcomeProto -> {
                val keys = outcome.value.keys.map { value ->
                    value.toPublic("open_pgp_public_key_parse")
                }
                if (keys.isEmpty()) malformedOpenPgp("open_pgp_public_key_parse")
                NativeOpenPgpPublicKeyParseResult.Success(keys)
            }

            is OpenPgpPublicKeyParseErrorOutcomeProto ->
                NativeOpenPgpPublicKeyParseResult.Error(
                    reason = outcome.value.reason.toPublic("open_pgp_public_key_parse"),
                )

            null -> malformedOpenPgp("open_pgp_public_key_parse")
        }
    }

    public fun verifyClearSigned(
        signedDocument: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpVerification = verify(
        kind = OpenPgpVerifyKindProto.CLEAR_TEXT,
        content = signedDocument,
        signature = byteArrayOf(),
        publicKeys = publicKeys,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun verifyDetached(
        content: ByteArray,
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpVerification = verify(
        kind = OpenPgpVerifyKindProto.DETACHED,
        content = content,
        signature = signature,
        publicKeys = publicKeys,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun openDetachedVerification(
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpVerificationSession {
        requireReferenceTime(referenceTimeEpochSeconds)
        val session = NativeCrypto.openPgpDetachedVerification(
            signature = signature,
            publicKeys = publicKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpVerificationSessionImpl(session)
    }

    public fun resolveMetadata(
        privateKeyData: ByteArray?,
        publicKeyData: ByteArray?,
        normalizedFingerprint: String = "",
        candidateRevocationKeys: List<ByteArray> = emptyList(),
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpKeyMetadata? {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_metadata_resolve",
            operation = OpenPgpMetadataResolveOperationProto(
                OpenPgpMetadataResolveRequestProto(
                    privateKeyData = privateKeyData,
                    publicKeyData = publicKeyData,
                    normalizedFingerprint = normalizedFingerprint,
                    candidateRevocationKeys = candidateRevocationKeys,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_metadata_resolve")
        return decodePayload<OpenPgpMetadataResolveResultProto>(
            operation = "open_pgp_metadata_resolve",
            payload = payload,
        ).metadata?.toPublic("open_pgp_metadata_resolve")
    }

    public fun generateKey(
        kind: NativeOpenPgpKeyKind,
        userId: String,
        rsaBits: Int = 0,
        creationTimeEpochSeconds: Long,
        expirationSeconds: Long? = null,
    ): NativeOpenPgpKeyMaterial {
        require(userId.isNotBlank()) { "OpenPGP user ID must not be blank" }
        require(creationTimeEpochSeconds >= 0L) { "OpenPGP creation time must not be negative" }
        require(expirationSeconds == null || expirationSeconds in 1L..UInt.MAX_VALUE.toLong()) {
            "OpenPGP expiration must fit an unsigned 32-bit duration"
        }
        when (kind) {
            NativeOpenPgpKeyKind.LEGACY_ED25519_X25519 ->
                require(rsaBits == 0) { "RSA bits must be zero for a modern OpenPGP key" }

            NativeOpenPgpKeyKind.RSA ->
                require(rsaBits == 3072 || rsaBits == 4096) { "Unsupported OpenPGP RSA size" }
        }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_key_generate",
            operation = OpenPgpKeyGenerateOperationProto(
                OpenPgpKeyGenerateRequestProto(
                    kind = when (kind) {
                        NativeOpenPgpKeyKind.LEGACY_ED25519_X25519 ->
                            OpenPgpKeyKindProto.LEGACY_ED25519_X25519

                        NativeOpenPgpKeyKind.RSA -> OpenPgpKeyKindProto.RSA
                    },
                    userId = userId,
                    rsaBits = rsaBits,
                    creationTimeEpochSeconds = creationTimeEpochSeconds,
                    expirationSeconds = expirationSeconds?.toUInt(),
                ),
            ),
        ).requireBytes("open_pgp_key_generate")
        return decodePayload<OpenPgpKeyMaterialProto>(
            operation = "open_pgp_key_generate",
            payload = payload,
        ).toPublic(
            operation = "open_pgp_key_generate",
            requirePrivateKey = true,
        )
    }

    public fun importKey(
        keyData: ByteArray,
        passphraseUtf8: ByteArray? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpKeyImportResult {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_key_import",
            operation = OpenPgpKeyImportOperationProto(
                OpenPgpKeyImportRequestProto(
                    keyData = keyData,
                    passphraseUtf8 = passphraseUtf8,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_key_import")
        val result = decodePayload<OpenPgpKeyImportResultProto>(
            operation = "open_pgp_key_import",
            payload = payload,
        )
        return when (val outcome = result.result) {
            is OpenPgpKeyImportSuccessOutcomeProto -> {
                val material = outcome.value.keyMaterial
                    ?: malformedOpenPgp("open_pgp_key_import")
                NativeOpenPgpKeyImportResult.Success(
                    material.toPublic(
                        operation = "open_pgp_key_import",
                        requirePrivateKey = true,
                    ),
                )
            }

            is OpenPgpKeyImportNeedsPassphraseOutcomeProto -> {
                val formatLabel = outcome.value.formatLabel
                if (formatLabel.isBlank()) malformedOpenPgp("open_pgp_key_import")
                NativeOpenPgpKeyImportResult.NeedsPassphrase(formatLabel)
            }

            is OpenPgpKeyImportErrorOutcomeProto -> NativeOpenPgpKeyImportResult.Error(
                reason = when (outcome.value.reason) {
                    OpenPgpKeyImportErrorReasonProto.EMPTY -> NativeOpenPgpKeyImportError.EMPTY
                    OpenPgpKeyImportErrorReasonProto.UNSUPPORTED_FORMAT ->
                        NativeOpenPgpKeyImportError.UNSUPPORTED_FORMAT

                    OpenPgpKeyImportErrorReasonProto.INVALID_PASSPHRASE ->
                        NativeOpenPgpKeyImportError.INVALID_PASSPHRASE

                    OpenPgpKeyImportErrorReasonProto.MALFORMED_KEY ->
                        NativeOpenPgpKeyImportError.MALFORMED_KEY

                    OpenPgpKeyImportErrorReasonProto.UNSPECIFIED ->
                        malformedOpenPgp("open_pgp_key_import")
                },
            )

            null -> malformedOpenPgp("open_pgp_key_import")
        }
    }

    public fun clearSign(
        content: ByteArray,
        privateKey: ByteArray,
        preferredFingerprint: String = "",
        signatureTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): ByteArray = sign(
        kind = OpenPgpSignKindProto.CLEAR_TEXT,
        content = content,
        privateKey = privateKey,
        preferredFingerprint = preferredFingerprint,
        armored = true,
        signatureTimeEpochSeconds = signatureTimeEpochSeconds,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun signDetached(
        content: ByteArray,
        privateKey: ByteArray,
        preferredFingerprint: String = "",
        armored: Boolean = true,
        signatureTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): ByteArray = sign(
        kind = OpenPgpSignKindProto.DETACHED,
        content = content,
        privateKey = privateKey,
        preferredFingerprint = preferredFingerprint,
        armored = armored,
        signatureTimeEpochSeconds = signatureTimeEpochSeconds,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun encrypt(
        content: ByteArray,
        publicKeys: List<ByteArray>,
        signingPrivateKey: ByteArray? = null,
        preferredSigningFingerprint: String = "",
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpEncryptResult {
        requireEncryptInputs(
            publicKeys = publicKeys,
            signingPrivateKey = signingPrivateKey,
            preferredSigningFingerprint = preferredSigningFingerprint,
            fileName = fileName,
            literalTimeEpochSeconds = literalTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        requireInMemoryOpenPgpPlaintextSize(
            operation = "open_pgp_encrypt",
            size = content.size,
        )
        if (content.size > OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES) {
            return encryptStreaming(
                content = content,
                publicKeys = publicKeys,
                signingPrivateKey = signingPrivateKey,
                preferredSigningFingerprint = preferredSigningFingerprint,
                fileName = fileName,
                armored = armored,
                literalTimeEpochSeconds = literalTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            )
        }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_encrypt",
            operation = OpenPgpEncryptOperationProto(
                OpenPgpEncryptRequestProto(
                    content = content,
                    publicKeys = publicKeys,
                    signingPrivateKey = signingPrivateKey,
                    preferredSigningFingerprint = preferredSigningFingerprint,
                    fileName = fileName,
                    armored = armored,
                    literalTimeEpochSeconds = literalTimeEpochSeconds,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_encrypt")
        return decodePayload<OpenPgpEncryptResultProto>(
            operation = "open_pgp_encrypt",
            payload = payload,
        ).toPublic("open_pgp_encrypt")
    }

    private fun encryptStreaming(
        content: ByteArray,
        publicKeys: List<ByteArray>,
        signingPrivateKey: ByteArray?,
        preferredSigningFingerprint: String,
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
    ): NativeOpenPgpEncryptResult {
        val session = openEncryption(
            publicKeys = publicKeys,
            signingPrivateKey = signingPrivateKey,
            preferredSigningFingerprint = preferredSigningFingerprint,
            fileName = fileName,
            armored = armored,
            literalTimeEpochSeconds = literalTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        val stagedOutputs = mutableListOf<ByteArray>()
        var totalOutputSize = 0L
        var primaryFailure: Throwable? = null
        var resultData: ByteArray? = null
        val result = try {
            var offset = 0
            while (offset < content.size) {
                val length = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, content.size - offset)
                totalOutputSize = stageOutput(
                    operation = "open_pgp_encrypt.stream_update",
                    output = session.update(content, offset, length),
                    stagedOutputs = stagedOutputs,
                    previousTotal = totalOutputSize,
                )
                offset += length
            }

            val final = session.finish()
            totalOutputSize = stageOutput(
                operation = "open_pgp_encrypt.stream_update",
                output = final.data,
                stagedOutputs = stagedOutputs,
                previousTotal = totalOutputSize,
            )
            NativeOpenPgpEncryptResult(
                data = mergeStagedOutputs(
                    stagedOutputs = stagedOutputs,
                    totalOutputSize = totalOutputSize.toInt(),
                ).also { resultData = it },
                protectionMode = final.protectionMode,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            resultData?.fill(0)
            throw failure
        } finally {
            stagedOutputs.forEach { output -> output.fill(0) }
            try {
                session.close()
            } catch (closeFailure: Throwable) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    resultData?.fill(0)
                    primaryFailure = closeFailure
                }
            }
        }
        primaryFailure?.let { throw it }
        return result
    }

    public fun decrypt(
        content: ByteArray,
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray> = emptyList(),
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpDecryptResult {
        requireDecryptInputs(privateKeys, referenceTimeEpochSeconds)
        if (content.isEmpty()) {
            throw NativeCryptoException(
                operation = "open_pgp_decrypt",
                code = NativeCryptoErrorCode.INVALID_ARGUMENT,
            )
        }
        return decryptStreaming(
            content = content,
            privateKeys = privateKeys,
            verificationPublicKeys = verificationPublicKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
    }

    private fun decryptStreaming(
        content: ByteArray,
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
    ): NativeOpenPgpDecryptResult {
        val session = openDecryption(
            privateKeys = privateKeys,
            verificationPublicKeys = verificationPublicKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        val stagedPlaintext = BoundedOpenPgpPlaintextAccumulator(
            operation = "open_pgp_decrypt",
            maximumBytes = MAX_IN_MEMORY_PLAINTEXT_BYTES,
        )
        var primaryFailure: Throwable? = null
        var resultData: ByteArray? = null
        var deferredCloseFailure: Throwable? = null
        val result = try {
            var offset = 0
            while (offset < content.size) {
                val length = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, content.size - offset)
                stagedPlaintext.stage(session.update(content, offset, length))
                offset += length
            }

            val final = session.finish()
            stagedPlaintext.stage(final.data)
            NativeOpenPgpDecryptResult(
                data = stagedPlaintext.commit().also { resultData = it },
                verification = final.verification,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            resultData?.fill(0)
            throw failure
        } finally {
            stagedPlaintext.close()
            try {
                session.close()
            } catch (closeFailure: Throwable) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    resultData?.fill(0)
                    deferredCloseFailure = closeFailure
                }
            }
        }
        return deferredCloseFailure?.let { throw it } ?: result
    }

    public fun openDetachedSigning(
        privateKey: ByteArray,
        preferredFingerprint: String = "",
        armored: Boolean = true,
        signatureTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpDetachedSigningSession {
        requireSigningInputs(
            privateKey = privateKey,
            preferredFingerprint = preferredFingerprint,
            signatureTimeEpochSeconds = signatureTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpDetachedSigningSessionImpl(
            NativeCrypto.openPgpDetachedSigning(
                privateKey = privateKey,
                preferredFingerprint = preferredFingerprint,
                armored = armored,
                signatureTimeEpochSeconds = signatureTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        )
    }

    public fun openEncryption(
        publicKeys: List<ByteArray>,
        signingPrivateKey: ByteArray? = null,
        preferredSigningFingerprint: String = "",
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpEncryptionSession {
        requireEncryptInputs(
            publicKeys = publicKeys,
            signingPrivateKey = signingPrivateKey,
            preferredSigningFingerprint = preferredSigningFingerprint,
            fileName = fileName,
            literalTimeEpochSeconds = literalTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpEncryptionSessionImpl(
            NativeCrypto.openPgpEncryption(
                publicKeys = publicKeys,
                signingPrivateKey = signingPrivateKey,
                preferredSigningFingerprint = preferredSigningFingerprint,
                fileName = fileName,
                armored = armored,
                literalTimeEpochSeconds = literalTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        )
    }

    public fun openDecryption(
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray> = emptyList(),
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpDecryptionSession {
        requireDecryptInputs(privateKeys, referenceTimeEpochSeconds)
        return NativeOpenPgpDecryptionSessionImpl(
            NativeCrypto.openPgpDecryption(
                privateKeys = privateKeys,
                verificationPublicKeys = verificationPublicKeys,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        )
    }

    public fun updateExpiration(
        privateKey: ByteArray,
        publicKey: ByteArray,
        expectedPrimaryFingerprint: String,
        componentFingerprints: List<String>,
        expiresAtEpochSeconds: Long?,
        candidateRevocationKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long,
    ): NativeOpenPgpExpirationUpdateResult {
        require(referenceTimeEpochSeconds >= 0L) {
            "OpenPGP reference time must not be negative"
        }
        require(expiresAtEpochSeconds == null || expiresAtEpochSeconds >= 0L) {
            "OpenPGP expiration time must not be negative"
        }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_expiration_update",
            operation = OpenPgpExpirationUpdateOperationProto(
                OpenPgpExpirationUpdateRequestProto(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                    componentFingerprints = componentFingerprints,
                    expiresAtEpochSeconds = expiresAtEpochSeconds,
                    candidateRevocationKeys = candidateRevocationKeys,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_expiration_update")
        val result = decodePayload<OpenPgpExpirationUpdateResultProto>(
            operation = "open_pgp_expiration_update",
            payload = payload,
        )
        return result.toPublicExpirationUpdateResult("open_pgp_expiration_update")
    }

    public fun agentSignHash(
        privateKey: ByteArray,
        preferredFingerprint: String,
        hashAlgorithm: String,
        hash: ByteArray,
    ): NativeOpenPgpAgentSignResult {
        require(privateKey.isNotEmpty()) { "OpenPGP private key must not be empty" }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_agent_sign",
            operation = OpenPgpAgentSignOperationProto(
                OpenPgpAgentSignRequestProto(
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                    hashAlgorithm = hashAlgorithm,
                    hash = hash,
                ),
            ),
        ).requireBytes("open_pgp_agent_sign")
        val result = decodePayload<OpenPgpAgentSignResultProto>(
            operation = "open_pgp_agent_sign",
            payload = payload,
        )
        return when (val outcome = result.result) {
            is OpenPgpAgentSignSuccessOutcomeProto -> {
                if (outcome.value.canonicalSexp.isEmpty()) {
                    malformedOpenPgp("open_pgp_agent_sign")
                }
                NativeOpenPgpAgentSignResult.Success(outcome.value.canonicalSexp)
            }

            is OpenPgpAgentSignErrorOutcomeProto -> NativeOpenPgpAgentSignResult.Error(
                outcome.value.reason.toPublic("open_pgp_agent_sign"),
            )

            null -> malformedOpenPgp("open_pgp_agent_sign")
        }
    }

    public fun agentDecrypt(
        privateKey: ByteArray,
        preferredFingerprint: String,
        ciphertext: ByteArray,
        unwrapEcdh: Boolean,
    ): NativeOpenPgpAgentDecryptResult {
        require(privateKey.isNotEmpty()) { "OpenPGP private key must not be empty" }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_agent_decrypt",
            operation = OpenPgpAgentDecryptOperationProto(
                OpenPgpAgentDecryptRequestProto(
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                    ciphertext = ciphertext,
                    unwrapEcdh = unwrapEcdh,
                ),
            ),
        ).requireBytes("open_pgp_agent_decrypt")
        val result = decodePayload<OpenPgpAgentDecryptResultProto>(
            operation = "open_pgp_agent_decrypt",
            payload = payload,
        )
        return when (val outcome = result.result) {
            is OpenPgpAgentDecryptSuccessOutcomeProto -> {
                if (outcome.value.canonicalSexp.isEmpty()) {
                    malformedOpenPgp("open_pgp_agent_decrypt")
                }
                NativeOpenPgpAgentDecryptResult.Success(outcome.value.canonicalSexp)
            }

            is OpenPgpAgentDecryptErrorOutcomeProto -> NativeOpenPgpAgentDecryptResult.Error(
                outcome.value.reason.toPublic("open_pgp_agent_decrypt"),
            )

            null -> malformedOpenPgp("open_pgp_agent_decrypt")
        }
    }

    private fun sign(
        kind: OpenPgpSignKindProto,
        content: ByteArray,
        privateKey: ByteArray,
        preferredFingerprint: String,
        armored: Boolean,
        signatureTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
    ): ByteArray {
        requireSigningInputs(
            privateKey = privateKey,
            preferredFingerprint = preferredFingerprint,
            signatureTimeEpochSeconds = signatureTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        val result = NativeCrypto.call(
            operationName = "open_pgp_sign",
            operation = OpenPgpSignOperationProto(
                OpenPgpSignRequestProto(
                    kind = kind,
                    content = content,
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                    armored = armored,
                    signatureTimeEpochSeconds = signatureTimeEpochSeconds,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_sign")
        if (result.isEmpty()) malformedOpenPgp("open_pgp_sign")
        return result
    }

    private fun verify(
        kind: OpenPgpVerifyKindProto,
        content: ByteArray,
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
    ): NativeOpenPgpVerification {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_verify",
            operation = OpenPgpVerifyOperationProto(
                OpenPgpVerifyRequestProto(
                    kind = kind,
                    content = content,
                    signature = signature,
                    publicKeys = publicKeys,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_verify")
        return decodeOpenPgpVerification("open_pgp_verify", payload)
    }
}

internal class BoundedOpenPgpPlaintextAccumulator(
    private val operation: String,
    private val maximumBytes: Int,
) : AutoCloseable {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0
    private var closed = false

    init {
        require(maximumBytes >= 0) { "Maximum OpenPGP plaintext size must not be negative" }
    }

    /** Takes ownership of [output], including when the plaintext limit is exceeded. */
    fun stage(output: ByteArray) {
        check(!closed) { "OpenPGP plaintext accumulator is closed" }
        if (output.size > maximumBytes - size) {
            output.fill(0)
            clear()
            throw NativeCryptoException(
                operation = operation,
                code = NativeCryptoErrorCode.RESOURCE_LIMIT,
            )
        }
        if (output.isNotEmpty()) {
            chunks += output
            size += output.size
        }
    }

    fun commit(): ByteArray {
        check(!closed) { "OpenPGP plaintext accumulator is closed" }
        var result: ByteArray? = null
        try {
            val destination = ByteArray(size)
            result = destination
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(destination, destinationOffset = offset)
                offset += chunk.size
            }
            return destination
        } catch (failure: Throwable) {
            result?.fill(0)
            throw failure
        } finally {
            clear()
            closed = true
        }
    }

    override fun close() {
        if (closed) return
        clear()
        closed = true
    }

    private fun clear() {
        chunks.forEach { chunk -> chunk.fill(0) }
        chunks.clear()
        size = 0
    }
}

private fun requireInMemoryOpenPgpPlaintextSize(
    operation: String,
    size: Int,
) {
    if (size > NativeCryptoOpenPgp.MAX_IN_MEMORY_PLAINTEXT_BYTES) {
        throw NativeCryptoException(
            operation = operation,
            code = NativeCryptoErrorCode.RESOURCE_LIMIT,
        )
    }
}

/**
 * Keeps one-shot armored encryption comfortably below the 16 MiB response envelope after
 * base64 expansion and packet overhead. Larger payloads use the unbounded streaming transport.
 */
internal const val OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES: Int = 8 * 1024 * 1024

private class NativeOpenPgpVerificationSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpVerificationSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val output = delegate.update(data, offset, length)
        if (output.isNotEmpty()) {
            output.fill(0)
            malformedOpenPgp("open_pgp_detached_verify.stream_update")
        }
    }

    override fun finish(): NativeOpenPgpVerification = decodeOpenPgpVerification(
        operation = "open_pgp_detached_verify.stream_finish",
        payload = delegate.finish(),
    )

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpDetachedSigningSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpDetachedSigningSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val output = delegate.update(data, offset, length)
        if (output.isNotEmpty()) {
            output.fill(0)
            malformedOpenPgp("open_pgp_detached_sign.stream_update")
        }
    }

    override fun finish(): ByteArray = delegate.finish().also { signature ->
        if (signature.isEmpty()) malformedOpenPgp("open_pgp_detached_sign.stream_finish")
    }

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpEncryptionSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpEncryptionSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = delegate.update(data, offset, length)

    override fun finish(): NativeOpenPgpEncryptFinal {
        val payload = delegate.finish()
        return decodePayload<OpenPgpEncryptFinalProto>(
            operation = "open_pgp_encrypt.stream_finish",
            payload = payload,
        ).toPublic("open_pgp_encrypt.stream_finish")
    }

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpDecryptionSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpDecryptionSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = delegate.update(data, offset, length)

    override fun finish(): NativeOpenPgpDecryptFinal {
        val payload = delegate.finish()
        return decodePayload<OpenPgpDecryptFinalProto>(
            operation = "open_pgp_decrypt.stream_finish",
            payload = payload,
        ).toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
    }

    override fun close() {
        delegate.close()
    }
}

private fun OpenPgpKeyMaterialProto.toPublic(
    operation: String,
    requirePrivateKey: Boolean,
): NativeOpenPgpKeyMaterial {
    var ownershipTransferred = false
    return try {
        requireOpenPgpFingerprint(operation, fingerprint)
        if (publicKeyArmored.isEmpty() || (requirePrivateKey && privateKeyArmored.isEmpty())) {
            malformedOpenPgp(operation)
        }
        NativeOpenPgpKeyMaterial(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
        ).also {
            ownershipTransferred = true
        }
    } finally {
        if (!ownershipTransferred) clearSensitiveData()
    }
}

internal fun OpenPgpExpirationUpdateResultProto.toPublicExpirationUpdateResult(
    operation: String,
): NativeOpenPgpExpirationUpdateResult = when (val outcome = result) {
    is OpenPgpExpirationUpdateSuccessOutcomeProto -> {
        val keyMaterial = outcome.value.keyMaterial ?: malformedOpenPgp(operation)
        var ownershipTransferred = false
        try {
            val metadata = outcome.value.metadata ?: malformedOpenPgp(operation)
            NativeOpenPgpExpirationUpdateResult.Success(
                keyMaterial = keyMaterial.toPublic(
                    operation = operation,
                    requirePrivateKey = true,
                ),
                metadata = metadata.toPublic(operation),
            ).also {
                ownershipTransferred = true
            }
        } finally {
            if (!ownershipTransferred) keyMaterial.clearSensitiveData()
        }
    }

    is OpenPgpExpirationUpdateErrorOutcomeProto ->
        NativeOpenPgpExpirationUpdateResult.Error(
            reason = outcome.value.reason.toPublic(operation),
        )

    null -> malformedOpenPgp(operation)
}

private fun OpenPgpKeyMaterialProto.clearSensitiveData() {
    privateKeyArmored.fill(0)
    publicKeyArmored.fill(0)
}

private fun OpenPgpExpirationUpdateErrorReasonProto.toPublic(
    operation: String,
): NativeOpenPgpExpirationUpdateError = when (this) {
    OpenPgpExpirationUpdateErrorReasonProto.EMPTY_PRIVATE_KEY ->
        NativeOpenPgpExpirationUpdateError.EMPTY_PRIVATE_KEY

    OpenPgpExpirationUpdateErrorReasonProto.MALFORMED_KEY ->
        NativeOpenPgpExpirationUpdateError.MALFORMED_KEY

    OpenPgpExpirationUpdateErrorReasonProto.FINGERPRINT_MISMATCH ->
        NativeOpenPgpExpirationUpdateError.FINGERPRINT_MISMATCH

    OpenPgpExpirationUpdateErrorReasonProto.NO_COMPONENTS_SELECTED ->
        NativeOpenPgpExpirationUpdateError.NO_COMPONENTS_SELECTED

    OpenPgpExpirationUpdateErrorReasonProto.COMPONENT_NOT_FOUND ->
        NativeOpenPgpExpirationUpdateError.COMPONENT_NOT_FOUND

    OpenPgpExpirationUpdateErrorReasonProto.REVOKED_COMPONENT ->
        NativeOpenPgpExpirationUpdateError.REVOKED_COMPONENT

    OpenPgpExpirationUpdateErrorReasonProto.UNRESOLVED_REVOCATION_AUTHORITY ->
        NativeOpenPgpExpirationUpdateError.UNRESOLVED_REVOCATION_AUTHORITY

    OpenPgpExpirationUpdateErrorReasonProto.UNSUPPORTED_KEY_VERSION ->
        NativeOpenPgpExpirationUpdateError.UNSUPPORTED_KEY_VERSION

    OpenPgpExpirationUpdateErrorReasonProto.MISSING_SECRET_KEY ->
        NativeOpenPgpExpirationUpdateError.MISSING_SECRET_KEY

    OpenPgpExpirationUpdateErrorReasonProto.PROTECTED_SECRET_KEY ->
        NativeOpenPgpExpirationUpdateError.PROTECTED_SECRET_KEY

    OpenPgpExpirationUpdateErrorReasonProto.MISSING_SELF_SIGNATURE ->
        NativeOpenPgpExpirationUpdateError.MISSING_SELF_SIGNATURE

    OpenPgpExpirationUpdateErrorReasonProto.INVALID_EXPIRATION ->
        NativeOpenPgpExpirationUpdateError.INVALID_EXPIRATION

    OpenPgpExpirationUpdateErrorReasonProto.TIME_CONFLICT ->
        NativeOpenPgpExpirationUpdateError.TIME_CONFLICT

    OpenPgpExpirationUpdateErrorReasonProto.SIGNATURE_VERIFICATION_FAILED ->
        NativeOpenPgpExpirationUpdateError.SIGNATURE_VERIFICATION_FAILED

    OpenPgpExpirationUpdateErrorReasonProto.METADATA_RESOLUTION_FAILED ->
        NativeOpenPgpExpirationUpdateError.METADATA_RESOLUTION_FAILED

    OpenPgpExpirationUpdateErrorReasonProto.INTERNAL_FAILURE ->
        NativeOpenPgpExpirationUpdateError.INTERNAL_FAILURE

    OpenPgpExpirationUpdateErrorReasonProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

private fun OpenPgpAgentErrorReasonProto.toPublic(
    operation: String,
): NativeOpenPgpAgentError = when (this) {
    OpenPgpAgentErrorReasonProto.KEY_NOT_FOUND -> NativeOpenPgpAgentError.KEY_NOT_FOUND
    OpenPgpAgentErrorReasonProto.UNSUPPORTED_ALGORITHM ->
        NativeOpenPgpAgentError.UNSUPPORTED_ALGORITHM

    OpenPgpAgentErrorReasonProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

private fun OpenPgpProtectionModeProto.toPublic(
    operation: String,
): NativeOpenPgpProtectionMode = when (this) {
    OpenPgpProtectionModeProto.SEIPD_V1_MDC -> NativeOpenPgpProtectionMode.SEIPD_V1_MDC
    OpenPgpProtectionModeProto.GNUPG_OCB -> NativeOpenPgpProtectionMode.GNUPG_OCB
    OpenPgpProtectionModeProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

private fun OpenPgpEncryptResultProto.toPublic(
    operation: String,
): NativeOpenPgpEncryptResult {
    if (data.isEmpty()) malformedOpenPgp(operation)
    return NativeOpenPgpEncryptResult(
        data = data,
        protectionMode = protectionMode.toPublic(operation),
    )
}

private fun OpenPgpEncryptFinalProto.toPublic(
    operation: String,
): NativeOpenPgpEncryptFinal = NativeOpenPgpEncryptFinal(
    data = data,
    protectionMode = protectionMode.toPublic(operation),
)

internal fun OpenPgpDecryptFinalProto.toPublicDecryptFinal(
    operation: String,
): NativeOpenPgpDecryptFinal {
    var ownershipTransferred = false
    return try {
        NativeOpenPgpDecryptFinal(
            data = data,
            verification = verification?.toPublic(operation),
        ).also {
            ownershipTransferred = true
        }
    } finally {
        if (!ownershipTransferred) data.fill(0)
    }
}

private fun OpenPgpPublicKeyInfoProto.toPublic(
    operation: String,
): NativeOpenPgpPublicKeyInfo {
    requireOpenPgpFingerprint(operation, fingerprint)
    requireOpenPgpKeygrip(operation, keygrip)
    requireOpenPgpKeyId(operation, keyId)
    requireOpenPgpAlgorithm(operation, algorithm)
    requireOpenPgpBitStrength(operation, bitStrength)
    requireOpenPgpEpoch(operation, createdAtEpochSeconds)
    requireOpenPgpEpoch(operation, expiresAtEpochSeconds)
    if (publicKeyArmored.isEmpty()) malformedOpenPgp(operation)
    return NativeOpenPgpPublicKeyInfo(
        fingerprint = fingerprint,
        keygrip = keygrip,
        keyId = keyId,
        algorithm = algorithm,
        bitStrength = bitStrength,
        userIds = userIds,
        emails = emails,
        createdAtEpochSeconds = createdAtEpochSeconds,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        revoked = revoked,
        canSign = canSign,
        canEncrypt = canEncrypt,
        publicKeyArmored = publicKeyArmored,
        subkeys = subkeys.map { value -> value.toPublic(operation) },
    )
}

private fun OpenPgpPublicSubKeyInfoProto.toPublic(
    operation: String,
): NativeOpenPgpPublicSubKeyInfo {
    requireOpenPgpFingerprint(operation, fingerprint)
    requireOpenPgpKeygrip(operation, keygrip)
    requireOpenPgpKeyId(operation, keyId)
    requireOpenPgpAlgorithm(operation, algorithm)
    requireOpenPgpBitStrength(operation, bitStrength)
    requireOpenPgpEpoch(operation, createdAtEpochSeconds)
    requireOpenPgpEpoch(operation, expiresAtEpochSeconds)
    return NativeOpenPgpPublicSubKeyInfo(
        fingerprint = fingerprint,
        keygrip = keygrip,
        keyId = keyId,
        algorithm = algorithm,
        bitStrength = bitStrength,
        canSign = canSign,
        canEncrypt = canEncrypt,
        revoked = revoked,
        createdAtEpochSeconds = createdAtEpochSeconds,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )
}

private fun OpenPgpPublicKeyParseErrorReasonProto.toPublic(
    operation: String,
): NativeOpenPgpPublicKeyParseError = when (this) {
    OpenPgpPublicKeyParseErrorReasonProto.EMPTY -> NativeOpenPgpPublicKeyParseError.EMPTY
    OpenPgpPublicKeyParseErrorReasonProto.MALFORMED -> NativeOpenPgpPublicKeyParseError.MALFORMED
    OpenPgpPublicKeyParseErrorReasonProto.UNSUPPORTED_KEY_VERSION ->
        NativeOpenPgpPublicKeyParseError.UNSUPPORTED_KEY_VERSION

    OpenPgpPublicKeyParseErrorReasonProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

internal fun decodeOpenPgpVerification(
    operation: String,
    payload: ByteArray,
): NativeOpenPgpVerification = decodePayload<OpenPgpVerificationProto>(
    operation = operation,
    payload = payload,
).toPublic(operation)

private fun OpenPgpVerificationProto.toPublic(
    operation: String,
): NativeOpenPgpVerification {
    requireOpenPgpKeyId(operation, keyId)
    fingerprint?.let { value -> requireOpenPgpFingerprint(operation, value) }
    requireOpenPgpEpoch(operation, createdAtEpochSeconds)
    val publicStatus = when (status) {
        OpenPgpVerificationStatusProto.VALID -> NativeOpenPgpVerificationStatus.VALID
        OpenPgpVerificationStatusProto.INVALID -> NativeOpenPgpVerificationStatus.INVALID
        OpenPgpVerificationStatusProto.MISSING_PUBLIC_KEY ->
            NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY

        OpenPgpVerificationStatusProto.UNSPECIFIED -> malformedOpenPgp(operation)
    }
    val publicWarnings = warnings.map { wireValue ->
        when (
            OpenPgpVerificationWarningProto.fromWireValue(wireValue)
                ?: malformedOpenPgp(operation)
        ) {
            OpenPgpVerificationWarningProto.KEY_REVOKED ->
                NativeOpenPgpVerificationWarning.KEY_REVOKED

            OpenPgpVerificationWarningProto.KEY_EXPIRED ->
                NativeOpenPgpVerificationWarning.KEY_EXPIRED

            OpenPgpVerificationWarningProto.SIGNATURE_EXPIRED ->
                NativeOpenPgpVerificationWarning.SIGNATURE_EXPIRED

            OpenPgpVerificationWarningProto.UNSPECIFIED -> malformedOpenPgp(operation)
        }
    }
    if (publicWarnings.toSet().size != publicWarnings.size) {
        malformedOpenPgp(operation)
    }
    when (publicStatus) {
        NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY -> {
            if (fingerprint != null || userIds.isNotEmpty() || publicWarnings.isNotEmpty()) {
                malformedOpenPgp(operation)
            }
        }

        NativeOpenPgpVerificationStatus.VALID,
        NativeOpenPgpVerificationStatus.INVALID,
            -> if (fingerprint == null) malformedOpenPgp(operation)
    }
    return NativeOpenPgpVerification(
        status = publicStatus,
        keyId = keyId,
        fingerprint = fingerprint,
        userIds = userIds,
        createdAtEpochSeconds = createdAtEpochSeconds,
        warnings = publicWarnings,
    )
}

private fun OpenPgpKeyMetadataProto.toPublic(
    operation: String,
): NativeOpenPgpKeyMetadata {
    if (version != 1 || keys.isEmpty()) malformedOpenPgp(operation)
    return NativeOpenPgpKeyMetadata(
        version = version,
        keys = keys.map { value ->
            requireOpenPgpKeygrip(operation, value.keygrip)
            requireOpenPgpFingerprint(operation, value.fingerprint)
            requireOpenPgpAlgorithm(operation, value.algorithm)
            val capabilities = value.capabilities.toSet()
            if (
                capabilities.size != value.capabilities.size ||
                capabilities.any { capability -> capability != "sign" && capability != "decrypt" }
            ) {
                malformedOpenPgp(operation)
            }
            NativeOpenPgpKeyMetadataKey(
                keygrip = value.keygrip,
                fingerprint = value.fingerprint,
                algorithm = value.algorithm,
                capabilities = capabilities,
            )
        },
    )
}

private inline fun <reified T> decodePayload(
    operation: String,
    payload: ByteArray,
): T = try {
    ProtoBuf.decodeFromByteArray<T>(payload)
} catch (_: SerializationException) {
    malformedOpenPgp(operation)
} catch (_: IllegalArgumentException) {
    malformedOpenPgp(operation)
} finally {
    payload.fill(0)
}

private fun requireReferenceTime(value: Long?) {
    require(value == null || value >= 0L) { "OpenPGP reference time must not be negative" }
}

private fun requireSigningInputs(
    privateKey: ByteArray,
    preferredFingerprint: String,
    signatureTimeEpochSeconds: Long?,
    referenceTimeEpochSeconds: Long?,
) {
    require(privateKey.isNotEmpty()) { "OpenPGP private key must not be empty" }
    requirePreferredFingerprint(preferredFingerprint)
    requireOptionalOpenPgpTime("signature", signatureTimeEpochSeconds)
    requireReferenceTime(referenceTimeEpochSeconds)
}

private fun requireEncryptInputs(
    publicKeys: List<ByteArray>,
    signingPrivateKey: ByteArray?,
    preferredSigningFingerprint: String,
    fileName: String,
    literalTimeEpochSeconds: Long?,
    referenceTimeEpochSeconds: Long?,
) {
    require(publicKeys.isNotEmpty() && publicKeys.all { key -> key.isNotEmpty() }) {
        "At least one non-empty OpenPGP public key is required"
    }
    require(signingPrivateKey == null || signingPrivateKey.isNotEmpty()) {
        "OpenPGP signing private key must not be empty"
    }
    require(signingPrivateKey != null || preferredSigningFingerprint.isEmpty()) {
        "A preferred signing fingerprint requires a signing private key"
    }
    requirePreferredFingerprint(preferredSigningFingerprint)
    require(fileName.isNotBlank()) { "OpenPGP literal file name must not be blank" }
    requireOptionalOpenPgpTime("literal", literalTimeEpochSeconds)
    requireReferenceTime(referenceTimeEpochSeconds)
}

private fun requireDecryptInputs(
    privateKeys: List<ByteArray>,
    referenceTimeEpochSeconds: Long?,
) {
    require(privateKeys.isNotEmpty() && privateKeys.all { key -> key.isNotEmpty() }) {
        "At least one non-empty OpenPGP private key is required"
    }
    requireReferenceTime(referenceTimeEpochSeconds)
}

private fun requirePreferredFingerprint(value: String) {
    require(
        value.isEmpty() ||
            (value.length in 32..128 && value.length % 2 == 0 && value.isUpperHex()),
    ) { "Invalid preferred OpenPGP fingerprint" }
}

private fun requireOptionalOpenPgpTime(label: String, value: Long?) {
    require(value == null || value >= 0L) { "OpenPGP $label time must not be negative" }
}

private fun requireOpenPgpEpoch(operation: String, value: Long?) {
    if (value != null && value < 0L) malformedOpenPgp(operation)
}

private fun requireOpenPgpBitStrength(operation: String, value: Int?) {
    if (value != null && value <= 0) malformedOpenPgp(operation)
}

private fun requireOpenPgpAlgorithm(operation: String, value: String) {
    if (value.isEmpty()) malformedOpenPgp(operation)
}

private fun requireOpenPgpKeyId(operation: String, value: String) {
    if (value.length != 16 || !value.isUpperHex()) malformedOpenPgp(operation)
}

private fun requireOpenPgpFingerprint(operation: String, value: String) {
    if (value.length !in 32..128 || value.length % 2 != 0 || !value.isUpperHex()) {
        malformedOpenPgp(operation)
    }
}

private fun requireOpenPgpKeygrip(operation: String, value: String?) {
    if (value != null && (value.length != 40 || !value.isUpperHex())) {
        malformedOpenPgp(operation)
    }
}

private fun String.isUpperHex(): Boolean = all { character ->
    character in '0'..'9' || character in 'A'..'F'
}

private fun malformedOpenPgp(operation: String): Nothing = throw NativeCryptoException(
    operation = operation,
    code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
)
