package com.artemchep.keyguard.feature.watchtower

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route

data class WatchtowerRoute(
    val args: Args = Args(),
) : Route {
    data class Args(
        val filter: DFilter? = null,
    )

    @Composable
    override fun Content() {
        WatchtowerScreen(
            args = args,
        )
    }
}
