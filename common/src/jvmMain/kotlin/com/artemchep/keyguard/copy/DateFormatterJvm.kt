package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.datepicker.getMonthYearTitle
import com.artemchep.keyguard.platform.LeContext
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime

class DateFormatterJvm(
    private val context: LeContext,
) : DateFormatter {
    private val formatterDateTime =
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)

    private val formatterDate = DateFormat.getDateInstance(DateFormat.LONG)

    override fun formatDateTime(
        instant: Instant,
    ): String {
        val date = instant.toEpochMilliseconds().let(::Date)
        return formatterDateTime.format(date)
    }

    override fun formatDate(instant: Instant): String {
        val date = instant.toEpochMilliseconds().let(::Date)
        return formatterDate.format(date)
    }

    override suspend fun formatDateShort(date: LocalDate): String =
        getMonthYearTitle(date, context)

    override fun formatDateMedium(date: LocalDate): String {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        return formatter.format(date.toJavaLocalDate())
    }

    override fun formatTimeShort(time: LocalTime): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        return formatter.format(time.toJavaLocalTime())
    }
}
