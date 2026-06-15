package com.artemchep.keyguard.wear.feature.vault.organizations

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRoute
import com.artemchep.keyguard.feature.navigation.Route

data class WearOrganizationsRoute(
    val args: OrganizationsRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        WearOrganizationsScreen(
            args = args,
        )
    }
}
