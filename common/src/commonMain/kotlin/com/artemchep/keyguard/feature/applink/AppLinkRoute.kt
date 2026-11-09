package com.artemchep.keyguard.feature.applink

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.applink.connection.AppLinkConnectionScreen
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

/** Links this app with the apps on the user's other devices. */
data object AppLinkRoute : Route {
    override val descriptor get() = RouteDescriptor.AppLink

    @Composable
    override fun Content() {
        AppLinkScreen()
    }
}

/**
 * Pairs this app with the app on another device; which of the two admits the other one
 * follows from their links. This device [scan]s the code of the other one, or shows its own.
 */
data class AppLinkPairRoute(
    val scan: Boolean,
) : Route {
    override val descriptor get() = RouteDescriptor.AppLinkPair(scan = scan)

    @Composable
    override fun Content() {
        AppLinkPairScreen(
            scan = scan,
        )
    }
}

/** How this device reaches its linked apps: the relay and the local network. */
data object AppLinkConnectionRoute : Route {
    override val descriptor get() = RouteDescriptor.AppLinkConnection

    @Composable
    override fun Content() {
        AppLinkConnectionScreen()
    }
}
