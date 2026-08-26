package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialContributions
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialInputContribution
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialInputError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialOperationalError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialPairError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileFailure
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialWithheldReason
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateMaterialContributions
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateMaterialInputContribution
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateMaterialInputError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateMaterialPairError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateMaterialReconcileFailure
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateMaterialReconcileV2Result
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateMaterialWithheldReason
import com.artemchep.keyguard.nativecrypto.isValidOpenPgpFingerprint

object NativeGpgCertificateMaterialReconciler : GpgCertificateMaterialReconciler {
    override fun reconcile(
        expectedPrimaryFingerprint: String,
        existingPublicCertificate: String?,
        existingSecretCertificate: String?,
        incomingPublicCertificate: String?,
        incomingSecretCertificate: String?,
    ): GpgCertificateMaterialReconcileResult {
        val fingerprint = expectedPrimaryFingerprint.normalizeGpgFingerprint()
        if (!fingerprint.isValidOpenPgpFingerprint()) {
            return GpgCertificateMaterialReconcileResult.Error(
                GpgCertificateMaterialReconcileFailure.Pair(
                    GpgCertificateMaterialPairError.FingerprintMismatch,
                ),
            )
        }
        val existingPublic = existingPublicCertificate?.encodeToByteArray()
        val incomingPublic = incomingPublicCertificate?.encodeToByteArray()
        val existingSecret = existingSecretCertificate?.encodeToByteArray()
        val incomingSecret = incomingSecretCertificate?.encodeToByteArray()
        return try {
            when (
                val result =
                    NativeCrypto.openPgp.reconcileCertificateMaterialV2(
                        expectedPrimaryFingerprint = fingerprint,
                        existingPublicCertificate = existingPublic,
                        incomingPublicCertificate = incomingPublic,
                        existingSecretCertificate = existingSecret,
                        incomingSecretCertificate = incomingSecret,
                    )
            ) {
                is NativeOpenPgpCertificateMaterialReconcileV2Result.Success -> {
                    try {
                        GpgCertificateMaterialReconcileResult.Success(
                            localPublicMaterial =
                                result.localPublicMaterial.decodeToString(
                                    throwOnInvalidSequence = true,
                                ),
                            localSecretMaterial =
                                result.localSecretMaterial?.decodeToString(
                                    throwOnInvalidSequence = true,
                                ),
                            transferablePublicCertificate =
                                result.transferablePublicCertificate?.decodeToString(
                                    throwOnInvalidSequence = true,
                                ),
                            transferableSecretKey =
                                result.transferableSecretKey?.decodeToString(
                                    throwOnInvalidSequence = true,
                                ),
                            primaryFingerprint = result.primaryFingerprint,
                            contributions = result.contributions.toDomain(),
                            withheldReasons =
                                result.withheldReasons.mapTo(mutableSetOf()) {
                                    it.toDomain()
                                },
                        )
                    } finally {
                        result.localPublicMaterial.fill(0)
                        result.localSecretMaterial?.fill(0)
                        result.transferablePublicCertificate?.fill(0)
                        result.transferableSecretKey?.fill(0)
                    }
                }

                is NativeOpenPgpCertificateMaterialReconcileV2Result.Error -> {
                    GpgCertificateMaterialReconcileResult.Error(result.failure.toDomain())
                }
            }
        } catch (failure: NativeCryptoException) {
            if (failure.code != NativeCryptoErrorCode.RESOURCE_LIMIT) throw failure
            GpgCertificateMaterialReconcileResult.Error(
                GpgCertificateMaterialReconcileFailure.Operational(
                    GpgCertificateMaterialOperationalError.ResourceLimit,
                ),
            )
        } finally {
            existingPublic?.fill(0)
            incomingPublic?.fill(0)
            existingSecret?.fill(0)
            incomingSecret?.fill(0)
        }
    }
}

private fun NativeOpenPgpCertificateMaterialContributions.toDomain() =
    GpgCertificateMaterialContributions(
        existingPublic = existingPublic.toDomain(),
        incomingPublic = incomingPublic.toDomain(),
        existingSecret = existingSecret.toDomain(),
        incomingSecret = incomingSecret.toDomain(),
    )

private fun NativeOpenPgpCertificateMaterialInputContribution.toDomain() =
    GpgCertificateMaterialInputContribution(
        present = present,
        uniquePublicEvidence = uniquePublicEvidence,
        uniqueSecretCapability = uniqueSecretCapability,
    )

private fun NativeOpenPgpCertificateMaterialWithheldReason.toDomain() =
    when (this) {
        NativeOpenPgpCertificateMaterialWithheldReason.NO_TRANSFERABLE_PUBLIC_CERTIFICATE -> {
            GpgCertificateMaterialWithheldReason.NoTransferablePublicCertificate
        }

        NativeOpenPgpCertificateMaterialWithheldReason.LOCAL_PUBLIC_EVIDENCE -> {
            GpgCertificateMaterialWithheldReason.LocalPublicEvidence
        }

        NativeOpenPgpCertificateMaterialWithheldReason.SECRET_MATERIAL_NOT_TRANSFERABLE -> {
            GpgCertificateMaterialWithheldReason.SecretMaterialNotTransferable
        }
    }

private fun NativeOpenPgpCertificateMaterialReconcileFailure.toDomain(): GpgCertificateMaterialReconcileFailure =
    when (this) {
        is NativeOpenPgpCertificateMaterialReconcileFailure.InvalidInputs -> {
            GpgCertificateMaterialReconcileFailure.InvalidInputs(
                existingPublic = existingPublic?.toDomain(),
                incomingPublic = incomingPublic?.toDomain(),
                existingSecret = existingSecret?.toDomain(),
                incomingSecret = incomingSecret?.toDomain(),
            )
        }

        is NativeOpenPgpCertificateMaterialReconcileFailure.Pair -> {
            GpgCertificateMaterialReconcileFailure.Pair(reason.toDomain())
        }
    }

private fun NativeOpenPgpCertificateMaterialInputError.toDomain(): GpgCertificateMaterialInputError =
    when (this) {
        NativeOpenPgpCertificateMaterialInputError.EMPTY_CERTIFICATE -> {
            GpgCertificateMaterialInputError.EmptyCertificate
        }

        NativeOpenPgpCertificateMaterialInputError.MALFORMED_CERTIFICATE -> {
            GpgCertificateMaterialInputError.MalformedCertificate
        }

        NativeOpenPgpCertificateMaterialInputError.UNSUPPORTED_KEY_VERSION -> {
            GpgCertificateMaterialInputError.UnsupportedKeyVersion
        }

        NativeOpenPgpCertificateMaterialInputError.FINGERPRINT_MISMATCH -> {
            GpgCertificateMaterialInputError.FingerprintMismatch
        }

        NativeOpenPgpCertificateMaterialInputError.COMPONENT_COLLISION -> {
            GpgCertificateMaterialInputError.ComponentCollision
        }

        NativeOpenPgpCertificateMaterialInputError.RESOURCE_LIMIT -> {
            GpgCertificateMaterialInputError.ResourceLimit
        }

        NativeOpenPgpCertificateMaterialInputError.UNSUPPORTED_TSK_LAYOUT -> {
            GpgCertificateMaterialInputError.UnsupportedTskLayout
        }
    }

private fun NativeOpenPgpCertificateMaterialPairError.toDomain(): GpgCertificateMaterialPairError =
    when (this) {
        NativeOpenPgpCertificateMaterialPairError.MISSING_MATERIAL -> {
            GpgCertificateMaterialPairError.MissingMaterial
        }

        NativeOpenPgpCertificateMaterialPairError.FINGERPRINT_MISMATCH -> {
            GpgCertificateMaterialPairError.FingerprintMismatch
        }

        NativeOpenPgpCertificateMaterialPairError.COMPONENT_COLLISION -> {
            GpgCertificateMaterialPairError.ComponentCollision
        }

        NativeOpenPgpCertificateMaterialPairError.RESOURCE_LIMIT -> {
            GpgCertificateMaterialPairError.ResourceLimit
        }

        NativeOpenPgpCertificateMaterialPairError.INVALID_REBUILT_OUTPUT -> {
            GpgCertificateMaterialPairError.InvalidRebuiltOutput
        }

        NativeOpenPgpCertificateMaterialPairError.CONFLICTING_SECRET_MATERIAL -> {
            GpgCertificateMaterialPairError.ConflictingSecretMaterial
        }
    }
