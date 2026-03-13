package com.artemchep.keyguard.feature.datepicker

import kotlinx.datetime.Month

sealed interface DatePickerResult {
    data object Deny : DatePickerResult

    data class Confirm(
        val month: Month,
        val year: Int,
    ) : DatePickerResult
}
