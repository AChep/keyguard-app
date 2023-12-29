package com.artemchep.keyguard.feature.datepicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data class DatePickerRoute(
    val args: Args,
) : DialogRouteForResult<DatePickerResult> {
    data class Args(
        val month: Int? = null,
        val year: Int? = null,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<DatePickerResult>,
    ) {
        DatePickerScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
