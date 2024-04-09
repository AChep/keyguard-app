package com.artemchep.keyguard.feature.timepicker

import kotlinx.datetime.LocalTime

sealed interface TimePickerResult {
    data object Deny : TimePickerResult

    data class Confirm(
        val localTime: LocalTime,
    ) : TimePickerResult
}
