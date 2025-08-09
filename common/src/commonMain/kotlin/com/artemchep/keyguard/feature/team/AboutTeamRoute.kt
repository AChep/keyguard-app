package com.artemchep.keyguard.feature.team

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive

object AboutTeamRoute : Route {
    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalExpressive provides GlobalExpressive.current,
        ) {
            AboutTeamScreen()
        }
    }
}
