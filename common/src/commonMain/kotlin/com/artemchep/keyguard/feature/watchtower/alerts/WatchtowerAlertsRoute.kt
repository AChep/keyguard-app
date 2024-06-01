package com.artemchep.keyguard.feature.watchtower.alerts

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route

data class WatchtowerAlertsRoute(
    val args: Args = Args(),
) : Route {
    companion object {
        const val ROUTER_NAME = "watchtower_alerts"
    }

    data class Args(
        val filter: DFilter? = null,
    )

    @Composable
    override fun Content() {
        WatchtowerAlerts(
            args = args,
        )
    }
}
