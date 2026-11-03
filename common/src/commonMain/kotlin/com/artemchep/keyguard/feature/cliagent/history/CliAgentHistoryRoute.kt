package com.artemchep.keyguard.feature.cliagent.history

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

/**
 * The request log of the CLI agent: the requests of one [client], or
 * all the requests that reached one environment.
 */
data class CliAgentHistoryRoute(
    val client: CliAgentUsageHistoryClient? = null,
    val cipherId: String? = null,
) : Route {
    override val descriptor get() = RouteDescriptor.CliAgentHistory(client, cipherId)

    @Composable
    override fun Content() {
        CliAgentHistoryScreen(
            client = client,
            cipherId = cipherId,
        )
    }
}
