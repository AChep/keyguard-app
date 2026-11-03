package com.artemchep.keyguard.feature.auth.certificate

import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsCertificateMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

enum class ClientCertificateStatus {
    VALID,
    EXPIRING_SOON,
    EXPIRED,
    NOT_YET_VALID,
}

/** How long before expiry a certificate is flagged for renewal. */
val ClientCertificateExpiringSoonThreshold = 30.days

fun MutualTlsCertificateMetadata.status(now: Instant): ClientCertificateStatus = when {
    now < validFrom -> ClientCertificateStatus.NOT_YET_VALID
    now >= expiresAt -> ClientCertificateStatus.EXPIRED
    expiresAt - now <= ClientCertificateExpiringSoonThreshold -> ClientCertificateStatus.EXPIRING_SOON
    else -> ClientCertificateStatus.VALID
}

/** Elapsed share of the validity period, in `0..1`. */
fun MutualTlsCertificateMetadata.lifetimeProgress(now: Instant): Float {
    val lifetime = expiresAt - validFrom
    if (lifetime <= Duration.ZERO) return 1f
    return ((now - validFrom) / lifetime).toFloat().coerceIn(0f, 1f)
}
