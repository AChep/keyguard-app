package com.artemchep.keyguard.common.model

import kotlin.time.Instant

/**
 * Durable per-key keyserver metadata. Stored in the encrypted vault database
 * (not the exposed database, which is rebuilt from ciphers on every change),
 * keyed by the normalized [fingerprint].
 */
data class DGpgKeyserverState(
    val fingerprint: String,
    /**
     * The vault cipher this key belongs to, or `null` for keys that were
     * fetched from a keyserver but not (yet) imported into the vault.
     */
    val cipherId: String? = null,
    /** Last observed verdict, retained as a conservative fallback when evidence is inconclusive. */
    val verificationStatus: GpgKeyserverVerificationStatus = GpgKeyserverVerificationStatus.UNKNOWN,
    val lastCheckedAt: Instant? = null,
    val lastRefreshedAt: Instant? = null,
    val sourceKeyserver: String? = null,
    /** Public packet history used to evaluate revocation; retained after restoration. */
    val revocationEvidenceArmored: String? = null,
    /** A historical revocation warning whose complete signed evidence was not retained. */
    val hasUnbackedRevocation: Boolean = false,
    /** Last keyserver publication result, independent of time-dependent revocation policy. */
    val publicationStatus: GpgKeyserverVerificationStatus = GpgKeyserverVerificationStatus.UNKNOWN,
) {
    init {
        require(publicationStatus != GpgKeyserverVerificationStatus.REVOKED)
    }
}
