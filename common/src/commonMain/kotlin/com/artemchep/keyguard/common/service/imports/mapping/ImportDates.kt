package com.artemchep.keyguard.common.service.imports.mapping

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * A date an export stores as Unix seconds, as `YYYY-MM-DD` in UTC — the
 * calendar date the source showed, for a custom field.
 */
internal fun importedDateFromEpochSeconds(
    seconds: Long,
): String = Instant.fromEpochSeconds(seconds)
    .toLocalDateTime(TimeZone.UTC)
    .date
    .toString()

/**
 * A card month and year as `MM/YYYY`, for a custom field; `null` when either
 * is missing.
 */
internal fun ImportedExpiry.toFieldText(): String? {
    if (month == null || year == null) {
        return null
    }
    return "${month.padStart(2, '0')}/$year"
}
