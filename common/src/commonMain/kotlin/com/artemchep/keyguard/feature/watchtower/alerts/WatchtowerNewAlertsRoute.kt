package com.artemchep.keyguard.feature.watchtower.alerts

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route

data class WatchtowerNewAlertsRoute(
    val args: WatchtowerAlertsRoute.Args = WatchtowerAlertsRoute.Args(),
) : Route {
    @Composable
    override fun Content() {
        WatchtowerNewAlerts(
            args = args,
        )
    }
}
