package com.artemchep.keyguard.common.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number

private const val YEAR_DIGITS = 4

// A sortable `yyyyMMddHHmmss` string in the ISO calendar with ASCII digits,
// independently of the user's locale.
fun LocalDateTime.formatDateTimeMachine(): String = buildString {
    append(year.toString().padStart(YEAR_DIGITS, '0'))
    append(month.number.toString().padStart(2, '0'))
    append(day.toString().padStart(2, '0'))
    append(hour.toString().padStart(2, '0'))
    append(minute.toString().padStart(2, '0'))
    append(second.toString().padStart(2, '0'))
}
