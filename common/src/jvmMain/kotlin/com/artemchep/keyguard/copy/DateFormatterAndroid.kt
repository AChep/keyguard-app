package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.datepicker.getMonthTitleStringRes
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.text.DateFormat
import java.util.Date

class DateFormatterAndroid(
    private val context: LeContext,
) : DateFormatter {
    private val formatterDateTime =
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)

    private val formatterDate = DateFormat.getDateInstance(DateFormat.LONG)

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance(),
    )

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

        // Manually format the date. Using the "MMMM yyyy" format
        // doesn't work correctly for some locales.
        val year = dt.year.toString()
        val month = kotlin.run {
            val res = getMonthTitleStringRes(dt.monthNumber)
            textResource(res, context)
        }
        return "$month $year"
    }
}
