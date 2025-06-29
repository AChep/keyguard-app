package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.kodein.di.DirectDI
import java.time.format.DateTimeFormatter

class DateTimePlaceholder(
    private val now: Instant,
) : Placeholder {
    private val localDateTime by lazy {
        val tz = TimeZone.currentSystemDefault()
        now.toLocalDateTime(tz)
    }

    private val utcDateTime by lazy {
        val tz = TimeZone.UTC
        now.toLocalDateTime(tz)
    }

    // ...for 2012-07-25 17:05:34 the value is 20120725170534
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    override fun get(
        key: String,
    ): IO<String?>? = when {
        //
        // Local Date Time
        //

        // Current local date/time as a simple, sortable string.
        key.equals("dt_simple", ignoreCase = true) -> {
            localDateTime.toJavaLocalDateTime()
                .format(dateTimeFormatter)
                .let(::io)
        }
        // Year component of the current local date/time.
        key.equals("dt_year", ignoreCase = true) -> {
            localDateTime.year.toString().let(::io)
        }
        // Month component of the current local date/time.
        key.equals("dt_month", ignoreCase = true) -> {
            localDateTime.month.number.toString().let(::io)
        }
        // Day component of the current local date/time.
        key.equals("dt_day", ignoreCase = true) -> {
            localDateTime.day.toString().let(::io)
        }
        // Hour component of the current local date/time.
        key.equals("dt_hour", ignoreCase = true) -> {
            localDateTime.hour.toString().let(::io)
        }
        // Minute component of the current local date/time.
        key.equals("dt_minute", ignoreCase = true) -> {
            localDateTime.minute.toString().let(::io)
        }
        // Second component of the current local date/time.
        key.equals("dt_second", ignoreCase = true) -> {
            localDateTime.second.toString().let(::io)
        }

        //
        // UTC Date Time
        //

        // Current UTC date/time as a simple, sortable string.
        key.equals("dt_utc_simple", ignoreCase = true) -> {
            utcDateTime.toJavaLocalDateTime()
                .format(dateTimeFormatter)
                .let(::io)
        }
        // Year component of the current UTC date/time.
        key.equals("dt_utc_year", ignoreCase = true) -> {
            utcDateTime.year.toString().let(::io)
        }
        // Month component of the current UTC date/time.
        key.equals("dt_utc_month", ignoreCase = true) -> {
            utcDateTime.month.number.toString().let(::io)
        }
        // Day component of the current UTC date/time.
        key.equals("dt_utc_day", ignoreCase = true) -> {
            utcDateTime.day.toString().let(::io)
        }
        // Hour component of the current UTC date/time.
        key.equals("dt_utc_hour", ignoreCase = true) -> {
            utcDateTime.hour.toString().let(::io)
        }
        // Minute component of the current UTC date/time.
        key.equals("dt_utc_minute", ignoreCase = true) -> {
            utcDateTime.minute.toString().let(::io)
        }
        // Second component of the current UTC date/time.
        key.equals("dt_utc_second", ignoreCase = true) -> {
            utcDateTime.second.toString().let(::io)
        }

        // unknown
        else -> null
    }

    class Factory(
    ) : Placeholder.Factory {
        constructor(
            directDI: DirectDI,
        ) : this(
        )

        override fun createOrNull(
            scope: PlaceholderScope,
        ) = DateTimePlaceholder(
            now = scope.now,
        )
    }
}
