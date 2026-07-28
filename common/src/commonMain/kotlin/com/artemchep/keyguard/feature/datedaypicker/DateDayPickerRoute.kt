package com.artemchep.keyguard.feature.datedaypicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
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

inline fun RememberStateFlowScope.createDateDayPickerDialogIntent(
    args: DateDayPickerRoute.Args,
    crossinline onSuccess: (LocalDate) -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        route = DateDayPickerRoute(args),
    ) { result ->
        if (
            result is DateDayPickerResult.Confirm &&
            args.selectableDates?.contains(result.localDate) != false
        ) {
            onSuccess(result.localDate)
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}
