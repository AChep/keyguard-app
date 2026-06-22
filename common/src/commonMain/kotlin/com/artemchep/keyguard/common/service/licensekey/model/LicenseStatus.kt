package com.artemchep.keyguard.common.service.licensekey.model

/**
 * Lifecycle status of a license, mirroring the server `LicenseStatus`.
 * Unknown values map to [UNKNOWN].
 *
 * @author Artem Chepurnyi
 */
enum class LicenseStatus {
    ACTIVE,
    GRACE,
    EXPIRED,
    REVOKED,
    REFUNDED,
    PENDING,
    INVALID,
    UNKNOWN,
    ;

    companion object {
        fun of(raw: String?): LicenseStatus = when (raw?.trim()?.lowercase()) {
            "active" -> ACTIVE
            "grace" -> GRACE
            "expired" -> EXPIRED
            "revoked" -> REVOKED
            "refunded" -> REFUNDED
            "pending" -> PENDING
            "invalid" -> INVALID
            else -> UNKNOWN
        }
    }
}