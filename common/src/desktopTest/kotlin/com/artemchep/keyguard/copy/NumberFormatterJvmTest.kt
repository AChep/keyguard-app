package com.artemchep.keyguard.copy

import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatterJvmTest {
    private val formatter = NumberFormatterJvm { Locale.US }

    @Test
    fun formatsSmallCountsAndCompactMagnitudes() = runTest {
        val cases = mapOf(
            0L to "0",
            1L to "1",
            999L to "999",
            1000L to "1K",
            1049L to "1K",
            1050L to "1.1K",
            1500L to "1.5K",
            12_345L to "12.3K",
            999_949L to "999.9K",
            999_950L to "1M",
            1_500_000L to "1.5M",
            999_950_000L to "1B",
            1_500_000_000L to "1.5B",
            1_500_000_000_000L to "1.5T",
            1_500_000_000_000_000L to "1.5Q",
            1_500_000_000_000_000_000L to "1.5E",
            Long.MAX_VALUE to "9.2E",
            Long.MIN_VALUE to "-9.2E",
            -999L to "-999",
            -1050L to "-1.1K",
            -999_950L to "-1M",
        )
        cases.forEach { (number, expected) ->
            assertEquals(expected, formatter.formatCompactNumber(number), number.toString())
        }
    }

    @Test
    fun usesTheCurrentLocaleForDecimalFormatting() = runTest {
        var locale = Locale.US
        val formatter = NumberFormatterJvm { locale }
        assertEquals("1.5K", formatter.formatCompactNumber(1500L))
        locale = Locale.GERMANY
        assertEquals("1,5K", formatter.formatCompactNumber(1500L))
        assertEquals("1K", formatter.formatCompactNumber(1000L))
    }

    @Test
    fun preservesOrdinaryNumberFormatting() {
        assertEquals("1,500", formatter.formatNumber(1500))
        assertEquals("1.500", NumberFormatterJvm { Locale.GERMANY }.formatNumber(1500))
    }
}
