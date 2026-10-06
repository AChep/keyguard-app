package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal object AutomaticBackupsSetupRoute : Route {
    @Composable
    override fun Content() {
        AutomaticBackupsSetupScreen()
    }
}
