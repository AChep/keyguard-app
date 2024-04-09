package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.datepicker.getMonthTitleStringRes
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

class DateFormatterAndroid(
    private val context: LeContext,
) : DateFormatter {
    private val formatterDateTime =
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)

    private val formatterDate = DateFormat.getDateInstance(DateFormat.LONG)

    private val machineDateTime = SimpleDateFormat("yyyyMMddHHmmss")

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance(),
    )

    override fun formatDateTimeMachine(
        instant: Instant,
    ): String {
        val date = instant.toEpochMilliseconds().let(::Date)
        return machineDateTime.format(date)
    }

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

    override fun formatDateShort(instant: Instant): String {
        val tz = TimeZone.currentSystemDefault()
        val dt = instant.toLocalDateTime(tz)
        return formatDateShort(dt.date)
    }

    override fun formatDateShort(date: LocalDate): String {
        // Manually format the date. Using the "MMMM yyyy" format
        // doesn't work correctly for some locales.
        val year = date.year.toString()
        val month = kotlin.run {
            val res = getMonthTitleStringRes(date.monthNumber)
            textResource(res, context)
        }
        return "$month $year"
    }

    override fun formatDateMedium(date: LocalDate): String {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        return formatter.format(date.toJavaLocalDate())
    }

    override fun formatTimeShort(time: LocalTime): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        return formatter.format(time.toJavaLocalTime())
    }
}
