package com.artemchep.keyguard.wear.feature.team

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearAboutTeamRoute : Route {
    @Composable
    override fun Content() {
        WearAboutTeamScreen()
    }
}
