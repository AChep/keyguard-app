package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.crypto.GPG_KEY_EXPIRATION_MAX_INSTANT
import com.artemchep.keyguard.common.service.crypto.GpgKeyGenerator
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportError
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptFileResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPrivateKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyDetachedTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.resolve
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyImportError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyImportResult
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyKind
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyMaterial
import com.artemchep.keyguard.util.foundation.io.consumeWithErasedBuffer
import kotlinx.datetime.TimeZone
import kotlinx.io.Sink
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
            ) ?: error("Could not resolve metadata for a generated GPG key.")
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
        ) ?: GpgAgentKeyMetadata()
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
            val key = parsed.keys.firstOrNull()
                ?: return GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
            val metadata = NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = null,
                publicKeyArmored = key.publicKeyArmored,
                fingerprint = key.fingerprint,
            ) ?: key.toMetadata()
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

object NativeGpgOpenPgpService : GpgOpenPgpService {
    override fun clearSignText(
        request: GpgOpenPgpSignTextRequest,
    ): String = request.privateKey.withEncoded { privateKey, preferredFingerprint ->
        val content = request.text.encodeToByteArray()
        try {
            translateNativeOpenPgpWriteError {
                NativeCrypto.openPgp.clearSign(
                    content = content,
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                )
            }.decodeAndErase()
        } finally {
            content.fill(0)
        }
    }

    override fun signTextDetached(
        request: GpgOpenPgpSignTextRequest,
    ): String = request.privateKey.withEncoded { privateKey, preferredFingerprint ->
        val content = request.text.encodeToByteArray()
        try {
            translateNativeOpenPgpWriteError {
                NativeCrypto.openPgp.signDetached(
                    content = content,
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                )
            }.decodeAndErase()
        } finally {
            content.fill(0)
        }
    }

    override fun verifyClearSignedText(
        request: GpgOpenPgpVerifyTextRequest,
    ): GpgOpenPgpVerification = NativeGpgOpenPgpVerifier.verifyClearSignedText(request)

    override fun verifyDetachedText(
        request: GpgOpenPgpVerifyDetachedTextRequest,
    ): GpgOpenPgpVerification = NativeGpgOpenPgpVerifier.verifyDetachedText(request)

    override fun encryptText(
        request: GpgOpenPgpEncryptTextRequest,
    ): String = request.publicKeys.withEncodedPublicKeys { publicKeys ->
        request.signingPrivateKey.withOptionalEncoded { signingPrivateKey, preferredFingerprint ->
            val content = request.text.encodeToByteArray()
            try {
                val result = translateNativeOpenPgpWriteError(
                    noUsableKeyMeansLegacyFailure = true,
                ) {
                    NativeCrypto.openPgp.encrypt(
                        content = content,
                        publicKeys = publicKeys,
                        signingPrivateKey = signingPrivateKey,
                        preferredSigningFingerprint = preferredFingerprint,
                        fileName = CONSOLE_FILE_NAME,
                        armored = true,
                    )
                }
                result.data.decodeAndErase()
            } finally {
                content.fill(0)
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
                    )
                } finally {
                    content.fill(0)
                }
            }
        }
    }

    override fun signFile(
        request: GpgOpenPgpSignFileRequest,
    ) {
        request.input.use { input ->
            request.signatureOutput.use { output ->
                request.privateKey.withEncoded { privateKey, preferredFingerprint ->
                    translateNativeOpenPgpWriteError {
                        NativeCrypto.openPgp.openDetachedSigning(
                            privateKey = privateKey,
                            preferredFingerprint = preferredFingerprint,
                            armored = request.armored,
                        ).use { session ->
                            input.consumeWithErasedBuffer { data, length ->
                                session.update(data, length = length)
                            }
                            val signature = session.finish()
                            writeAndErase(output, signature)
                            output.flush()
                        }
                    }
                }
            }
        }
    }

    override fun verifyFile(
        request: GpgOpenPgpVerifyFileRequest,
    ): GpgOpenPgpVerification = NativeGpgOpenPgpVerifier.verifyFile(request)

    override fun encryptFile(
        request: GpgOpenPgpEncryptFileRequest,
    ) {
        request.input.use { input ->
            request.output.use { output ->
                request.publicKeys.withEncodedPublicKeys { publicKeys ->
                    request.signingPrivateKey.withOptionalEncoded { signingPrivateKey, preferredFingerprint ->
                        translateNativeOpenPgpWriteError(
                            noUsableKeyMeansLegacyFailure = true,
                        ) {
                            NativeCrypto.openPgp.openEncryption(
                                publicKeys = publicKeys,
                                signingPrivateKey = signingPrivateKey,
                                preferredSigningFingerprint = preferredFingerprint,
                                fileName = request.fileName.ifBlank { CONSOLE_FILE_NAME },
                                armored = request.armored,
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

    override fun decryptFile(
        request: GpgOpenPgpDecryptFileRequest,
    ): GpgOpenPgpDecryptFileResult {
        check(request.privateKeys.isNotEmpty()) { "At least one OpenPGP private key is required." }
        return withStagedOpenPgpPlaintext(request.output) { stagedOutput ->
            request.input.use { input ->
                request.privateKeys.withEncodedPrivateKeys { privateKeys ->
                    request.publicKeys.withEncodedPublicKeys { publicKeys ->
                        translateNativeOpenPgpWriteError {
                            NativeCrypto.openPgp.openDecryption(
                                privateKeys = privateKeys,
                                verificationPublicKeys = publicKeys,
                            ).use { session ->
                                input.consumeWithErasedBuffer { data, length ->
                                    writeAndErase(stagedOutput, session.update(data, length = length))
                                }
                                val final = session.finish()
                                writeAndErase(stagedOutput, final.data)
                                stagedOutput.flush()
                                GpgOpenPgpDecryptFileResult(
                                    verification = final.verification?.toDomain(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private inline fun <T> NativeOpenPgpKeyMaterial.useArmoredStrings(
    block: (privateKeyArmored: String, publicKeyArmored: String) -> T,
): T = try {
    block(
        privateKeyArmored.decodeToString(throwOnInvalidSequence = true),
        publicKeyArmored.decodeToString(throwOnInvalidSequence = true),
    )
} finally {
    privateKeyArmored.fill(0)
    publicKeyArmored.fill(0)
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

private fun GpgPublicKeyInfo.toMetadata(): GpgAgentKeyMetadata {
    val keys = buildList {
        addMetadataKey(
            keygrip = keygrip,
            fingerprint = fingerprint,
            algorithm = algorithm,
            canSign = canSign,
            canEncrypt = canEncrypt,
        )
        subKeys.forEach { subKey ->
            addMetadataKey(
                keygrip = subKey.keygrip,
                fingerprint = subKey.fingerprint,
                algorithm = subKey.algorithm,
                canSign = subKey.canSign,
                canEncrypt = subKey.canEncrypt,
            )
        }
    }
    return GpgAgentKeyMetadata(keys = keys)
}

private fun MutableList<GpgAgentKeyMetadataKey>.addMetadataKey(
    keygrip: String?,
    fingerprint: String,
    algorithm: String,
    canSign: Boolean,
    canEncrypt: Boolean,
) {
    val normalizedKeygrip = keygrip?.takeIf { it.isNotBlank() } ?: return
    val capabilities = buildSet {
        if (canSign) add("sign")
        if (canEncrypt) add("encrypt")
    }
    if (capabilities.isEmpty()) return
    add(
        GpgAgentKeyMetadataKey(
            keygrip = normalizedKeygrip,
            fingerprint = fingerprint,
            algorithm = algorithm,
            capabilities = capabilities,
        ),
    )
}

private const val CONSOLE_FILE_NAME = "_CONSOLE"
