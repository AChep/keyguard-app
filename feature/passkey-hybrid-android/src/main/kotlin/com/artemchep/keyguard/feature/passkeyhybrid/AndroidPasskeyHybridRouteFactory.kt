package com.artemchep.keyguard.feature.passkeyhybrid

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.passkeys.PasskeyHybridRouteFactory

object AndroidPasskeyHybridRouteFactory : PasskeyHybridRouteFactory {
    override fun create(): Route = HybridSignInRoute()
}

internal class HybridSignInRoute : Route {
    @Composable
    override fun Content() {
        HybridSignInScreen(produceHybridSignInState())
    }
}
