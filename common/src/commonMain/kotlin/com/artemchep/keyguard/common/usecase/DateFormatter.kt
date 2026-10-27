package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.util.formatDateTimeMachine
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

interface DateFormatter {
    fun formatDateTimeMachine(
        instant: Instant,
    ): String = instant
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .formatDateTimeMachine()

    fun formatDateTime(
        instant: Instant,
    ): String

    /** Includes seconds for deadlines whose ordering is significant within a minute. */
    fun formatDateTimeWithSeconds(instant: Instant): String = instant.toString()

    fun formatDate(
        instant: Instant,
    ): String

    suspend fun formatDateShort(
        instant: Instant,
    ): String {
        val tz = TimeZone.currentSystemDefault()
        val dt = instant.toLocalDateTime(tz)
        return formatDateShort(dt.date)
    }

    suspend fun formatDateShort(
        date: LocalDate,
    ): String

    fun formatDateMedium(
        date: LocalDate,
    ): String

    fun formatTimeShort(
        time: LocalTime,
    ): String
}
