package com.artemchep.keyguard.wear.feature.passkeys

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute

data class WearPasskeysCredentialViewRoute(
    val args: PasskeysCredentialViewRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        WearPasskeysCredentialViewScreen(
            args = args,
        )
    }
}
