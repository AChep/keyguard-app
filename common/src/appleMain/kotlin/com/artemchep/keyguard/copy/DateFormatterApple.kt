package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.datepicker.getMonthYearTitle
import com.artemchep.keyguard.platform.LeContext
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSDateFormatterStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeZoneForSecondsFromGMT

/**
 * Locale-aware date formatting using the same styles as the JVM implementation.
 * Native calendar preferences and locale data can produce different strings on Apple.
 *
 * `NSDateFormatter` is expensive to create, so the formatters are cached per locale.
 * They follow `NSLocale.currentLocale`, which Foundation updates on system locale
 * changes but not when the app writes `AppleLanguages`.
 */
class DateFormatterApple(
    private val context: LeContext,
    private val localeProvider: () -> NSLocale = { NSLocale.currentLocale },
    private val timeZoneProvider: () -> NSTimeZone = { NSTimeZone.defaultTimeZone },
) : DateFormatter {
    private val formatters = LocaleCache(localeProvider) { locale ->
        Formatters(locale, timeZoneProvider())
    }

    private class Formatters(
        locale: NSLocale,
        timeZone: NSTimeZone,
    ) {
        val dateTime = createFormatter(locale, timeZone, NSDateFormatterLongStyle, NSDateFormatterShortStyle)
        val date = createFormatter(locale, timeZone, NSDateFormatterLongStyle, NSDateFormatterNoStyle)

        // Local values are encoded in UTC solely for Foundation formatting.
        // Rendering them in a device zone would change their date or time.
        val localDate = createFormatter(locale, utcTimeZone, NSDateFormatterMediumStyle, NSDateFormatterNoStyle)
        val localTime = createFormatter(locale, utcTimeZone, NSDateFormatterNoStyle, NSDateFormatterShortStyle)
    }

    override fun formatDateTime(
        instant: Instant,
    ): String = formatters.get().dateTime.stringFromDate(instant.toNSDate())

    override fun formatDate(
        instant: Instant,
    ): String = formatters.get().date.stringFromDate(instant.toNSDate())

    override suspend fun formatDateShort(
        date: LocalDate,
    ): String = getMonthYearTitle(date, context)

    override fun formatDateMedium(
        date: LocalDate,
    ): String = formatters.get().localDate.stringFromDate(date.toSyntheticUtcDate())

    override fun formatTimeShort(
        time: LocalTime,
    ): String = formatters.get().localTime.stringFromDate(time.toSyntheticUtcDate())
}

private fun createFormatter(
    locale: NSLocale,
    timeZone: NSTimeZone,
    dateStyle: NSDateFormatterStyle,
    timeStyle: NSDateFormatterStyle,
) = NSDateFormatter().apply {
    setLocale(locale)
    setTimeZone(timeZone)
    setDateStyle(dateStyle)
    setTimeStyle(timeStyle)
}

// Unlike kotlinx-datetime's toNSDate(), this does not throw
// outside of NSDate.distantPast..NSDate.distantFuture.
private fun Instant.toNSDate(): NSDate = NSDate
    .dateWithTimeIntervalSince1970(toEpochMilliseconds() / MILLISECONDS_PER_SECOND)

/**
 * Interpret the input in Kotlin's ISO calendar, independently of the user's calendar.
 * UTC also preserves civil dates skipped by a device zone, such as Apia's 2011-12-30.
 */
private fun LocalDate.toSyntheticUtcDate(): NSDate =
    atTime(12, 0).toInstant(TimeZone.UTC).toNSDate()

/**
 * Only the time is displayed. A fixed ISO date avoids calendar interpretation
 * and local time-zone transitions.
 */
private fun LocalTime.toSyntheticUtcDate(): NSDate =
    timeAnchorDate.atTime(this).toInstant(TimeZone.UTC).toNSDate()

private val timeAnchorDate = LocalDate(2000, 1, 1)

private val utcTimeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)

private const val MILLISECONDS_PER_SECOND = 1000.0
