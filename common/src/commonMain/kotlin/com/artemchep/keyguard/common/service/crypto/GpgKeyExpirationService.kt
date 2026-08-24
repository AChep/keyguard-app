package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import kotlin.time.Instant

/**
 * Re-certifies selected OpenPGP key components with a new expiry.
 *
 * Component fingerprints are explicit: callers include the primary fingerprint when
 * the primary expiry should change, and include each subkey fingerprint separately.
 * A null [GpgKeyExpirationChange.expiresAt] means that the selected components do
 * not expire.
 */
interface GpgKeyExpirationService {
    val isSupported: Boolean
        get() = true

    fun update(
        request: GpgKeyExpirationRequest,
    ): GpgKeyExpirationResult
}

data class GpgKeyExpirationRequest(
    val key: GpgKeyMaterial,
    val change: GpgKeyExpirationChange,
    /** Vault-local public keys that may authenticate external designated revocations. */
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
)

data class GpgKeyExpirationChange(
    val expiresAt: Instant?,
    val componentFingerprints: Set<String>,
)

sealed interface GpgKeyExpirationResult {
    data class Success(
        val key: GpgKeyMaterial,
    ) : GpgKeyExpirationResult

    data class Error(
        val reason: GpgKeyExpirationError,
    ) : GpgKeyExpirationResult
}

enum class GpgKeyExpirationError {
    EmptyPrivateKey,
    MalformedKey,
    FingerprintMismatch,
    NoComponentsSelected,
    ComponentNotFound,
    RevokedComponent,
    UnresolvedRevocationAuthority,
    UnsupportedKeyVersion,
    MissingSecretKey,
    ProtectedSecretKey,
    MissingSelfSignature,
    InvalidExpiration,
    TimeConflict,
    SignatureVerificationFailed,
    MetadataResolutionFailed,
    InternalFailure,

    /**
     * The certificate can only be re-certified with a digest algorithm the native core
     * refuses to emit (SHA-1 or weaker).
     */
    UnsupportedSigningHash,
    UnsupportedPlatform,
}

object GpgKeyExpirationServiceUnsupported : GpgKeyExpirationService {
    override val isSupported: Boolean
        get() = false

    override fun update(
        request: GpgKeyExpirationRequest,
    ): GpgKeyExpirationResult = GpgKeyExpirationResult.Error(
        reason = GpgKeyExpirationError.UnsupportedPlatform,
    )
}
