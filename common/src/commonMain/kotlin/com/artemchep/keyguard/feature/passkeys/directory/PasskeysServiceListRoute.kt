package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

object PasskeysServiceListRoute : Route {
    @Composable
    override fun Content() {
        PasskeysListScreen()
    }
}
