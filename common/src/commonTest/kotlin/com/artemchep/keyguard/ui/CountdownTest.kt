package com.artemchep.keyguard.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CountdownTest {
    @Test
    fun underAMinuteShowsSeconds() {
        assertEquals("0", formatCountdown((-1).seconds))
        assertEquals("0", formatCountdown(999.milliseconds))
        assertEquals("59", formatCountdown(59.seconds))
    }

    @Test
    fun fromAMinuteShowsMinutesAndSeconds() {
        assertEquals("1:00", formatCountdown(60.seconds))
        assertEquals("4:59", formatCountdown(5.minutes - 1.seconds))
        assertEquals("30:00", formatCountdown(30.minutes))
    }
}
