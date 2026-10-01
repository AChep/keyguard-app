package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.impl.GetAppBuildDateImpl
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toNSDate
import platform.Foundation.NSCalendar
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.localeWithLocaleIdentifier
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.Foundation.timeZoneWithName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class DateFormatterAppleTest {
    @Test
    fun formatsIsoDatesInSelectedPresentationCalendar() {
        for (localeId in localeIds) {
            val locale = NSLocale.localeWithLocaleIdentifier(localeId)
            val formatter = formatter(locale, utc)
            for (date in listOf("2026-09-24", "2024-02-29", "2026-12-31", "2019-04-30", "2019-05-01")) {
                assertEquals(
                    expectedDate(locale, date),
                    formatter.formatDateMedium(LocalDate.parse(date)),
                    "$localeId: $date",
                )
            }
        }
    }

    @Test
    fun preservesDatesAcrossDeviceTimeZones() {
        val locale = NSLocale.localeWithLocaleIdentifier("en_US")
        for (zoneId in zoneIds) {
            val formatter = formatter(locale, zone(zoneId))
            // Apia skipped an entire day; Santiago skipped midnight on this date.
            for (date in listOf("2011-12-30", "2024-09-08", "2026-09-24")) {
                assertEquals(
                    expectedDate(locale, date),
                    formatter.formatDateMedium(LocalDate.parse(date)),
                    "$zoneId: $date",
                )
            }
        }
    }

    @Test
    fun preservesClockValuesAcrossCalendarsAndDeviceTimeZones() {
        for (localeId in localeIds) {
            val locale = NSLocale.localeWithLocaleIdentifier(localeId)
            for (zoneId in zoneIds) {
                val formatter = formatter(locale, zone(zoneId))
                for (time in listOf("00:00:00", "02:30:00", "12:00:00", "23:59:59.999")) {
                    assertEquals(
                        expectedTime(locale, time),
                        formatter.formatTimeShort(LocalTime.parse(time)),
                        "$localeId / $zoneId: $time",
                    )
                }
            }
        }
    }

    @Test
    fun keepsInstantFormattingInDeviceZoneAfterFormattingLocalValues() {
        val instant = Instant.parse("2026-09-24T00:30:00Z")
        val locale = NSLocale.localeWithLocaleIdentifier("en_US")
        val datesByZone = mutableSetOf<String>()
        for (zoneId in listOf("UTC", "America/Los_Angeles", "Pacific/Kiritimati")) {
            val timeZone = zone(zoneId)
            val formatter = formatter(locale, timeZone)
            val expectedDate = reference(locale, timeZone, NSDateFormatterLongStyle, NSDateFormatterNoStyle, instant)
            val expectedDateTime = reference(
                locale, timeZone, NSDateFormatterLongStyle, NSDateFormatterShortStyle, instant,
            )
            assertEquals(expectedDate, formatter.formatDate(instant))
            assertEquals(expectedDateTime, formatter.formatDateTime(instant))

            formatter.formatDateMedium(LocalDate(2026, 9, 24))
            formatter.formatTimeShort(LocalTime(2, 30))

            assertEquals(expectedDate, formatter.formatDate(instant), zoneId)
            assertEquals(expectedDateTime, formatter.formatDateTime(instant), zoneId)
            datesByZone += formatter.formatDate(instant)
        }
        assertEquals(2, datesByZone.size)
    }

    @Test
    fun refreshesCachedLocalFormattersWhenLocaleChanges() {
        var locale = NSLocale.localeWithLocaleIdentifier("en_US")
        val formatter = DateFormatterApple(LeContext(), { locale }, { utc })
        val date = LocalDate(2026, 9, 24)
        val time = LocalTime(23, 59)
        val originalDate = formatter.formatDateMedium(date)
        val originalTime = formatter.formatTimeShort(time)

        locale = NSLocale.localeWithLocaleIdentifier("fa_IR@calendar=persian")
        assertEquals(expectedDate(locale, "2026-09-24"), formatter.formatDateMedium(date))
        assertEquals(expectedTime(locale, "23:59:00"), formatter.formatTimeShort(time))
        assertNotEquals(originalDate, formatter.formatDateMedium(date))
        assertNotEquals(originalTime, formatter.formatTimeShort(time))

        locale = NSLocale.localeWithLocaleIdentifier("en_US")
        assertEquals(originalDate, formatter.formatDateMedium(date))
        assertEquals(originalTime, formatter.formatTimeShort(time))
    }

    @Test
    fun usesCurrentCalendarForPresentationOnly() {
        // Also run this test in separate processes with -AppleLocale overrides. Unlike
        // injected locales, those change NSCalendar.currentCalendar, so a conversion that
        // depends on it fails even on a host whose normal calendar is Gregorian.
        val expectedCalendar = NSProcessInfo.processInfo.environment["KEYGUARD_TEST_CALENDAR"] as? String
        if (expectedCalendar != null) {
            assertEquals(expectedCalendar, NSCalendar.currentCalendar.calendarIdentifier)
        }
        val expectedTimeZone = NSProcessInfo.processInfo.environment["KEYGUARD_TEST_TIME_ZONE"] as? String
        if (expectedTimeZone != null) {
            assertEquals(expectedTimeZone, NSCalendar.currentCalendar.timeZone.name)
        }
        val locale = NSLocale.currentLocale
        val formatter = DateFormatterApple(LeContext())
        assertEquals(expectedDate(locale, "2026-09-24"), formatter.formatDateMedium(LocalDate(2026, 9, 24)))
        assertEquals(expectedDate(locale, "2011-12-30"), formatter.formatDateMedium(LocalDate(2011, 12, 30)))
        assertEquals(expectedTime(locale, "02:30:00"), formatter.formatTimeShort(LocalTime(2, 30)))
    }

    @Test
    fun formatsBuildMetadataAsAnIsoDate() = runTest {
        val locale = NSLocale.localeWithLocaleIdentifier("th_TH@calendar=buddhist")
        val formatter = DateFormatterApple(LeContext(), { locale })
        val buildDate = GetAppBuildDateImpl(formatter, "20260924")().first()
        val expected = reference(
            locale, utc, NSDateFormatterLongStyle, NSDateFormatterNoStyle, Instant.parse("2026-09-24T12:00:00Z"),
        )
        assertEquals(expected, buildDate)
    }

    @Test
    fun leavesMachineFormattingUnchangedAfterFormattingLocalValues() {
        val formatter = DateFormatterApple(LeContext())
        val instant = Instant.parse("2026-09-24T00:30:00Z")
        val nativeFormatter = NSDateFormatter().apply {
            setLocale(NSLocale.localeWithLocaleIdentifier("en_US_POSIX"))
            setDateFormat("yyyyMMddHHmmss")
        }
        val expected = nativeFormatter.stringFromDate(instant.toNSDate())
        assertEquals(expected, formatter.formatDateTimeMachine(instant))
        formatter.formatDateMedium(LocalDate(2026, 9, 24))
        formatter.formatTimeShort(LocalTime(2, 30))
        assertEquals(expected, formatter.formatDateTimeMachine(instant))
    }
}

private val localeIds = listOf(
    "en_US",
    "th_TH@calendar=buddhist",
    "fa_IR@calendar=persian",
    "ja_JP@calendar=japanese",
)

private val zoneIds = listOf("UTC", "America/Los_Angeles", "America/Santiago", "Pacific/Apia", "Pacific/Kiritimati")

private val utc = NSTimeZone.timeZoneForSecondsFromGMT(0)

private fun zone(id: String): NSTimeZone = assertNotNull(NSTimeZone.timeZoneWithName(id))

private fun formatter(locale: NSLocale, timeZone: NSTimeZone) = DateFormatterApple(
    context = LeContext(),
    localeProvider = { locale },
    timeZoneProvider = { timeZone },
)

// Reference instants are parsed directly from ISO text, without the production
// LocalDate/LocalTime conversion or Foundation calendar component construction.
private fun expectedDate(locale: NSLocale, date: String): String = reference(
    locale, utc, NSDateFormatterMediumStyle, NSDateFormatterNoStyle, Instant.parse("${date}T12:00:00Z"),
)

private fun expectedTime(locale: NSLocale, time: String): String = reference(
    locale, utc, NSDateFormatterNoStyle, NSDateFormatterShortStyle, Instant.parse("2000-01-01T${time}Z"),
)

private fun reference(
    locale: NSLocale,
    timeZone: NSTimeZone,
    dateStyle: ULong,
    timeStyle: ULong,
    instant: Instant,
): String = NSDateFormatter().apply {
    setLocale(locale)
    setDateStyle(dateStyle)
    setTimeStyle(timeStyle)
    setTimeZone(timeZone)
}.stringFromDate(instant.toNSDate())
