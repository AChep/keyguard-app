package com.artemchep.keyguard.feature.gpgkey.selection

import androidx.compose.runtime.Immutable

@Immutable
data class GpgUserIdSelectionState(
    val identities: List<Identity> = emptyList(),
    val validationError: String? = null,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    data class Identity(
        val key: String,
        val title: String,
        val selected: Boolean,
        val onSelect: () -> Unit,
    )
}
