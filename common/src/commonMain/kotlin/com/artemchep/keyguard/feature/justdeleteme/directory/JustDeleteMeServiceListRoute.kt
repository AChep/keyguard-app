package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive

object JustDeleteMeServiceListRoute : Route {
    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalExpressive provides GlobalExpressive.current,
        ) {
            JustDeleteMeListScreen()
        }
    }
}
