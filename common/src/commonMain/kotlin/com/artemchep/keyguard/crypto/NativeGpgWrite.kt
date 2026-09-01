package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.crypto.GPG_KEY_EXPIRATION_MAX_INSTANT
import com.artemchep.keyguard.common.service.crypto.GpgKeyGenerator
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportError
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpClearSignFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpCertificationAuthority
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptionWarning
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpExportPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpLiteralMetadata
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPrivateKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpReadFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpReadFileResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpUserIdCertificationRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifier
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.resolve
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgp
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificationAuthority
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpDecryptionWarning
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyImportError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyImportResult
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyKind
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyMaterial
import com.artemchep.keyguard.util.io.consumeWithErasedBuffer
import kotlinx.datetime.TimeZone
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.Instant

object NativeGpgKeyGenerator : GpgKeyGenerator {
    override fun generate(
        config: GpgKeyConfig,
    ): GeneratedGpgKey {
        val userId = config.userId.trim()
        require(userId.isNotEmpty()) { "GPG user ID must not be blank." }

        val creationTime = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val expirationSeconds = config.expiry
            .resolve(
                creationTime = creationTime,
                timeZone = TimeZone.currentSystemDefault(),
            )
            ?.let { target -> expirationSeconds(creationTime, target) }
        val material = NativeCrypto.openPgp.generateKey(
            kind = when (config) {
                is GpgKeyConfig.Modern -> NativeOpenPgpKeyKind.LEGACY_ED25519_X25519
                is GpgKeyConfig.Rsa -> NativeOpenPgpKeyKind.RSA
            },
            userId = userId,
            rsaBits = (config as? GpgKeyConfig.Rsa)?.length?.size ?: 0,
            creationTimeEpochSeconds = creationTime.epochSeconds,
            expirationSeconds = expirationSeconds,
        )
        return material.useArmoredStrings { privateKeyArmored, publicKeyArmored ->
            val metadata = NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = privateKeyArmored,
                publicKeyArmored = publicKeyArmored,
                fingerprint = material.fingerprint,
            )?.metadata ?: error("Could not resolve metadata for a generated GPG key.")
            GeneratedGpgKey(
                privateKeyArmored = privateKeyArmored,
                publicKeyArmored = publicKeyArmored,
                fingerprint = material.fingerprint,
                metadata = metadata,
                userId = userId,
                typeLabel = config.type.title,
            )
        }
    }

    private fun expirationSeconds(
        creationTime: Instant,
        target: Instant,
    ): Long {
        require(target <= GPG_KEY_EXPIRATION_MAX_INSTANT) {
            "GPG key expiry exceeds the supported protocol range."
        }
        val seconds = target.epochSeconds - creationTime.epochSeconds
        require(seconds in 1L..UInt.MAX_VALUE.toLong()) {
            "GPG key expiry must be after creation and fit the OpenPGP duration field."
        }
        return seconds
    }
}

/** Shared native implementation of passwordless OpenPGP key import. */
object NativeGpgKeyImportService : GpgKeyImportService {
    override fun import(
        request: GpgKeyImportRequest,
    ): GpgKeyImportResult {
        val content = request.content.trim()
        if (content.isEmpty()) {
            return GpgKeyImportResult.Error(GpgKeyImportError.Empty)
        }

        val keyData = content.encodeToByteArray()
        val passphrase = request.passphrase?.encodeToByteArray()
        return try {
            when (
                val result = NativeCrypto.openPgp.importKey(
                    keyData = keyData,
                    passphraseUtf8 = passphrase,
                )
            ) {
                is NativeOpenPgpKeyImportResult.Success -> importPrivateKey(result.keyMaterial)
                is NativeOpenPgpKeyImportResult.NeedsPassphrase ->
                    GpgKeyImportResult.NeedsPassphrase(result.formatLabel)

                is NativeOpenPgpKeyImportResult.Error -> when (result.reason) {
                    NativeOpenPgpKeyImportError.EMPTY ->
                        GpgKeyImportResult.Error(GpgKeyImportError.Empty)

                    NativeOpenPgpKeyImportError.UNSUPPORTED_FORMAT ->
                        importPublicKey(content).let { publicResult ->
                            if (
                                publicResult ==
                                GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
                            ) {
                                // The native secret-key parser also uses UNSUPPORTED_FORMAT for
                                // valid legacy v2/v3 secret packets. Preserve that classification
                                // when the public-key fallback cannot consume the same input.
                                GpgKeyImportResult.Error(GpgKeyImportError.UnsupportedFormat)
                            } else {
                                publicResult
                            }
                        }
                    NativeOpenPgpKeyImportError.INVALID_PASSPHRASE ->
                        GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase)

                    NativeOpenPgpKeyImportError.MALFORMED_KEY ->
                        GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
                }
            }
        } finally {
            keyData.fill(0)
            passphrase?.fill(0)
        }
    }

    private fun importPrivateKey(
        material: NativeOpenPgpKeyMaterial,
    ): GpgKeyImportResult = material.useArmoredStrings { privateKeyArmored, publicKeyArmored ->
        if (privateKeyArmored.isEmpty()) {
            return@useArmoredStrings GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
        }
        val keyInfo = publicKeyInfo(publicKeyArmored, material.fingerprint)
            ?: return@useArmoredStrings GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
        val metadata = NativeGpgKeyMetadataResolver.resolve(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = material.fingerprint,
        )?.metadata
            ?: return@useArmoredStrings GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
        GpgKeyImportResult.Success(
            GeneratedGpgKey(
                privateKeyArmored = privateKeyArmored,
                publicKeyArmored = publicKeyArmored,
                fingerprint = material.fingerprint,
                metadata = metadata,
                userId = keyInfo.userIds.firstOrNull().orEmpty(),
                typeLabel = keyInfo.algorithm,
            ),
        )
    }

    private fun importPublicKey(
        content: String,
    ): GpgKeyImportResult = when (val parsed = NativeGpgPublicKeyParser.parse(content)) {
        is GpgPublicKeyParseResult.Success -> {
            val containsMultipleCertificates = parsed.keys.size > 1 ||
                parsed.keys.isNotEmpty() && parsed.skippedCertificates > 0
            if (containsMultipleCertificates) {
                return GpgKeyImportResult.Error(GpgKeyImportError.MultipleKeys)
            }
            val key = parsed.keys.singleOrNull()
                ?: return GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
            val metadata = NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = null,
                publicKeyArmored = key.publicKeyArmored,
                fingerprint = key.fingerprint,
            )?.metadata
                ?: return GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
            GpgKeyImportResult.Success(
                GeneratedGpgKey(
                    privateKeyArmored = "",
                    publicKeyArmored = key.publicKeyArmored,
                    fingerprint = key.fingerprint,
                    metadata = metadata,
                    userId = key.userIds.firstOrNull().orEmpty(),
                    typeLabel = key.algorithm,
                ),
            )
        }

        is GpgPublicKeyParseResult.Error -> GpgKeyImportResult.Error(
            reason = when (parsed.reason) {
                GpgPublicKeyParseError.Empty -> GpgKeyImportError.Empty
                GpgPublicKeyParseError.Malformed -> GpgKeyImportError.MalformedKey
                GpgPublicKeyParseError.UnsupportedKeyVersion -> GpgKeyImportError.UnsupportedFormat
                GpgPublicKeyParseError.MultipleCertificates -> GpgKeyImportError.MultipleKeys
                GpgPublicKeyParseError.Unsupported -> GpgKeyImportError.UnsupportedPlatform
            },
        )
    }

    private fun publicKeyInfo(
        publicKeyArmored: String,
        fingerprint: String,
    ): GpgPublicKeyInfo? = (NativeGpgPublicKeyParser.parse(publicKeyArmored) as? GpgPublicKeyParseResult.Success)
        ?.keys
        ?.firstOrNull { key -> key.fingerprint == fingerprint }
}

private const val MAX_CERTIFICATION_AUTHORITIES_PER_REQUEST =
    NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST - 1
private const val CERTIFICATION_EVALUATION_OPERATION =
    "open_pgp_user_id_certification_evaluate"

/**
 * Coalesces revisions of each explicitly trusted primary certificate so they
 * can be evaluated together, in one request, with normalized fingerprints.
 *
 * Certification policy is not additive across requests: every authority document may also carry
 * newer revisions and designated-revoker candidates needed by another authority. Keeping one
 * request ensures that native certificate merging and policy evaluation see the complete bounded
 * candidate set. If the coalesced roots cannot fit beside the target certificate, fail closed.
 */
internal fun List<GpgOpenPgpCertificationAuthority>.coalesceCertificationAuthorities():
    List<GpgOpenPgpCertificationAuthority> =
    groupBy { authority ->
        authority.primaryFingerprint.normalizeGpgFingerprint()
    }.entries
        .sortedBy { (fingerprint, _) -> fingerprint }
        .map { (fingerprint, revisions) ->
            val publicKeyArmored =
                revisions
                    .map { authority -> authority.publicKey.armored }
                    .distinct()
                    .sorted()
                    .joinToString(separator = "\n")
            GpgOpenPgpCertificationAuthority(
                publicKey = GpgOpenPgpPublicKey(publicKeyArmored),
                primaryFingerprint = fingerprint,
            )
        }
        .requireNativeOpenPgpKeyDocumentLimit(
            operation = CERTIFICATION_EVALUATION_OPERATION,
            max = MAX_CERTIFICATION_AUTHORITIES_PER_REQUEST,
        )

class NativeGpgOpenPgpService internal constructor(
    private val stagingSpoolFactory: StagingSpoolFactory =
        DefaultStagingSpoolFactory(),
    verifier: GpgOpenPgpVerifier = NativeGpgOpenPgpVerifier,
) : GpgOpenPgpService,
    GpgOpenPgpVerifier by verifier {
    constructor(
        directDI: DirectDI,
    ) : this(
        stagingSpoolFactory = directDI.instance(),
    )

    override fun evaluateUserIdCertifications(
        request: GpgOpenPgpUserIdCertificationRequest,
    ): List<String> {
        // At most MAX_KEY_DOCUMENTS_PER_REQUEST - 1 coalesced authorities, so
        // neither withEncodedPublicKeys clamp below can drop a document.
        val authorities = request.authorities.coalesceCertificationAuthorities()
        return listOf(request.publicKey).withEncodedPublicKeys { publicKeys ->
            authorities.map { authority -> authority.publicKey }
                .withEncodedPublicKeys { authorityKeys ->
                    NativeCrypto.openPgp.evaluateUserIdCertifications(
                        publicKey = publicKeys.single(),
                        authorities = authorities.zip(authorityKeys) { authority, keyData ->
                            NativeOpenPgpCertificationAuthority(
                                publicKey = keyData,
                                primaryFingerprint = authority.primaryFingerprint,
                            )
                        },
                        referenceTimeEpochSeconds = request.referenceTime.epochSeconds,
                    )
                }
        }
    }

    override fun clearSignText(
        request: GpgOpenPgpSignTextRequest,
    ): String = signText(request) { content, privateKey, candidateRevocationKeys,
                                     preferredFingerprint, referenceTimeEpochSeconds ->
        NativeCrypto.openPgp.clearSign(
            content = content,
            privateKey = privateKey,
            candidateRevocationKeys = candidateRevocationKeys,
            preferredFingerprint = preferredFingerprint,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
    }

    override fun signTextDetached(
        request: GpgOpenPgpSignTextRequest,
    ): String = signText(request) { content, privateKey, candidateRevocationKeys,
                                     preferredFingerprint, referenceTimeEpochSeconds ->
        NativeCrypto.openPgp.signDetached(
            content = content,
            privateKey = privateKey,
            candidateRevocationKeys = candidateRevocationKeys,
            preferredFingerprint = preferredFingerprint,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
    }

    override fun encryptText(
        request: GpgOpenPgpEncryptTextRequest,
    ): String = request.publicKeys.withEncodedPublicKeys { publicKeys ->
        request.signingPrivateKey.withOptionalEncoded { signingPrivateKey, preferredFingerprint ->
            request.candidateRevocationKeys.withEncodedRevocationKeyCandidates(
                targetPrivateKeys = listOfNotNull(
                    signingPrivateKey?.let { keyData ->
                        EncodedRevocationTarget(keyData, preferredFingerprint)
                    },
                ),
                targetPublicKeys = publicKeys.map { keyData -> EncodedRevocationTarget(keyData) },
            ) { candidateRevocationKeys, referenceTimeEpochSeconds ->
                val content = request.text.encodeToByteArray()
                try {
                    val result = translateNativeOpenPgpWriteError(
                        noUsableKeyMeansLegacyFailure = true,
                    ) {
                        NativeCrypto.openPgp.encrypt(
                            content = content,
                            publicKeys = publicKeys,
                            candidateRevocationKeys = candidateRevocationKeys,
                            signingPrivateKey = signingPrivateKey,
                            preferredSigningFingerprint = preferredFingerprint,
                            fileName = "",
                            armored = true,
                            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                        )
                    }
                    result.data.decodeAndErase()
                } finally {
                    content.fill(0)
                }
            }
        }
    }

    override fun decryptText(
        request: GpgOpenPgpDecryptTextRequest,
    ): GpgOpenPgpDecryptTextResult {
        check(request.privateKeys.isNotEmpty()) { "At least one OpenPGP private key is required." }
        return request.privateKeys.withEncodedPrivateKeys { privateKeys ->
            request.publicKeys.withEncodedPublicKeys { publicKeys ->
                val content = request.encryptedText.encodeToByteArray()
                try {
                    val result = translateNativeOpenPgpWriteError {
                        NativeCrypto.openPgp.decrypt(
                            content = content,
                            privateKeys = privateKeys,
                            verificationPublicKeys = publicKeys,
                        )
                    }
                    GpgOpenPgpDecryptTextResult(
                        text = result.data.decodeAndErase(),
                        verification = result.verification?.toDomain(),
                        decryptionKeyFingerprint = result.decryptionKeyFingerprint,
                        warnings = result.warnings.map(NativeOpenPgpDecryptionWarning::toDomain),
                    )
                } finally {
                    content.fill(0)
                }
            }
        }
    }

    override fun exportPublicKey(
        request: GpgOpenPgpExportPublicKeyRequest,
    ) {
        requireSinglePublicKeyArmor(request.publicKey.armored)
        val parsed = NativeGpgPublicKeyParser.parse(request.publicKey.armored)
        val publicKey = (parsed as? GpgPublicKeyParseResult.Success)
            ?.keys
            ?.singleOrNull()
            ?: throw IllegalArgumentException("Expected exactly one valid OpenPGP public key.")
        request.output.use { output ->
            val data = if (request.armored) {
                publicKey.publicKeyArmored.encodeToByteArray()
            } else {
                decodeCanonicalPublicKeyArmor(publicKey.publicKeyArmored)
            }
            writeAndErase(output, data)
            output.flush()
        }
    }

    override fun signFile(
        request: GpgOpenPgpSignFileRequest,
    ) = signFileStream(
        input = request.input,
        output = request.signatureOutput,
        privateKey = request.privateKey,
        candidateRevocationKeys = request.candidateRevocationKeys,
    ) { input, output, privateKey, candidateRevocationKeys,
        preferredFingerprint, referenceTimeEpochSeconds ->
        NativeCrypto.openPgp.openDetachedSigning(
            privateKey = privateKey,
            candidateRevocationKeys = candidateRevocationKeys,
            preferredFingerprint = preferredFingerprint,
            armored = request.armored,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        ).use { session ->
            input.consumeWithErasedBuffer { data, length ->
                session.update(data, length = length)
            }
            val signature = session.finish()
            writeAndErase(output, signature)
            output.flush()
        }
    }

    override fun clearSignFile(
        request: GpgOpenPgpClearSignFileRequest,
    ) = signFileStream(
        input = request.input,
        output = request.output,
        privateKey = request.privateKey,
        candidateRevocationKeys = request.candidateRevocationKeys,
    ) { input, output, privateKey, candidateRevocationKeys,
        preferredFingerprint, referenceTimeEpochSeconds ->
        NativeCrypto.openPgp.openClearSigning(
            privateKey = privateKey,
            candidateRevocationKeys = candidateRevocationKeys,
            preferredFingerprint = preferredFingerprint,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        ).use { session ->
            input.consumeWithErasedBuffer { data, length ->
                writeAndErase(output, session.update(data, length = length))
            }
            writeAndErase(output, session.finish())
            output.flush()
        }
    }

    private fun signFileStream(
        input: Source,
        output: Sink,
        privateKey: GpgOpenPgpPrivateKey,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
        sign: (
            input: Source,
            output: Sink,
            privateKey: ByteArray,
            candidateRevocationKeys: List<ByteArray>,
            preferredFingerprint: String,
            referenceTimeEpochSeconds: Long,
        ) -> Unit,
    ) {
        input.use {
            output.use {
                privateKey.withEncoded { keyData, preferredFingerprint ->
                    candidateRevocationKeys.withEncodedRevocationKeyCandidates(
                        targetPrivateKeys = listOf(
                            EncodedRevocationTarget(keyData, preferredFingerprint),
                        ),
                    ) { encodedCandidates, referenceTimeEpochSeconds ->
                        translateNativeOpenPgpWriteError {
                            sign(
                                input,
                                output,
                                keyData,
                                encodedCandidates,
                                preferredFingerprint,
                                referenceTimeEpochSeconds,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun verifyClearSignedFile(
        request: GpgOpenPgpReadFileRequest,
    ): GpgOpenPgpReadFileResult.ClearSigned = request.output.use { output ->
        // The recovered body is staged and only released to the caller's
        // sink after the trailing signature has been checked.
        withStagedOpenPgpPlaintext(
            output = output,
            stagingSpoolFactory = stagingSpoolFactory,
        ) { stagedOutput ->
            request.input.use { input ->
                request.publicKeys.withEncodedPublicKeys { publicKeys ->
                    NativeCrypto.openPgp.openClearVerification(
                        publicKeys = publicKeys,
                    ).use { session ->
                        var bodySize = 0L
                        input.consumeWithErasedBuffer { data, length ->
                            val body = session.update(data, length = length)
                            bodySize += body.size
                            writeAndErase(stagedOutput, body)
                        }
                        val result = session.finish()
                        stagedOutput.flush()
                        GpgOpenPgpReadFileResult.ClearSigned(
                            verification = result.verification.toDomain(),
                            bodyValidUtf8 = result.bodyValidUtf8,
                            bodySize = bodySize,
                        )
                    }
                }
            }
        }
    }

    override fun encryptFile(
        request: GpgOpenPgpEncryptFileRequest,
    ) {
        request.input.use { input ->
            request.output.use { output ->
                request.publicKeys.withEncodedPublicKeys { publicKeys ->
                    request.signingPrivateKey.withOptionalEncoded { signingPrivateKey, preferredFingerprint ->
                        request.candidateRevocationKeys.withEncodedRevocationKeyCandidates(
                            targetPrivateKeys = listOfNotNull(
                                signingPrivateKey?.let { keyData ->
                                    EncodedRevocationTarget(keyData, preferredFingerprint)
                                },
                            ),
                            targetPublicKeys = publicKeys.map { keyData ->
                                EncodedRevocationTarget(keyData)
                            },
                        ) { candidateRevocationKeys, referenceTimeEpochSeconds ->
                            translateNativeOpenPgpWriteError(
                                noUsableKeyMeansLegacyFailure = true,
                            ) {
                                NativeCrypto.openPgp.openEncryption(
                                    publicKeys = publicKeys,
                                    candidateRevocationKeys = candidateRevocationKeys,
                                    signingPrivateKey = signingPrivateKey,
                                    preferredSigningFingerprint = preferredFingerprint,
                                    fileName = request.fileName.value,
                                    armored = request.armored,
                                    enableCompression = request.enableCompression,
                                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                                ).use { session ->
                                    input.consumeWithErasedBuffer { data, length ->
                                        writeAndErase(output, session.update(data, length = length))
                                    }
                                    val final = session.finish()
                                    writeAndErase(output, final.data)
                                    output.flush()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun decryptFile(
        request: GpgOpenPgpReadFileRequest,
    ): GpgOpenPgpReadFileResult.Message {
        check(request.allowSignedOnly || request.privateKeys.isNotEmpty()) {
            "At least one OpenPGP private key is required."
        }
        return request.output.use { output ->
            withStagedOpenPgpPlaintext(
                output = output,
                stagingSpoolFactory = stagingSpoolFactory,
            ) { stagedOutput ->
                request.input.use { input ->
                    request.privateKeys.withEncodedPrivateKeys { privateKeys ->
                        request.publicKeys.withEncodedPublicKeys { publicKeys ->
                            translateNativeOpenPgpWriteError {
                                NativeCrypto.openPgp.openDecryption(
                                    privateKeys = privateKeys,
                                    verificationPublicKeys = publicKeys,
                                    allowSignedOnly = request.allowSignedOnly,
                                ).use { session ->
                                    input.consumeWithErasedBuffer { data, length ->
                                        writeAndErase(stagedOutput, session.update(data, length = length))
                                    }
                                    val final = session.finish()
                                    writeAndErase(stagedOutput, final.data)
                                    stagedOutput.flush()
                                    GpgOpenPgpReadFileResult.Message(
                                        verification = final.verification?.toDomain(),
                                        metadata = final.metadata?.let { metadata ->
                                            GpgOpenPgpLiteralMetadata(
                                                fileName = metadata.fileName,
                                                format = metadata.format,
                                                modificationTimeEpochSeconds =
                                                    metadata.modificationTimeEpochSeconds,
                                                originalSize = metadata.originalSize,
                                            )
                                        },
                                        encrypted = final.encrypted,
                                        declaredCharset = final.declaredCharset,
                                        decryptionKeyFingerprint =
                                            final.decryptionKeyFingerprint,
                                        warnings = final.warnings.map(
                                            NativeOpenPgpDecryptionWarning::toDomain,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun signText(
    request: GpgOpenPgpSignTextRequest,
    sign: (
        content: ByteArray,
        privateKey: ByteArray,
        candidateRevocationKeys: List<ByteArray>,
        preferredFingerprint: String,
        referenceTimeEpochSeconds: Long,
    ) -> ByteArray,
): String = request.privateKey.withEncoded { privateKey, preferredFingerprint ->
    request.candidateRevocationKeys.withEncodedRevocationKeyCandidates(
        targetPrivateKeys = listOf(EncodedRevocationTarget(privateKey, preferredFingerprint)),
    ) { candidateRevocationKeys, referenceTimeEpochSeconds ->
        val content = request.text.encodeToByteArray()
        try {
            translateNativeOpenPgpWriteError {
                sign(
                    content,
                    privateKey,
                    candidateRevocationKeys,
                    preferredFingerprint,
                    referenceTimeEpochSeconds,
                )
            }.decodeAndErase()
        } finally {
            content.fill(0)
        }
    }
}

internal fun NativeOpenPgpDecryptionWarning.toDomain(): GpgOpenPgpDecryptionWarning = when (this) {
    NativeOpenPgpDecryptionWarning.WEAK_RSA_KEY -> GpgOpenPgpDecryptionWarning.WEAK_RSA_KEY
    NativeOpenPgpDecryptionWarning.ELGAMAL_KEY -> GpgOpenPgpDecryptionWarning.ELGAMAL_KEY
}

private fun requireSinglePublicKeyArmor(armored: String) {
    val lines = armored.lineSequence().map(String::trim).toList()
    require(lines.count { it == PUBLIC_KEY_ARMOR_BEGIN } == 1) {
        "Expected exactly one OpenPGP public-key armor header."
    }
    require(lines.count { it == PUBLIC_KEY_ARMOR_END } == 1) {
        "Expected exactly one OpenPGP public-key armor footer."
    }
}

private inline fun <T> GpgOpenPgpPrivateKey.withEncoded(
    block: (keyData: ByteArray, preferredFingerprint: String) -> T,
): T {
    val keyData = armored.encodeToByteArray()
    return try {
        block(
            keyData,
            preferredFingerprint
                ?.normalizeGpgFingerprint()
                .orEmpty(),
        )
    } finally {
        keyData.fill(0)
    }
}

private inline fun <T> GpgOpenPgpPrivateKey?.withOptionalEncoded(
    block: (keyData: ByteArray?, preferredFingerprint: String) -> T,
): T = if (this != null) {
    withEncoded(block)
} else {
    block(null, "")
}

private inline fun <T> List<GpgOpenPgpPrivateKey>.withEncodedPrivateKeys(
    block: (List<ByteArray>) -> T,
): T {
    val keyData = clampToNativeOpenPgpKeyLimit()
        .map { key -> key.armored.encodeToByteArray() }
    return try {
        block(keyData)
    } finally {
        keyData.eraseAll()
    }
}

private fun writeAndErase(
    output: Sink,
    data: ByteArray,
) {
    try {
        output.write(data)
    } finally {
        data.fill(0)
    }
}

private fun ByteArray.decodeAndErase(): String = try {
    decodeToString(throwOnInvalidSequence = true)
} finally {
    fill(0)
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeCanonicalPublicKeyArmor(
    armored: String,
): ByteArray {
    val lines = armored.lineSequence().toList()
    require(lines.firstOrNull() == PUBLIC_KEY_ARMOR_BEGIN) {
        "Native public-key parser returned an invalid armor header."
    }
    require(lines.lastOrNull { it.isNotEmpty() } == PUBLIC_KEY_ARMOR_END) {
        "Native public-key parser returned an invalid armor footer."
    }

    val separatorIndex = lines.indexOfFirst(String::isEmpty)
    require(separatorIndex > 0) {
        "Native public-key parser returned armor without a header separator."
    }
    val payload = lines
        .asSequence()
        .drop(separatorIndex + 1)
        .takeWhile { line -> line != PUBLIC_KEY_ARMOR_END }
        .filterNot { line -> line.startsWith('=') }
        .joinToString(separator = "")
    require(payload.isNotEmpty()) {
        "Native public-key parser returned empty public-key armor."
    }
    return Base64.Default.decode(payload)
}

internal inline fun <T> translateNativeOpenPgpWriteError(
    noUsableKeyMeansLegacyFailure: Boolean = false,
    block: () -> T,
): T = try {
    block()
} catch (failure: NativeCryptoException) {
    if (failure.code == NativeCryptoErrorCode.UNSUPPORTED_KEY_VERSION) {
        throw GpgUnsupportedKeyVersionException(version = 3)
    }
    if (
        noUsableKeyMeansLegacyFailure &&
        failure.code == NativeCryptoErrorCode.NO_USABLE_KEY
    ) {
        throw IllegalStateException("No usable OpenPGP key is available.", failure)
    }
    throw failure
}

private const val PUBLIC_KEY_ARMOR_BEGIN = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
private const val PUBLIC_KEY_ARMOR_END = "-----END PGP PUBLIC KEY BLOCK-----"
