package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.DateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class GetAppBuildDateImplTest {
    @Test
    fun preservesTheBuildCalendarDateInTheDeviceTimeZone() = runTest {
        val formatter = DeviceDateOnlyFormatter()
        for ((buildDate, expected) in listOf("20260916" to "2026-09-16", "20240229" to "2024-02-29")) {
            val date = GetAppBuildDateImpl(formatter, buildDate)().first()
            assertEquals(expected, date)
        }
    }
}

private class DeviceDateOnlyFormatter : DateFormatter {
    override fun formatDate(instant: Instant): String = instant
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

    override fun formatDateTime(instant: Instant): String = error("Unexpected date format")
    override suspend fun formatDateShort(date: LocalDate): String = error("Unexpected date format")
    override fun formatDateMedium(date: LocalDate): String = error("Unexpected date format")
    override fun formatTimeShort(time: LocalTime): String = error("Unexpected time format")
}
