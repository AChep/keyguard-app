package com.artemchep.keyguard.feature.watchtower

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive

data class WatchtowerRoute(
    val args: Args = Args(),
) : Route {
    data class Args(
        val filter: DFilter? = null,
    )

    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalExpressive provides GlobalExpressive.current,
        ) {
            WatchtowerScreen(
                args = args,
            )
        }
    }
}
