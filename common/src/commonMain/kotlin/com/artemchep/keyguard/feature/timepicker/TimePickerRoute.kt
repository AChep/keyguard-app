package com.artemchep.keyguard.feature.timepicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import kotlinx.datetime.LocalTime

data class TimePickerRoute(
    val args: Args,
) : DialogRouteForResult<TimePickerResult> {
    data class Args(
        val initialTime: LocalTime? = null,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<TimePickerResult>,
    ) {
        TimePickerScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
