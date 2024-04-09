package com.artemchep.keyguard.feature.datedaypicker

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

@Immutable
data class DateDayPickerState(
    val content: Content,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val initialDateMs: Long,
        val selectableYears: IntRange = DatePickerDefaults.YearRange,
        @Stable
        val selectableDates: SelectableDates = DatePickerDefaults.AllDates,
        val onSelect: (LocalDate?) -> Unit,
    )
}
