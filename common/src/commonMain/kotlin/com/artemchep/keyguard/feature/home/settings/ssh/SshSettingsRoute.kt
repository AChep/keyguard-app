package com.artemchep.keyguard.feature.home.settings.ssh

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

object SshSettingsRoute : Route {
    @Composable
    override fun Content() {
        SshSettingsScreen()
    }
}
