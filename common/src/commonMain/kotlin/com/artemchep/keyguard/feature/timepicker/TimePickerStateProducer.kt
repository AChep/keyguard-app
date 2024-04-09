package com.artemchep.keyguard.feature.timepicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun produceTimePickerState(
    args: TimePickerRoute.Args,
    transmitter: RouteResultTransmitter<TimePickerResult>,
): Loadable<TimePickerState> = produceScreenState(
    key = "time_picker",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val initialTime = args.initialTime
        ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time

    val timeSink = mutablePersistedFlow("time") {
        initialTime
    }

    val content = TimePickerState.Content(
        initialTime = initialTime,
        onSelect = timeSink::value::set,
    )
    timeSink
        .map { time ->
            val state = TimePickerState(
                content = content,
                onDeny = {
                    transmitter(TimePickerResult.Deny)
                    navigatePopSelf()
                },
                onConfirm = {
                    val result = TimePickerResult.Confirm(
                        localTime = time,
                    )
                    transmitter(result)
                    navigatePopSelf()
                },
            )
            Loadable.Ok(state)
        }
}
