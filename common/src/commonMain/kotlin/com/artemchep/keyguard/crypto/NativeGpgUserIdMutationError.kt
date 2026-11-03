package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdMutationError

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun NativeOpenPgpUserIdMutationError.toDomain(): GpgUserIdMutationError =
    when (this) {
        NativeOpenPgpUserIdMutationError.LAST_USER_ID -> GpgUserIdMutationError.LastUserId
        NativeOpenPgpUserIdMutationError.EMPTY_PRIVATE_KEY -> {
            GpgUserIdMutationError.EmptyPrivateKey
        }

        NativeOpenPgpUserIdMutationError.MALFORMED_KEY -> {
            GpgUserIdMutationError.MalformedKey
        }

        NativeOpenPgpUserIdMutationError.FINGERPRINT_MISMATCH -> {
            GpgUserIdMutationError.FingerprintMismatch
        }

        NativeOpenPgpUserIdMutationError.TARGET_NOT_FOUND -> {
            GpgUserIdMutationError.TargetNotFound
        }

        NativeOpenPgpUserIdMutationError.TARGET_INACTIVE -> {
            GpgUserIdMutationError.TargetInactive
        }

        NativeOpenPgpUserIdMutationError.INVALID_NEW_USER_ID -> {
            GpgUserIdMutationError.InvalidNewUserId
        }

        NativeOpenPgpUserIdMutationError.SAME_IDENTITY -> {
            GpgUserIdMutationError.SameIdentity
        }

        NativeOpenPgpUserIdMutationError.DUPLICATE_IDENTITY -> {
            GpgUserIdMutationError.DuplicateIdentity
        }

        NativeOpenPgpUserIdMutationError.PREVIOUSLY_REVOKED_IDENTITY -> {
            GpgUserIdMutationError.PreviouslyRevokedIdentity
        }

        NativeOpenPgpUserIdMutationError.AMBIGUOUS_PRIMARY -> {
            GpgUserIdMutationError.AmbiguousPrimary
        }

        NativeOpenPgpUserIdMutationError.UNSUPPORTED_KEY_VERSION -> {
            GpgUserIdMutationError.UnsupportedKeyVersion
        }

        NativeOpenPgpUserIdMutationError.PROTECTED_SECRET_KEY -> {
            GpgUserIdMutationError.ProtectedSecretKey
        }

        NativeOpenPgpUserIdMutationError.MISSING_SELF_SIGNATURE -> {
            GpgUserIdMutationError.MissingSelfSignature
        }

        NativeOpenPgpUserIdMutationError.NON_REVOCABLE -> {
            GpgUserIdMutationError.NonRevocable
        }

        NativeOpenPgpUserIdMutationError.UNSUPPORTED_TEMPLATE -> {
            GpgUserIdMutationError.UnsupportedTemplate
        }

        NativeOpenPgpUserIdMutationError.POLICY_CONFLICT -> {
            GpgUserIdMutationError.PolicyConflict
        }

        NativeOpenPgpUserIdMutationError.TIME_CONFLICT -> {
            GpgUserIdMutationError.TimeConflict
        }

        NativeOpenPgpUserIdMutationError.SIGNATURE_VERIFICATION_FAILED -> {
            GpgUserIdMutationError.SignatureVerificationFailed
        }

        NativeOpenPgpUserIdMutationError.METADATA_RESOLUTION_FAILED -> {
            GpgUserIdMutationError.MetadataResolutionFailed
        }

        NativeOpenPgpUserIdMutationError.INTERNAL_FAILURE -> {
            GpgUserIdMutationError.InternalFailure
        }

        NativeOpenPgpUserIdMutationError.CERTIFICATE_REVOKED -> {
            GpgUserIdMutationError.CertificateRevoked
        }

        NativeOpenPgpUserIdMutationError.UNRESOLVED_REVOCATION_AUTHORITY -> {
            GpgUserIdMutationError.UnresolvedRevocationAuthority
        }

        NativeOpenPgpUserIdMutationError.UNSUPPORTED_SIGNING_HASH -> {
            GpgUserIdMutationError.UnsupportedSigningHash
        }
    }
