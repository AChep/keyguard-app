package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class PasskeysServiceViewFullRoute(
    val args: PasskeysServiceViewDialogRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        PasskeysViewFullScreen(
            args = args,
        )
    }
}
