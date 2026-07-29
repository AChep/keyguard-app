package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifier
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyDetachedTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPublicKeyInfo
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPublicKeyParseError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPublicKeyParseResult
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPublicSubKeyInfo
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerification
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerificationStatus
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerificationWarning
import com.artemchep.keyguard.util.io.consumeWithErasedBuffer
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.time.Instant

object NativeGpgPublicKeyParser : GpgPublicKeyParser {
    override fun parse(
        armored: String,
    ): GpgPublicKeyParseResult {
        if (armored.isBlank()) {
            return GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Empty)
        }
        val keyData = armored.encodeToByteArray()
        return try {
            when (val result = NativeCrypto.openPgp.parsePublicKeys(keyData)) {
                is NativeOpenPgpPublicKeyParseResult.Success ->
                    GpgPublicKeyParseResult.Success(result.keys.map { it.toDomain() })

                is NativeOpenPgpPublicKeyParseResult.Error -> GpgPublicKeyParseResult.Error(
                    reason = when (result.reason) {
                        NativeOpenPgpPublicKeyParseError.EMPTY -> GpgPublicKeyParseError.Empty
                        NativeOpenPgpPublicKeyParseError.MALFORMED -> GpgPublicKeyParseError.Malformed
                        NativeOpenPgpPublicKeyParseError.UNSUPPORTED_KEY_VERSION ->
                            GpgPublicKeyParseError.UnsupportedKeyVersion
                    },
                )
            }
        } catch (failure: NativeCryptoException) {
            if (failure.code != NativeCryptoErrorCode.RESOURCE_LIMIT) {
                throw failure
            }
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed)
        } finally {
            keyData.fill(0)
        }
    }
}

object NativeGpgKeyMetadataResolver : GpgKeyMetadataResolver {
    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgAgentKeyMetadata? {
        val normalizedFingerprint = fingerprint
            ?.normalizeGpgFingerprint()
            .orEmpty()
        if (normalizedFingerprint.any { character -> !character.isAsciiHexDigit() }) {
            return null
        }
        val privateKeyData = privateKeyArmored
            ?.takeIf { it.isNotBlank() }
            ?.encodeToByteArray()
        val publicKeyData = publicKeyArmored
            ?.takeIf { it.isNotBlank() }
            ?.encodeToByteArray()
        val candidates = candidateRevocationKeys
            .clampToNativeOpenPgpKeyLimit()
            .map { key -> key.armored.encodeToByteArray() }
        return try {
            NativeCrypto.openPgp.resolveMetadata(
                privateKeyData = privateKeyData,
                publicKeyData = publicKeyData,
                normalizedFingerprint = normalizedFingerprint,
                candidateRevocationKeys = candidates,
            )?.toDomain()
        } finally {
            privateKeyData?.fill(0)
            publicKeyData?.fill(0)
            candidates.forEach { candidate -> candidate.fill(0) }
        }
    }
}

object NativeGpgOpenPgpVerifier : GpgOpenPgpVerifier {
    override fun verifyClearSignedText(
        request: GpgOpenPgpVerifyTextRequest,
    ): GpgOpenPgpVerification {
        val signedDocument = request.signedText.encodeToByteArray()
        return try {
            request.publicKeys.withEncodedPublicKeys { publicKeys ->
                NativeCrypto.openPgp.verifyClearSigned(
                    signedDocument = signedDocument,
                    publicKeys = publicKeys,
                ).toDomain()
            }
        } finally {
            signedDocument.fill(0)
        }
    }

    override fun verifyDetachedText(
        request: GpgOpenPgpVerifyDetachedTextRequest,
    ): GpgOpenPgpVerification {
        val content = request.text.encodeToByteArray()
        val signature = request.signature.encodeToByteArray()
        return try {
            request.publicKeys.withEncodedPublicKeys { publicKeys ->
                NativeCrypto.openPgp.verifyDetached(
                    content = content,
                    signature = signature,
                    publicKeys = publicKeys,
                ).toDomain()
            }
        } finally {
            content.fill(0)
            signature.fill(0)
        }
    }

    override fun verifyFile(
        request: GpgOpenPgpVerifyFileRequest,
    ): GpgOpenPgpVerification = request.input.use { input ->
        val signature = request.signatureInput.readBoundedAndClose(
            limit = NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES / 2,
            operation = OPEN_PGP_DETACHED_VERIFY_STREAM_OPEN_OPERATION,
        )
        try {
            request.publicKeys.withEncodedPublicKeys { publicKeys ->
                NativeCrypto.openPgp.openDetachedVerification(
                    signature = signature,
                    publicKeys = publicKeys,
                ).use { session ->
                    input.consumeWithErasedBuffer { buffer, length ->
                        session.update(buffer, length = length)
                    }
                    session.finish().toDomain()
                }
            }
        } finally {
            signature.fill(0)
        }
    }

    private const val OPEN_PGP_DETACHED_VERIFY_STREAM_OPEN_OPERATION =
        "open_pgp_detached_verify.stream_open"
}

private fun Char.isAsciiHexDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'F'

private fun Source.readBoundedAndClose(
    limit: Int,
    operation: String,
): ByteArray = use { source ->
    val output = Buffer()
    var total = 0L
    try {
        source.consumeWithErasedBuffer(bufferSize = 64 * 1024) { buffer, length ->
            total += length
            if (total > limit) {
                throw NativeCryptoException(operation, NativeCryptoErrorCode.RESOURCE_LIMIT)
            }
            output.write(buffer, startIndex = 0, endIndex = length)
        }
        output.readByteArray()
    } finally {
        output.clear()
    }
}

private fun NativeOpenPgpPublicKeyInfo.toDomain(): GpgPublicKeyInfo = GpgPublicKeyInfo(
    fingerprint = fingerprint,
    keygrip = keygrip,
    keyId = keyId,
    algorithm = algorithm,
    bitStrength = bitStrength,
    userIds = userIds,
    emails = emails,
    createdAt = createdAtEpochSeconds?.let(Instant::fromEpochSeconds),
    expiresAt = expiresAtEpochSeconds?.let(Instant::fromEpochSeconds),
    revoked = revoked,
    canSign = canSign,
    canEncrypt = canEncrypt,
    publicKeyArmored = publicKeyArmored,
    subKeys = subkeys.map { it.toDomain() },
)

private fun NativeOpenPgpPublicSubKeyInfo.toDomain(): GpgPublicSubKeyInfo = GpgPublicSubKeyInfo(
    fingerprint = fingerprint,
    keygrip = keygrip,
    keyId = keyId,
    algorithm = algorithm,
    bitStrength = bitStrength,
    canSign = canSign,
    canEncrypt = canEncrypt,
    revoked = revoked,
    createdAt = createdAtEpochSeconds?.let(Instant::fromEpochSeconds),
    expiresAt = expiresAtEpochSeconds?.let(Instant::fromEpochSeconds),
)

internal fun NativeOpenPgpVerification.toDomain(): GpgOpenPgpVerification =
    GpgOpenPgpVerification(
        status = when (status) {
            NativeOpenPgpVerificationStatus.VALID -> GpgOpenPgpVerificationStatus.VALID
            NativeOpenPgpVerificationStatus.INVALID -> GpgOpenPgpVerificationStatus.INVALID
            NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY ->
                GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY
        },
        keyId = keyId,
        fingerprint = fingerprint,
        userIds = userIds,
        createdAt = createdAtEpochSeconds?.let(Instant::fromEpochSeconds),
        warnings = warnings.map { warning ->
            when (warning) {
                NativeOpenPgpVerificationWarning.KEY_REVOKED ->
                    GpgOpenPgpVerificationWarning.KEY_REVOKED

                NativeOpenPgpVerificationWarning.KEY_EXPIRED ->
                    GpgOpenPgpVerificationWarning.KEY_EXPIRED

                NativeOpenPgpVerificationWarning.SIGNATURE_EXPIRED ->
                    GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED
            }
        },
    )
