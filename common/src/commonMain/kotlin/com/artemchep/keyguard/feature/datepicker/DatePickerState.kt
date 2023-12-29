package com.artemchep.keyguard.feature.datepicker

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class DatePickerState(
    val content: Content,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val month: Int,
        val months: ImmutableList<Item>,
        val year: Int,
        val years: ImmutableList<Item>,
    )

    @Stable
    data class Item(
        val key: Int,
        val title: String,
        val index: String? = null,
        val onClick: () -> Unit,
    )
}
