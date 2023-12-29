package com.artemchep.keyguard.feature.confirmation

sealed interface ConfirmationResult {
    data object Deny : ConfirmationResult

    data class Confirm(
        val data: Map<String, Any?>,
    ) : ConfirmationResult
}
