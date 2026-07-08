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
    val verificationStatus: GpgKeyserverVerificationStatus = GpgKeyserverVerificationStatus.UNKNOWN,
    val lastCheckedAt: Instant? = null,
    val lastRefreshedAt: Instant? = null,
    val sourceKeyserver: String? = null,
)
