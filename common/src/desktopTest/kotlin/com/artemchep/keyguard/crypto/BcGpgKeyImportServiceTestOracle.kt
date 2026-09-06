package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportError
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportService
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.parsePrimaryKeyInfo
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BcGpgKeyImportServiceTestOracle(
    private val publicKeyParser: GpgPublicKeyParser,
    private val metadataResolver: GpgKeyMetadataResolver,
) : GpgKeyImportService {
    constructor(
        directDI: DirectDI,
    ) : this(
        publicKeyParser = directDI.instance(),
        metadataResolver = directDI.instance(),
    )

    override fun import(
        request: GpgKeyImportRequest,
    ): GpgKeyImportResult {
        val content = request.content.trim()
        if (content.isBlank()) {
            return GpgKeyImportResult.Error(GpgKeyImportError.Empty)
        }

        val privateResult = importPrivateKey(request.copy(content = content))
        if (privateResult != null) {
            return privateResult
        }
        return importPublicKey(content)
    }

    private fun importPrivateKey(
        request: GpgKeyImportRequest,
    ): GpgKeyImportResult? {
        val collection = try {
            parseGpgSecretKeyRingCollection(request.content)
        } catch (_: GpgUnsupportedKeyVersionException) {
            return GpgKeyImportResult.Error(GpgKeyImportError.UnsupportedFormat)
        } catch (_: Exception) {
            return null
        }
        val ring = collection.keyRings
            .asSequence()
            .firstOrNull { it.hasSecretKeyMaterial() }
            ?: return GpgKeyImportResult.Error(GpgKeyImportError.UnsupportedFormat)

        val protected = ring.isPassphraseProtected()
        if (protected && request.passphrase == null) {
            return GpgKeyImportResult.NeedsPassphrase(FORMAT_LABEL)
        }

        val importedRing = if (protected) {
            val decryptor = buildDecryptor(request.passphrase.orEmpty())
            runCatching {
                PGPSecretKeyRing.copyWithNewPassword(
                    ring,
                    decryptor,
                    null,
                )
            }.getOrElse { error ->
                return if (error is PGPException) {
                    GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase)
                } else {
                    GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
                }
            }
        } else {
            ring
        }

        val publicKeyRing = importedRing.toCertificate()
        val publicKeyArmored = publicKeyRing.armored()
        val privateKeyArmored = importedRing.armored()
        val keyInfo = publicKeyParser.parsePrimaryKeyInfo(publicKeyArmored)
            ?: return GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
        val fingerprint = keyInfo.fingerprint
        val metadata = metadataResolver.resolve(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
        )?.metadata ?: return GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
        return GpgKeyImportResult.Success(
            GeneratedGpgKey(
                privateKeyArmored = privateKeyArmored,
                publicKeyArmored = publicKeyArmored,
                fingerprint = fingerprint,
                metadata = metadata,
                userId = keyInfo.userIds.firstOrNull().orEmpty(),
                typeLabel = keyInfo.algorithm,
            ),
        )
    }

    private fun importPublicKey(
        content: String,
    ): GpgKeyImportResult = when (val result = publicKeyParser.parse(content)) {
        is GpgPublicKeyParseResult.Success -> {
            val containsMultipleCertificates = result.keys.size > 1 ||
                result.keys.isNotEmpty() && result.skippedCertificates > 0
            if (containsMultipleCertificates) {
                return GpgKeyImportResult.Error(GpgKeyImportError.MultipleKeys)
            }
            val key = result.keys.singleOrNull()
                ?: return GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
            val metadata = metadataResolver.resolve(
                privateKeyArmored = null,
                publicKeyArmored = key.publicKeyArmored,
                fingerprint = key.fingerprint,
            )?.metadata ?: return GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)
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
            reason = when (result.reason) {
                GpgPublicKeyParseError.Empty -> GpgKeyImportError.Empty
                GpgPublicKeyParseError.Malformed -> GpgKeyImportError.MalformedKey
                GpgPublicKeyParseError.UnsupportedKeyVersion -> GpgKeyImportError.UnsupportedFormat
                GpgPublicKeyParseError.MultipleCertificates -> GpgKeyImportError.MultipleKeys
                GpgPublicKeyParseError.Unsupported -> GpgKeyImportError.UnsupportedPlatform
            },
        )
    }

    private fun buildDecryptor(
        passphrase: String,
    ): PBESecretKeyDecryptor {
        return JcePBESecretKeyDecryptorBuilder(
            JcaPGPDigestCalculatorProviderBuilder()
                .setProvider(gpgBouncyCastleProvider)
                .build(),
        )
            .setProvider(gpgBouncyCastleProvider)
            .build(passphrase.toCharArray())
    }

    private fun PGPSecretKeyRing.hasSecretKeyMaterial(): Boolean =
        secretKeys.asSequence().any { key -> !key.isPrivateKeyEmpty }

    private fun PGPSecretKeyRing.isPassphraseProtected(): Boolean =
        secretKeys.asSequence().any { key -> key.isPassphraseProtected() }

    private fun PGPSecretKey.isPassphraseProtected(): Boolean =
        !isPrivateKeyEmpty && keyEncryptionAlgorithm != SymmetricKeyAlgorithmTags.NULL

    private companion object {
        const val FORMAT_LABEL = "OpenPGP"
    }
}
