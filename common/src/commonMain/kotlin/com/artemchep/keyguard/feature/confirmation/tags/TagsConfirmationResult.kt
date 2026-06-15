package com.artemchep.keyguard.feature.confirmation.tags

sealed interface TagsConfirmationResult {
    data object Deny : TagsConfirmationResult

    data class Confirm(
        val tags: List<String>,
    ) : TagsConfirmationResult
}
