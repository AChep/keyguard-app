package com.artemchep.keyguard.feature.home.settings.subscriptions

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

object SubscriptionsSettingsRoute : Route {
    // Data-only identity so the Apple bridge can map this route to its native
    // subscriptions screen (the vault-list paywall CTA navigates here).
    override val descriptor: RouteDescriptor
        get() = RouteDescriptor.Subscriptions

    @Composable
    override fun Content() {
        SubscriptionsSettingsScreen()
    }
}
