package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.DateFormatter
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

class DateFormatterAndroid(
) : DateFormatter {
    private val formatterDateTime =
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)

    private val formatterDate = DateFormat.getDateInstance(DateFormat.LONG)
    private val formatterDateShort = SimpleDateFormat("MMMM yyyy")

    constructor(directDI: DirectDI) : this()

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
        val date = instant.toEpochMilliseconds().let(::Date)
        return formatterDateShort.format(date)
    }
}
