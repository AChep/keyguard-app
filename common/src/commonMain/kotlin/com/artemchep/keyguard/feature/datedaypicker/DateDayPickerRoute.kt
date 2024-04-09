package com.artemchep.keyguard.feature.datedaypicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import kotlinx.datetime.LocalDate

data class DateDayPickerRoute(
    val args: Args,
) : DialogRouteForResult<DateDayPickerResult> {
    data class Args(
        val initialDate: LocalDate? = null,
        val selectableDates: ClosedRange<LocalDate>? = null,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<DateDayPickerResult>,
    ) {
        DatePickerScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
