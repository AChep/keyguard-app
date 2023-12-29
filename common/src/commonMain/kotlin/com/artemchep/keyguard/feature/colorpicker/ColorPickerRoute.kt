package com.artemchep.keyguard.feature.colorpicker

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope

data class ColorPickerRoute(
    val args: Args,
) : DialogRouteForResult<ColorPickerResult> {
    data class Args(
        val color: Color? = null,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<ColorPickerResult>,
    ) {
        ColorPickerScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

inline fun RememberStateFlowScope.createColorPickerDialogIntent(
    args: ColorPickerRoute.Args,
    noinline onSuccess: (Color) -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        route = ColorPickerRoute(
            args = args,
        ),
    ) { result ->
        if (result is ColorPickerResult.Confirm) {
            val arg = result.color
            onSuccess(arg)
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}
