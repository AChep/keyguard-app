package com.artemchep.keyguard.feature.confirmation.elevatedaccess

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.MutableStateFlow

class ElevatedAccessRoute(
) : DialogRouteForResult<ElevatedAccessResult> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<ElevatedAccessResult>,
    ) {
        ElevatedAccessScreen(
            transmitter = transmitter,
        )
    }
}

fun RememberStateFlowScope.createElevatedAccessDialogIntent(
    onSuccess: () -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        route = ElevatedAccessRoute(),
    ) { result ->
        if (result is ElevatedAccessResult.Allow) {
            onSuccess()
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}

/**
 * Returns a gate that asks the user to confirm access before
 * running a block, or `null` if the confirmation is not [required].
 *
 * If [granted] is set, then a successful confirmation is remembered
 * there and the following blocks run without asking again.
 */
fun RememberStateFlowScope.createElevatedAccessVerify(
    required: Boolean,
    granted: MutableStateFlow<Boolean>? = null,
): ((() -> Unit) -> Unit)? {
    if (!required) {
        return null
    }
    return { block ->
        if (granted?.value == true) {
            block()
        } else {
            val intent = createElevatedAccessDialogIntent {
                granted?.value = true
                block()
            }
            navigate(intent)
        }
    }
}
