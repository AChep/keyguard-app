package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRoute
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.vault.organizations.WearOrganizationsRoute

object OrganizationsRouteFactoryWear : OrganizationsRouteFactory {
    override fun create(args: OrganizationsRoute.Args): Route {
        return WearOrganizationsRoute(args = args)
    }
}
