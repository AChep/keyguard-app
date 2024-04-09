package com.artemchep.keyguard.feature.datedaypicker

import kotlinx.datetime.LocalDate

sealed interface DateDayPickerResult {
    data object Deny : DateDayPickerResult

    data class Confirm(
        val localDate: LocalDate,
    ) : DateDayPickerResult
}
