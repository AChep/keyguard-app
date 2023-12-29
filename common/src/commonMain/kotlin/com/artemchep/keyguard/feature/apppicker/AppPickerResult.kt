package com.artemchep.keyguard.feature.apppicker

sealed interface AppPickerResult {
    data object Deny : AppPickerResult

    data class Confirm(
        val uri: String,
    ) : AppPickerResult
}
