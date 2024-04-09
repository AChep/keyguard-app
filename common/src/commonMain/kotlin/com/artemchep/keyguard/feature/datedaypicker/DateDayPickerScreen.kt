package com.artemchep.keyguard.feature.datedaypicker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun DatePickerScreen(
    args: DateDayPickerRoute.Args,
    transmitter: RouteResultTransmitter<DateDayPickerResult>,
) {
    val loadableState = produceDatePickerState(
        args = args,
        transmitter = transmitter,
    )
    Dialog(
        title = null,
        content = {
            Column {
                val state = loadableState.getOrNull()
                    ?: return@Column
                Content(
                    state = state,
                )
            }
        },
        contentScrollable = true,
        actions = {
            val updatedOnClose by rememberUpdatedState(loadableState.getOrNull()?.onDeny)
            val updatedOnOk by rememberUpdatedState(loadableState.getOrNull()?.onConfirm)
            TextButton(
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.strings.close))
            }
            TextButton(
                enabled = updatedOnOk != null,
                onClick = {
                    updatedOnOk?.invoke()
                },
            ) {
                Text(stringResource(Res.strings.ok))
            }
        },
    )
}

@Composable
private fun ColumnScope.Content(
    state: DateDayPickerState,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = state.content.initialDateMs,
        yearRange = state.content.selectableYears,
        selectableDates = state.content.selectableDates,
    )
    DatePicker(
        modifier = Modifier
            .fillMaxWidth(),
        state = datePickerState,
    )

    val updatedOnSelect by rememberUpdatedState(state.content.onSelect)
    LaunchedEffect(datePickerState) {
        snapshotFlow {
            val ms = datePickerState.selectedDateMillis
                ?: return@snapshotFlow null
            val date = Instant.fromEpochMilliseconds(ms)
                .toLocalDateTime(TimeZone.UTC)
                .date
            date
        }
            .onEach { date ->
                updatedOnSelect(date)
            }
            .collect()
    }
}
