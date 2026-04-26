package com.artemchep.keyguard.wear.feature.picker

sealed interface WearPickerResult {
    data object Deny : WearPickerResult

    data class Confirm(
        val data: Map<String, Any?>,
    ) : WearPickerResult
}
