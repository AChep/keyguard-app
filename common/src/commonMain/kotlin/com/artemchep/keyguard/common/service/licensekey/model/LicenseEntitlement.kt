package com.artemchep.keyguard.common.service.licensekey.model

import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement.Decoded
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement.Expiry
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement.Undecodable
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseProductKind
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface LicenseEntitlement {
    val licenseKey: String

    /**
     * Effective status: [LicenseStatus.INVALID] when the key signature does
     * not verify, otherwise refined by the latest server snapshot (e.g.
     * [LicenseStatus.REVOKED]) or [LicenseStatus.ACTIVE].
     */
    val status: LicenseStatus
    val source: LicenseSource?

    /**
     * The key could not be decoded or its signature did not verify.
     */
    data class Undecodable(
        override val licenseKey: String,
        override val source: LicenseSource?,
        val reason: String,
    ) : LicenseEntitlement {
        override val status: LicenseStatus = LicenseStatus.INVALID
    }

    /**
     * Unified runtime view of a decoded license. The authoritative facts (tier,
     * product kind, paid-through month) come from the locally verified KG2 key
     * payload; the server `/v1/license/status` snapshot only layers in revocation
     * and refresh hints on top.
     */
    data class Decoded(
        override val licenseKey: String,
        val licenseId: String,
        val tier: String,
        val productKind: Kg2LicenseProductKind,
        val expiry: Expiry,
        override val status: LicenseStatus,
        /** After this point a fresh server status check should be performed. */
        val checkAfter: Instant?,
        val reason: String?,
        override val source: LicenseSource?,
    ) : LicenseEntitlement

    sealed interface Expiry {
        /** The key payload grants lifetime access. */
        data object Lifetime : Expiry

        data class PaidThrough(
            /** Paid-through month `YYYY-MM` from the key payload. */
            val yearMonth: String,
        ) : Expiry
    }
}

/**
 * Return this license grants premium right now.
 */
fun LicenseEntitlement.isCurrentlyLicensed(
    now: Instant = Clock.System.now(),
): Boolean {
    when (status) {
        LicenseStatus.INVALID,
        LicenseStatus.REVOKED,
        LicenseStatus.REFUNDED,
            -> return false

        else -> Unit
    }
    return when (this) {
        is Decoded -> isExpiryCurrentlyLicensed(expiry, now)
        is Undecodable -> false
    }
}

private fun isExpiryCurrentlyLicensed(
    expiry: Expiry,
    now: Instant,
): Boolean = when (expiry) {
    Expiry.Lifetime -> true
    is Expiry.PaidThrough -> currentYearMonthUtc(now) <= expiry.yearMonth
}

private fun currentYearMonthUtc(now: Instant): String {
    val dateTime = now.toLocalDateTime(TimeZone.UTC)
    val year = dateTime.year.toString().padStart(4, '0')
    val month = dateTime.month.number.toString().padStart(2, '0')
    return "$year-$month"
}
