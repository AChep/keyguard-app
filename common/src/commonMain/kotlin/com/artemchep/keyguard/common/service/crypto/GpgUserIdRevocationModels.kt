package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import kotlin.time.Instant

data class GpgUserIdRevocationRequest(
    val key: GpgKeyMaterial,
    /** Stable ID derived from the exact User ID packet body, never display text. */
    val identityId: String,
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
)

sealed interface GpgUserIdRevocationResult {
    data class Success(
        val key: GpgKeyMaterial,
        /** Empty when unchanged or when the applied mutation is local-only. */
        val revocationCertificateArmored: String,
        /** A local-only change is true even though no transferable certificate is returned. */
        val changed: Boolean,
        val effectiveAt: Instant,
    ) : GpgUserIdRevocationResult

    data class Error(
        val reason: GpgUserIdRevocationError,
    ) : GpgUserIdRevocationResult
}

enum class GpgUserIdRevocationError {
    EmptyPrivateKey,
    MalformedKey,
    FingerprintMismatch,
    TargetNotFound,
    LastUserId,
    UnsupportedKeyVersion,
    ProtectedSecretKey,
    MissingSelfSignature,
    NonRevocable,
    TimeConflict,
    SignatureVerificationFailed,
    MetadataResolutionFailed,
    InternalFailure,
    CertificateRevoked,
    UnresolvedRevocationAuthority,
    UnsupportedSigningHash,
}
