package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementService
import com.artemchep.keyguard.common.util.sleepBlocking
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdReplacementError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdReplacementResult
import com.artemchep.keyguard.nativecrypto.isValidOpenPgpUserId
import kotlin.time.Clock
import kotlin.time.Instant

object NativeGpgUserIdReplacementService : GpgUserIdReplacementService {
    override val isSupported: Boolean
        get() = true

    override fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult =
        replace(
            request = request,
            now = { Clock.System.now() },
            waitForClock = ::sleepBlocking,
        )

    internal fun replace(
        request: GpgUserIdReplacementRequest,
        now: () -> Instant,
        waitForClock: (milliseconds: Long) -> Boolean,
    ): GpgUserIdReplacementResult {
        val validationError =
            when {
                request.key.privateKeyArmored.isBlank() -> GpgUserIdReplacementError.EmptyPrivateKey
                !request.newUserId.isValidOpenPgpUserId() -> GpgUserIdReplacementError.InvalidNewUserId
                else -> null
            }
        if (validationError != null) {
            return GpgUserIdReplacementResult.Error(validationError)
        }
        return mutateSignedUserId(
            key = request.key,
            candidateRevocationKeys = request.candidateRevocationKeys,
            now = now,
            waitForClock = waitForClock,
            internalFailureError = {
                GpgUserIdReplacementResult.Error(GpgUserIdReplacementError.InternalFailure)
            },
            isTimeConflict = { result -> result.isRetryableTimeConflict() },
            nativeMutation = { privateKey, publicKey, fingerprint, candidates, referenceTime ->
                NativeCrypto.openPgp.replaceUserId(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    expectedPrimaryFingerprint = fingerprint,
                    oldIdentityId = request.oldIdentityId,
                    newUserId = request.newUserId,
                    candidateRevocationKeys = candidates,
                    referenceTimeEpochSeconds = referenceTime,
                )
            },
            toDomain = { result -> result.toDomain() },
        )
    }

    private fun NativeOpenPgpUserIdReplacementResult.toDomain(): GpgUserIdReplacementResult =
        when (this) {
            is NativeOpenPgpUserIdReplacementResult.Success -> {
                withDecodedKeyMaterial(
                    material = keyMaterial,
                    certificateIndex = certificateIndex,
                    certificateArmored = replacementCertificateArmored,
                ) { key, certificate ->
                    GpgUserIdReplacementResult.Success(
                        key = key,
                        replacementCertificateArmored = certificate,
                        changed = changed,
                        effectiveAt = Instant.fromEpochSeconds(effectiveAtEpochSeconds),
                        oldIdentityId = oldIdentityId,
                        newIdentityId = newIdentityId,
                        primaryUserId = primaryUserId,
                    )
                }
            }

            is NativeOpenPgpUserIdReplacementResult.Error -> {
                GpgUserIdReplacementResult.Error(reason.toDomain())
            }
        }
}

private fun NativeOpenPgpUserIdReplacementResult.isRetryableTimeConflict(): Boolean =
    this is NativeOpenPgpUserIdReplacementResult.Error &&
        reason == NativeOpenPgpUserIdReplacementError.TIME_CONFLICT

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun NativeOpenPgpUserIdReplacementError.toDomain(): GpgUserIdReplacementError =
    when (this) {
        NativeOpenPgpUserIdReplacementError.EMPTY_PRIVATE_KEY -> {
            GpgUserIdReplacementError.EmptyPrivateKey
        }

        NativeOpenPgpUserIdReplacementError.MALFORMED_KEY -> {
            GpgUserIdReplacementError.MalformedKey
        }

        NativeOpenPgpUserIdReplacementError.FINGERPRINT_MISMATCH -> {
            GpgUserIdReplacementError.FingerprintMismatch
        }

        NativeOpenPgpUserIdReplacementError.TARGET_NOT_FOUND -> {
            GpgUserIdReplacementError.TargetNotFound
        }

        NativeOpenPgpUserIdReplacementError.TARGET_INACTIVE -> {
            GpgUserIdReplacementError.TargetInactive
        }

        NativeOpenPgpUserIdReplacementError.INVALID_NEW_USER_ID -> {
            GpgUserIdReplacementError.InvalidNewUserId
        }

        NativeOpenPgpUserIdReplacementError.SAME_IDENTITY -> {
            GpgUserIdReplacementError.SameIdentity
        }

        NativeOpenPgpUserIdReplacementError.DUPLICATE_IDENTITY -> {
            GpgUserIdReplacementError.DuplicateIdentity
        }

        NativeOpenPgpUserIdReplacementError.PREVIOUSLY_REVOKED_IDENTITY -> {
            GpgUserIdReplacementError.PreviouslyRevokedIdentity
        }

        NativeOpenPgpUserIdReplacementError.AMBIGUOUS_PRIMARY -> {
            GpgUserIdReplacementError.AmbiguousPrimary
        }

        NativeOpenPgpUserIdReplacementError.UNSUPPORTED_KEY_VERSION -> {
            GpgUserIdReplacementError.UnsupportedKeyVersion
        }

        NativeOpenPgpUserIdReplacementError.PROTECTED_SECRET_KEY -> {
            GpgUserIdReplacementError.ProtectedSecretKey
        }

        NativeOpenPgpUserIdReplacementError.MISSING_SELF_SIGNATURE -> {
            GpgUserIdReplacementError.MissingSelfSignature
        }

        NativeOpenPgpUserIdReplacementError.NON_REVOCABLE -> {
            GpgUserIdReplacementError.NonRevocable
        }

        NativeOpenPgpUserIdReplacementError.UNSUPPORTED_TEMPLATE -> {
            GpgUserIdReplacementError.UnsupportedTemplate
        }

        NativeOpenPgpUserIdReplacementError.POLICY_CONFLICT -> {
            GpgUserIdReplacementError.PolicyConflict
        }

        NativeOpenPgpUserIdReplacementError.TIME_CONFLICT -> {
            GpgUserIdReplacementError.TimeConflict
        }

        NativeOpenPgpUserIdReplacementError.SIGNATURE_VERIFICATION_FAILED -> {
            GpgUserIdReplacementError.SignatureVerificationFailed
        }

        NativeOpenPgpUserIdReplacementError.METADATA_RESOLUTION_FAILED -> {
            GpgUserIdReplacementError.MetadataResolutionFailed
        }

        NativeOpenPgpUserIdReplacementError.INTERNAL_FAILURE -> {
            GpgUserIdReplacementError.InternalFailure
        }

        NativeOpenPgpUserIdReplacementError.CERTIFICATE_REVOKED -> {
            GpgUserIdReplacementError.CertificateRevoked
        }

        NativeOpenPgpUserIdReplacementError.UNRESOLVED_REVOCATION_AUTHORITY -> {
            GpgUserIdReplacementError.UnresolvedRevocationAuthority
        }

        NativeOpenPgpUserIdReplacementError.UNSUPPORTED_SIGNING_HASH -> {
            GpgUserIdReplacementError.UnsupportedSigningHash
        }
    }
