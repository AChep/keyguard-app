package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationService
import com.artemchep.keyguard.common.util.sleepBlocking
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdRevocationError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdRevocationResult
import kotlin.time.Clock
import kotlin.time.Instant

object NativeGpgUserIdRevocationService : GpgUserIdRevocationService {
    override val isSupported: Boolean
        get() = true

    override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
        revoke(
            request = request,
            now = { Clock.System.now() },
            waitForClock = ::sleepBlocking,
        )

    internal fun revoke(
        request: GpgUserIdRevocationRequest,
        now: () -> Instant,
        waitForClock: (milliseconds: Long) -> Boolean,
    ): GpgUserIdRevocationResult {
        if (request.key.privateKeyArmored.isBlank()) {
            return GpgUserIdRevocationResult.Error(GpgUserIdRevocationError.EmptyPrivateKey)
        }
        return mutateSignedUserId(
            key = request.key,
            candidateRevocationKeys = request.candidateRevocationKeys,
            now = now,
            waitForClock = waitForClock,
            internalFailureError = {
                GpgUserIdRevocationResult.Error(GpgUserIdRevocationError.InternalFailure)
            },
            isTimeConflict = { result -> result.isRetryableTimeConflict() },
            nativeMutation = { privateKey, publicKey, fingerprint, candidates, referenceTime ->
                NativeCrypto.openPgp.revokeUserId(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    expectedPrimaryFingerprint = fingerprint,
                    identityId = request.identityId,
                    candidateRevocationKeys = candidates,
                    referenceTimeEpochSeconds = referenceTime,
                )
            },
            toDomain = { result -> result.toDomain() },
        )
    }

    private fun NativeOpenPgpUserIdRevocationResult.toDomain(): GpgUserIdRevocationResult =
        when (this) {
            is NativeOpenPgpUserIdRevocationResult.Success -> {
                withDecodedKeyMaterial(
                    material = keyMaterial,
                    certificateIndex = certificateIndex,
                    certificateArmored = revocationCertificateArmored,
                ) { key, certificate ->
                    GpgUserIdRevocationResult.Success(
                        key = key,
                        revocationCertificateArmored = certificate,
                        changed = changed,
                        effectiveAt = Instant.fromEpochSeconds(effectiveAtEpochSeconds),
                    )
                }
            }

            is NativeOpenPgpUserIdRevocationResult.Error -> {
                GpgUserIdRevocationResult.Error(reason.toDomain())
            }
        }
}

private fun NativeOpenPgpUserIdRevocationResult.isRetryableTimeConflict(): Boolean =
    this is NativeOpenPgpUserIdRevocationResult.Error &&
        reason == NativeOpenPgpUserIdRevocationError.TIME_CONFLICT

@Suppress("CyclomaticComplexMethod")
private fun NativeOpenPgpUserIdRevocationError.toDomain(): GpgUserIdRevocationError =
    when (this) {
        NativeOpenPgpUserIdRevocationError.EMPTY_PRIVATE_KEY -> {
            GpgUserIdRevocationError.EmptyPrivateKey
        }

        NativeOpenPgpUserIdRevocationError.MALFORMED_KEY -> {
            GpgUserIdRevocationError.MalformedKey
        }

        NativeOpenPgpUserIdRevocationError.FINGERPRINT_MISMATCH -> {
            GpgUserIdRevocationError.FingerprintMismatch
        }

        NativeOpenPgpUserIdRevocationError.TARGET_NOT_FOUND -> {
            GpgUserIdRevocationError.TargetNotFound
        }

        NativeOpenPgpUserIdRevocationError.LAST_USER_ID -> {
            GpgUserIdRevocationError.LastUserId
        }

        NativeOpenPgpUserIdRevocationError.UNSUPPORTED_KEY_VERSION -> {
            GpgUserIdRevocationError.UnsupportedKeyVersion
        }

        NativeOpenPgpUserIdRevocationError.PROTECTED_SECRET_KEY -> {
            GpgUserIdRevocationError.ProtectedSecretKey
        }

        NativeOpenPgpUserIdRevocationError.MISSING_SELF_SIGNATURE -> {
            GpgUserIdRevocationError.MissingSelfSignature
        }

        NativeOpenPgpUserIdRevocationError.NON_REVOCABLE -> {
            GpgUserIdRevocationError.NonRevocable
        }

        NativeOpenPgpUserIdRevocationError.TIME_CONFLICT -> {
            GpgUserIdRevocationError.TimeConflict
        }

        NativeOpenPgpUserIdRevocationError.SIGNATURE_VERIFICATION_FAILED -> {
            GpgUserIdRevocationError.SignatureVerificationFailed
        }

        NativeOpenPgpUserIdRevocationError.METADATA_RESOLUTION_FAILED -> {
            GpgUserIdRevocationError.MetadataResolutionFailed
        }

        NativeOpenPgpUserIdRevocationError.INTERNAL_FAILURE -> {
            GpgUserIdRevocationError.InternalFailure
        }

        NativeOpenPgpUserIdRevocationError.CERTIFICATE_REVOKED -> {
            GpgUserIdRevocationError.CertificateRevoked
        }

        NativeOpenPgpUserIdRevocationError.UNRESOLVED_REVOCATION_AUTHORITY -> {
            GpgUserIdRevocationError.UnresolvedRevocationAuthority
        }

        NativeOpenPgpUserIdRevocationError.UNSUPPORTED_SIGNING_HASH -> {
            GpgUserIdRevocationError.UnsupportedSigningHash
        }
    }
