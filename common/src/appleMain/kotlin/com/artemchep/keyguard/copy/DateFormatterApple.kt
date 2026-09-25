package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.datepicker.getMonthTitleStringRes
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.localeIdentifier
import platform.Foundation.localeWithLocaleIdentifier
import platform.Foundation.timeZoneForSecondsFromGMT

/**
 * Locale-aware date formatting using the same styles as the JVM implementation.
 * Native calendar preferences and locale data can produce different strings on Apple.
 *
 * `NSDateFormatter` is documented as expensive to create, so the formatters are cached.
 * The cache is keyed on the current locale identifier because the app can override the
 * language at runtime (it writes `AppleLanguages`), and a formatter built before that
 * override would keep formatting in the old locale for the rest of the process.
 */
class DateFormatterApple internal constructor(
    private val context: LeContext,
    private val localeProvider: () -> NSLocale,
    private val timeZoneProvider: () -> NSTimeZone,
) : DateFormatter {
    constructor(context: LeContext) : this(
        context = context,
        localeProvider = { NSLocale.currentLocale },
        timeZoneProvider = { NSTimeZone.defaultTimeZone },
    )

    private var cacheLocaleIdentifier: String? = null

    private var cacheFormatters: MutableMap<Key, NSDateFormatter> = mutableMapOf()

    private data class Key(
        val dateStyle: ULong,
        val timeStyle: ULong,
        val localValue: Boolean,
    )

    private fun formatter(
        dateStyle: ULong,
        timeStyle: ULong,
        localValue: Boolean = false,
    ): NSDateFormatter {
        val locale = localeProvider()
        val localeIdentifier = locale.localeIdentifier
        // A language override invalidates every cached formatter at once; there are only
        // ever a handful of them, so rebuilding the whole map is cheaper than tracking
        // them individually.
        if (localeIdentifier != cacheLocaleIdentifier) {
            cacheLocaleIdentifier = localeIdentifier
            cacheFormatters = mutableMapOf()
        }
        return cacheFormatters.getOrPut(Key(dateStyle, timeStyle, localValue)) {
            NSDateFormatter().apply {
                setLocale(locale)
                setDateStyle(dateStyle)
                setTimeStyle(timeStyle)
                // Local values are encoded in UTC solely for Foundation formatting.
                // Rendering them in a device zone would change their date or time.
                setTimeZone(if (localValue) utcTimeZone else timeZoneProvider())
            }
        }
    }

    /**
     * The machine format is an identifier, not something a user reads, so it is pinned to
     * a fixed pattern in the POSIX locale — otherwise a non-Gregorian calendar locale
     * (e.g. `ja_JP@calendar=japanese`) would emit a different year.
     */
    private val machineFormatter by lazy {
        NSDateFormatter().apply {
            setLocale(NSLocale.localeWithLocaleIdentifier("en_US_POSIX"))
            setDateFormat("yyyyMMddHHmmss")
        }
    }

    override fun formatDateTimeMachine(
        instant: Instant,
    ): String = machineFormatter.stringFromDate(instant.toNSDate())

    override fun formatDateTime(
        instant: Instant,
    ): String = formatter(
        dateStyle = NSDateFormatterLongStyle,
        timeStyle = NSDateFormatterShortStyle,
    ).stringFromDate(instant.toNSDate())

    override fun formatDate(
        instant: Instant,
    ): String = formatter(
        dateStyle = NSDateFormatterLongStyle,
        timeStyle = NSDateFormatterNoStyle,
    ).stringFromDate(instant.toNSDate())

    override suspend fun formatDateShort(
        instant: Instant,
    ): String {
        val tz = TimeZone.currentSystemDefault()
        val dt = instant.toLocalDateTime(tz)
        return formatDateShort(dt.date)
    }

    override suspend fun formatDateShort(
        date: LocalDate,
    ): String {
        // Manually format the date. Using the "MMMM yyyy" format
        // doesn't work correctly for some locales.
        val year = date.year.toString()
        val month = kotlin.run {
            val res = getMonthTitleStringRes(date.month.ordinal + 1)
            textResource(res, context)
        }
        return "$month $year"
    }

    override fun formatDateMedium(
        date: LocalDate,
    ): String = formatter(
        dateStyle = NSDateFormatterMediumStyle,
        timeStyle = NSDateFormatterNoStyle,
        localValue = true,
    ).stringFromDate(date.toSyntheticUtcDate())

    override fun formatTimeShort(
        time: LocalTime,
    ): String = formatter(
        dateStyle = NSDateFormatterNoStyle,
        timeStyle = NSDateFormatterShortStyle,
        localValue = true,
    ).stringFromDate(time.toSyntheticUtcDate())
}

private fun Instant.toNSDate(): NSDate = NSDate
    .dateWithTimeIntervalSince1970(toEpochMilliseconds() / MILLISECONDS_PER_SECOND)

/**
 * Interpret the input in Kotlin's ISO calendar, independently of the user's calendar.
 * UTC also preserves civil dates skipped by a device zone, such as Apia's 2011-12-30.
 * The resulting synthetic date must only be rendered by a UTC formatter.
 */
private fun LocalDate.toSyntheticUtcDate(): NSDate =
    atTime(12, 0).toInstant(TimeZone.UTC).toNSDate()

/**
 * Only the time is displayed. A fixed ISO date in UTC avoids calendar interpretation
 * and local time-zone transitions when constructing the synthetic date.
 */
private fun LocalTime.toSyntheticUtcDate(): NSDate =
    timeAnchorDate.atTime(this).toInstant(TimeZone.UTC).toNSDate()

private val timeAnchorDate = LocalDate(2000, 1, 1)

private val utcTimeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)

private const val MILLISECONDS_PER_SECOND = 1000.0
