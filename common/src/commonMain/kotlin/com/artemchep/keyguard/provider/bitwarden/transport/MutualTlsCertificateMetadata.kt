package com.artemchep.keyguard.provider.bitwarden.transport

import kotlin.time.Instant

/**
 * Public certificate details; none of them is secret.
 *
 * [subject] and [issuer] are RFC 2253 names, [issuerName] is the issuer's common name
 * or its full name, [serialNumber] and [sha256Fingerprint] are colon-separated
 * uppercase hex, and [keyType] is empty for an unknown key algorithm.
 */
data class MutualTlsCertificateMetadata(
    val displayName: String,
    val subject: String,
    val issuer: String,
    val issuerName: String,
    val validFrom: Instant,
    val expiresAt: Instant,
    val serialNumber: String,
    val sha256Fingerprint: String,
    val keyType: String,
)
