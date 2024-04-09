package com.artemchep.keyguard.feature.timepicker

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalTime

@Immutable
data class TimePickerState(
    val content: Content,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val initialTime: LocalTime,
        val onSelect: (LocalTime) -> Unit,
    )
}
