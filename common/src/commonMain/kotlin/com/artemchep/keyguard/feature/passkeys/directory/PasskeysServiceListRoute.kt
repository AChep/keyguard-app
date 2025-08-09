package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive

object PasskeysServiceListRoute : Route {
    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalExpressive provides GlobalExpressive.current,
        ) {
            PasskeysListScreen()
        }
    }
}
