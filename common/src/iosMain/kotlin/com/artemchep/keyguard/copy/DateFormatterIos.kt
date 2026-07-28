package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.DateFormatter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

object DateFormatterIos : DateFormatter {
    override fun formatDateTimeMachine(
        instant: Instant,
    ): String = instant.toString()

    override fun formatDateTime(
        instant: Instant,
    ): String {
        val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${formatDateMedium(dateTime.date)} ${formatTimeShort(dateTime.time)}"
    }

    override fun formatDate(
        instant: Instant,
    ): String = formatDateMedium(
        instant.toLocalDateTime(TimeZone.currentSystemDefault()).date,
    )

    override suspend fun formatDateShort(
        instant: Instant,
    ): String = formatDate(
        instant = instant,
    )

    override suspend fun formatDateShort(
        date: LocalDate,
    ): String = formatDateMedium(date)

    override fun formatDateMedium(
        date: LocalDate,
    ): String = "${date.year}-${(date.month.ordinal + 1).twoDigits()}-${date.day.twoDigits()}"

    override fun formatTimeShort(
        time: LocalTime,
    ): String = "${time.hour.twoDigits()}:${time.minute.twoDigits()}"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
