package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyExpiry
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The largest timestamp representable by OpenPGP's unsigned 32-bit time field. */
val GPG_KEY_EXPIRATION_MAX_INSTANT: Instant =
    Instant.fromEpochSeconds(UInt.MAX_VALUE.toLong())

/**
 * Conservative calendar limit for expiration pickers.
 *
 * Keeping the selected local day below the protocol ceiling leaves enough room
 * for end-of-day conversion in every time zone.
 */
val GPG_KEY_EXPIRATION_MAX_DATE: LocalDate = LocalDate(2106, 2, 5)

fun clampGpgKeyExpirationDate(date: LocalDate): LocalDate = minOf(date, GPG_KEY_EXPIRATION_MAX_DATE)

/**
 * Resolves a chosen calendar [date] to 23:59:00 local timestamp.
 * The date is first clamped to the safe OpenPGP limit so the result never
 * exceeds the protocol ceiling in any time zone.
 */
fun gpgKeyExpirationAtEndOfDay(
    date: LocalDate,
    timeZone: TimeZone,
): Instant = clampGpgKeyExpirationDate(date)
    .plus(1, DateTimeUnit.DAY)
    .atStartOfDayIn(timeZone) - 60.seconds

/** Resolves a generator policy exactly once against the key creation context. */
fun GpgKeyExpiry.resolve(
    creationTime: Instant,
    timeZone: TimeZone,
): Instant? = when (this) {
    is GpgKeyExpiry.AfterYears -> {
        val date = creationTime
            .toLocalDateTime(timeZone)
            .date
            .plus(years, DateTimeUnit.YEAR)
        require(date <= GPG_KEY_EXPIRATION_MAX_DATE) {
            "GPG key expiry exceeds the supported calendar range."
        }
        gpgKeyExpirationAtEndOfDay(date, timeZone)
    }

    is GpgKeyExpiry.OnDate -> {
        require(date <= GPG_KEY_EXPIRATION_MAX_DATE) {
            "GPG key expiry exceeds the supported calendar range."
        }
        gpgKeyExpirationAtEndOfDay(date, timeZone)
    }

    is GpgKeyExpiry.At -> instant
    GpgKeyExpiry.Never -> null
}
