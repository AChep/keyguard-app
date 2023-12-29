package com.artemchep.keyguard.feature.datepicker

import java.time.Month
import java.time.Year

sealed interface DatePickerResult {
    data object Deny : DatePickerResult

    data class Confirm(
        val month: Month,
        val year: Year,
    ) : DatePickerResult
}
